package io.github.ysl_ovo.douyuplus.hooks;

import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;
import io.github.ysl_ovo.douyuplus.DouyuLog;
import io.github.ysl_ovo.douyuplus.DouyuPlusConfig;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.WeakHashMap;

/**
 * 自动最高画质 Hook (零卡顿秒开 + 2K/4K/原画精准梯队 + 用户手动切换豁免)
 * 1. 起播请求直接命中最高画质 (DYRtmpPlayerLoader.l() 返回 0 / 原画，关掉自适应与协商降级)，首播即最高画质；
 * 2. 监听 RamboDefinitionNeuron.lC(RoomRtmpInfo)，在下发流瞬间进行瞬间同步，废除 600ms 滞后切流；
 * 3. 严格梯队评分：4K(50000) > 2K(40000) > HDR(35000) > 原画(30000) > 蓝光(20000) > 超清(10000)；
 * 4. 彻底修复原画 bit 码率反超 2K 的 bug；码率仅作同梯队微调决胜；
 * 5. 官方赛事直播间（如王者荣耀 998）2K 档位免受 diamondFan 误杀拦截；
 * 6. 【重大修复】支持用户手动切换画质：
 *    当用户在清晰度菜单手动选择画质时，记录当前房间的手动状态，
 *    停止后续自动切回最高画质，彻底解决“用户切不了画质被强切回最高画质”的 bug；
 *    切房间时自动重置，新房间恢复自动最高画质。
 */
public class AutoQualityHook {
    private static final String TAG = "DouyuPlus_AutoQuality";
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());
    private static String sLastSwitchedRoomId = null;
    private static String sLastSwitchedRate = null;

    // 手动切换豁免机制：记录当前房间用户是否主动手动选择过画质
    private static volatile boolean sIsAutoSwitching = false;
    private static volatile boolean sHasUserManualSwitched = false;
    private static volatile String sUserManualRoomId = null;
    private static volatile String sUserManualRate = null;
    private static volatile java.lang.ref.WeakReference<Object> sCurrentNeuronRef = null;
    private static volatile ClassLoader sClassLoader = null;

    // 进房自动切换备用线路（策略A：优先在 RamboPlayerPresenter.GB 首播前拦截并 reload 切换，规避 stopPlayback 阻塞）
    // 退房/切房及新进房前会重置全局 Config.rtmp_cdn，确保每次进房先获取默认主线并记住后将其排除，避免重进反切回主线
    private static final WeakHashMap<Object, String> sLineHandledByPresenter = new WeakHashMap<>();
    private static final WeakHashMap<Object, String> sLineHandledByNeuron = new WeakHashMap<>();
    private static final WeakHashMap<Object, String> sUserManualLineByNeuron = new WeakHashMap<>();
    private static final WeakHashMap<Object, DefaultLineRecord> sDefaultMainLineByPresenter = new WeakHashMap<>();
    private static volatile String sLineHandledGlobalRoomId = null;
    private static volatile String sActiveDefaultMainRoomId = null;
    private static volatile String sActiveDefaultMainCdn = null;
    private static volatile boolean sModuleOverrodeGlobalCdn = false;
    private static volatile String sLineReloadInFlightRoomId = null;
    private static volatile String sPendingPreOpenToastRoomId = null;
    private static volatile String sPendingPreOpenLineDisplay = null;
    private static volatile boolean sIsPreOpenReloading = false;
    private static volatile String sPendingOverrideCdn = null;
    private static volatile boolean sSwitchDispatchedInPlayer = false;

    private static final class DefaultLineRecord {
        final String roomId;
        final String cdn;

        DefaultLineRecord(String roomId, String cdn) {
            this.roomId = roomId;
            this.cdn = cdn;
        }
    }

    public static void simulateUserManualSwitch(String targetRate, String targetName) {
        if (sCurrentNeuronRef == null || sCurrentNeuronRef.get() == null || sClassLoader == null) {
            Log.w(TAG, "simulateUserManualSwitch failed: current neuron is null");
            return;
        }
        try {
            Object neuron = sCurrentNeuronRef.get();
            Class<?> rateBeanCls = XposedHelpers.findClass("com.douyu.sdk.playerframework.business.live.liveuser.beans.LiveRateBean", sClassLoader);
            Object bean = rateBeanCls.newInstance();
            XposedHelpers.setObjectField(bean, "rate", targetRate);
            XposedHelpers.setObjectField(bean, "name", targetName != null ? targetName : ("Rate_" + targetRate));
            Log.i(TAG, "Simulating user manual switch to rate=" + targetRate + " (name=" + targetName + ")");
            XposedHelpers.callMethod(neuron, "aF", bean);
        } catch (Throwable t) {
            Log.e(TAG, "simulateUserManualSwitch error", t);
        }
    }

    public static void hook(final XC_LoadPackage.LoadPackageParam lpparam) {
        int hookedTargets = 0;

        // 0. 策略A核心：在 RamboPlayerPresenter.GB(RoomRtmpInfo) 首次 openVideo/prepareAsync 之前完成备用线路切换
        if (hookPreOpenLineSwitch(lpparam)) {
            hookedTargets++;
        }

        // 1. 起播请求阶段：优化 DYRtmpPlayerLoader.l()
        // 仅在官方返回 -1 (自适应降级) 时兜底为 0 (原画秒开)；
        // 当存在真实目标画质 (例如 2K=15, 4K=16, 超清=2, 流畅=3 等 >= 0) 时 100% 放行真实请求！
        try {
            Class<?> playerLoaderCls = XposedHelpers.findClassIfExists(
                    "com.douyu.sdk.playerframework.business.live.liveuser.rtmp.DYRtmpPlayerLoader",
                    lpparam.classLoader
            );
            if (playerLoaderCls != null) {
                XposedHelpers.findAndHookMethod(playerLoaderCls, "l", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        int mode = DouyuPlusConfig.getAutoQualityMode(null);
                        if (mode == DouyuPlusConfig.AUTO_QUALITY_OFF) return;
                        if (mode == DouyuPlusConfig.AUTO_QUALITY_WIFI_ONLY && !isWifiConnected(null)) return;

                        Object resultObj = param.getResult();
                        int currentResult = resultObj instanceof Integer ? (Integer) resultObj : -1;

                        // 核心修复：
                        // 1. 如果已有明确的目标画质 (currentResult >= 0，例如 15=2K, 16=4K, 2=超清 等)，
                        //    坚决放行，绝对不能覆盖为 0，否则向服务器发出的请求会被生生篡改成原画！
                        // 2. 只有当返回值是 -1 (官方自适应降级模式)，且用户在当前房间未手动选择画质时，
                        //    才兜底指定为 0 (原画)，实现首播秒开免降级！
                        if (currentResult < 0 && !sHasUserManualSwitched) {
                            if (!isUserLoggedIn(lpparam.classLoader)) {
                                DouyuLog.d(TAG, "DYRtmpPlayerLoader.l(): skipped rate=0 override because user is not logged in");
                                return;
                            }
                            param.setResult(0);
                            DouyuLog.d(TAG, "DYRtmpPlayerLoader.l(): replaced adaptive (-1) with rate=0 for initial start");
                        }
                    }
                });

                // 强制关闭官方自适应动态切换标志，防止向服务端请求 rate=-1
                XposedHelpers.findAndHookMethod(playerLoaderCls, "r", XC_MethodReplacement.returnConstant(false));
                hookedTargets++;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook DYRtmpPlayerLoader", t);
        }

        // 2. 监听 RamboDefinitionNeuron
        try {
            final Class<?> neuronCls = XposedHelpers.findClassIfExists(
                    "com.douyu.module.player.p.rambodefinition.RamboDefinitionNeuron",
                    lpparam.classLoader
            );
            if (neuronCls == null) {
                Log.i(TAG, "Hooked AutoQuality (" + hookedTargets + "/4 targets)");
                return;
            }

            Class<?> rtmpInfoCls = XposedHelpers.findClassIfExists(
                    "com.douyu.sdk.playerframework.business.live.liveuser.beans.RoomRtmpInfo",
                    lpparam.classLoader
            );

            // Hook aF: 捕获用户在清晰度菜单的手动选择
            Class<?> liveRateBeanCls = XposedHelpers.findClassIfExists(
                    "com.douyu.sdk.playerframework.business.live.liveuser.beans.LiveRateBean",
                    lpparam.classLoader
            );
            if (liveRateBeanCls != null) {
                XposedHelpers.findAndHookMethod(neuronCls, "aF", liveRateBeanCls, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (sIsAutoSwitching) {
                            // 模块自动切画质触发，忽略
                            return;
                        }

                        Object targetRateBean = param.args[0];
                        if (targetRateBean == null) return;

                        Object neuron = param.thisObject;
                        String roomId = getRoomIdFromNeuronOrGlobal(neuron, lpparam.classLoader);

                        String rate = "";
                        String name = "";
                        try {
                            rate = (String) XposedHelpers.getObjectField(targetRateBean, "rate");
                            name = (String) XposedHelpers.getObjectField(targetRateBean, "name");
                        } catch (Throwable ignored) {}

                        sHasUserManualSwitched = true;
                        sUserManualRoomId = roomId;
                        sUserManualRate = rate;
                        Log.i(TAG, "User manually switched quality: name=" + name + ", rate=" + rate + " for roomId=" + roomId);
                    }
                });
            }

            // Hook ZE: 捕获用户手动切换线路，防止后续 onVideoPrepared 覆盖用户选择
            Class<?> lineBeanCls = XposedHelpers.findClassIfExists(
                    "com.douyu.sdk.playerframework.business.live.liveuser.beans.LineBean",
                    lpparam.classLoader
            );
            if (lineBeanCls != null) {
                XposedHelpers.findAndHookMethod(neuronCls, "ZE", lineBeanCls, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (sIsAutoSwitching) {
                            return;
                        }
                        Object neuron = param.thisObject;
                        String roomId = getRoomIdFromNeuronOrGlobal(neuron, lpparam.classLoader);
                        if (!TextUtils.isEmpty(roomId)) {
                            markLineHandled(neuron, roomId);
                            markUserManualLine(neuron, roomId);
                            Log.i(TAG, "User manually switched line for roomId=" + roomId);
                        }
                    }
                });
            }

            if (rtmpInfoCls != null) {
                // Hook lC: 下发流信息瞬间，立即同步最高画质
                XposedHelpers.findAndHookMethod(neuronCls, "lC", rtmpInfoCls, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        final Object roomRtmpInfo = param.args[0];
                        if (roomRtmpInfo == null) return;

                        // 强制关闭 autoEnable，防止官方协商自适应降级
                        try {
                            XposedHelpers.setObjectField(roomRtmpInfo, "autoEnable", "0");
                        } catch (Throwable ignored) {}
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        final Object neuron = param.thisObject;
                        final Object roomRtmpInfo = param.args[0];
                        if (roomRtmpInfo == null) return;

                        if (DouyuPlusConfig.isDebugModeEnabled()) {
                            logRtmpInfoDebug(roomRtmpInfo, "lC");
                        }
                        triggerAutoQuality(neuron, roomRtmpInfo, "lC_instant", lpparam.classLoader);
                    }
                });
            }

            // Hook onVideoPrepared: 视频就绪后兜底验证
            XposedHelpers.findAndHookMethod(neuronCls, "onVideoPrepared", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    final Object neuron = param.thisObject;
                    try {
                        Object roomRtmpInfo = XposedHelpers.getObjectField(neuron, "roomRtmpInfo");
                        if (roomRtmpInfo != null) {
                            if (DouyuPlusConfig.isDebugModeEnabled()) {
                                logRtmpInfoDebug(roomRtmpInfo, "onVideoPrepared");
                            }
                            triggerAutoQuality(neuron, roomRtmpInfo, "onVideoPrepared", lpparam.classLoader);
                        }
                    } catch (Throwable ignored) {}
                }
            });

            // Hook onRoomChange: 同 Activity 内滑屏换房时清理线路状态
            try {
                XposedHelpers.findAndHookMethod(neuronCls, "onRoomChange", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        clearLineStateForNeuron(param.thisObject);
                        sPendingOverrideCdn = null;
                        if (!sIsPreOpenReloading && TextUtils.isEmpty(sLineReloadInFlightRoomId)) {
                            clearActiveDefaultMainLine(null, true);
                            clearGlobalCdnConfigIfNeeded(null, null, lpparam.classLoader);
                        }
                    }
                });
            } catch (Throwable ignored) {}

            // Hook BE (房间变更): 重置房间切换记录与手动豁免状态
            Class<?> roomInfoCls = XposedHelpers.findClassIfExists(
                    "com.douyu.sdk.playerframework.business.live.liveuser.beans.RoomInfoBean",
                    lpparam.classLoader
            );
            if (roomInfoCls != null) {
                XposedHelpers.findAndHookMethod(neuronCls, "BE", roomInfoCls, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            Object neuron = param.thisObject;
                            Object roomInfo = param.args[0];
                            if (roomInfo != null) {
                                String newRoomId = (String) XposedHelpers.getObjectField(roomInfo, "roomId");
                                if (!TextUtils.isEmpty(newRoomId)) {
                                    resetManualAndSwitchedIfRoomChanged(newRoomId, true);
                                    clearLineStateIfRoomChanged(neuron, newRoomId);
                                    clearGlobalPreOpenIfRoomChanged(newRoomId);
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                });
            }

            hookedTargets++;
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook RamboDefinitionNeuron", t);
        }

        // 3. Hook PlayerRateAndLine.i(String cdn, int videoResolutionState, boolean seamlessSwitch):
        // 在画质与线路同时自动切换时替换目标 CDN，实现单次合并切流并跟踪底层实际派发状态
        try {
            Class<?> rateAndLineCls = XposedHelpers.findClassIfExists(
                    "com.douyu.module.player.p.common.player.PlayerRateAndLine",
                    lpparam.classLoader
            );
            if (rateAndLineCls != null) {
                XposedHelpers.findAndHookMethod(rateAndLineCls, "i", String.class, int.class, boolean.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        String origCdn = String.valueOf(param.args[0]);
                        String overrideCdn = sPendingOverrideCdn;
                        if (!TextUtils.isEmpty(overrideCdn)) {
                            sPendingOverrideCdn = null;
                            if (sIsAutoSwitching) {
                                // 后备切线走非无缝重载 (w2 + reload)，规避 ManualRateSwitcher 无缝切线卡顿
                                param.args[2] = false;
                            }
                            DouyuLog.d(TAG, "PlayerRateAndLine.i: overriding cdn " + param.args[0] + " -> " + overrideCdn
                                    + " (rateState=" + param.args[1] + ", seamless=" + param.args[2] + ")");
                            param.args[0] = overrideCdn;
                        }
                        if (DouyuPlusConfig.isDebugModeEnabled()) {
                            Log.d(TAG, "[DEBUG] PlayerRateAndLine.i before: origCdn=" + origCdn
                                    + ", finalCdn=" + param.args[0]
                                    + ", rateState=" + param.args[1]
                                    + ", seamless=" + param.args[2]
                                    + ", autoSwitching=" + sIsAutoSwitching);
                        }
                        if (sIsAutoSwitching) {
                            sSwitchDispatchedInPlayer = true;
                            if (!TextUtils.isEmpty(overrideCdn)) {
                                sModuleOverrodeGlobalCdn = true;
                            }
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (DouyuPlusConfig.isDebugModeEnabled()) {
                            Log.d(TAG, "[DEBUG] PlayerRateAndLine.i after: cdn=" + param.args[0]
                                    + ", rateState=" + param.args[1]
                                    + ", seamless=" + param.args[2]);
                        }
                    }
                });
                hookedTargets++;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook PlayerRateAndLine.i", t);
        }

        Log.i(TAG, "Hooked AutoQuality (" + hookedTargets + "/4 targets)");
    }

    private static boolean hookPreOpenLineSwitch(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> presenterCls = XposedHelpers.findClassIfExists(
                    "com.douyu.module.player.p.rambobase.player.RamboPlayerPresenter",
                    lpparam.classLoader
            );
            Class<?> rtmpInfoCls = XposedHelpers.findClassIfExists(
                    "com.douyu.sdk.playerframework.business.live.liveuser.beans.RoomRtmpInfo",
                    lpparam.classLoader
            );
            if (presenterCls == null || rtmpInfoCls == null) {
                return false;
            }

            XposedHelpers.findAndHookMethod(presenterCls, "GB", rtmpInfoCls, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Object presenter = param.thisObject;
                    Object roomRtmpInfo = param.args[0];
                    if (presenter == null || roomRtmpInfo == null) return;

                    Context ctx = getContextFromPresenter(presenter);
                    String roomId = getStringFieldSafe(roomRtmpInfo, "roomId");
                    if (TextUtils.isEmpty(roomId)) return;

                    boolean isActivePresenter = getBooleanFieldSafe(presenter, "Z", true);
                    if (DouyuPlusConfig.isDebugModeEnabled()) {
                        logRtmpInfoDebug(roomRtmpInfo, isActivePresenter ? "GB_before" : "GB_preload_Z0");
                    }

                    if (!DouyuPlusConfig.isAutoSwitchLineEnabled(ctx)) {
                        return;
                    }

                    // 离屏预加载 Presenter (Z == false) 严禁跳过 GB 或调用 reload()，避免污染全局状态并打断当前在播房间
                    if (!isActivePresenter) {
                        if (DouyuPlusConfig.isDebugModeEnabled()) {
                            Log.d(TAG, "GB pre-open skipped: preload presenter (Z=false), roomId=" + roomId
                                    + ", presenter@" + System.identityHashCode(presenter));
                        }
                        return;
                    }

                    if (isPreOpenLineHandled(presenter, roomId)) {
                        if (TextUtils.equals(roomId, sLineReloadInFlightRoomId)) {
                            sLineReloadInFlightRoomId = null;
                        }
                        if (TextUtils.equals(roomId, sPendingPreOpenToastRoomId)) {
                            final String lineDisplay = sPendingPreOpenLineDisplay;
                            sPendingPreOpenToastRoomId = null;
                            sPendingPreOpenLineDisplay = null;
                            if (ctx != null && !TextUtils.isEmpty(lineDisplay)) {
                                final Context toastCtx = ctx;
                                sMainHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            Toast.makeText(toastCtx,
                                                    "斗鱼Plus: 已自动切换至备用线路 (" + lineDisplay + ")",
                                                    Toast.LENGTH_SHORT).show();
                                        } catch (Throwable ignored) {}
                                    }
                                });
                            }
                        }
                        return;
                    }

                    // 必须处于尚未 openVideo/prepareAsync 的首播前状态 (H == true)，避免在播期间调用 stopPlayback
                    boolean notOpenedYet = getBooleanFieldSafe(presenter, "H", false);
                    if (!notOpenedYet) {
                        DouyuLog.d(TAG, "GB pre-open skipped: player already opened (H=false), roomId=" + roomId);
                        return;
                    }
                    if (isAudioOrMixedOrPaidBeforeOpen(presenter, roomRtmpInfo)) {
                        markPreOpenLineHandled(presenter, roomId);
                        return;
                    }

                    String currentCdn = getStringFieldSafe(roomRtmpInfo, "rtmp_cdn");
                    String defaultMainCdn = recordOrGetPresenterDefaultMainCdn(presenter, roomId, currentCdn);
                    if (!TextUtils.isEmpty(defaultMainCdn) && !TextUtils.isEmpty(currentCdn)
                            && !TextUtils.equals(currentCdn, defaultMainCdn)) {
                        markPreOpenLineHandled(presenter, roomId);
                        DouyuLog.d(TAG, "GB pre-open skipped: already off default main cdn=" + defaultMainCdn
                                + " (current=" + currentCdn + "), roomId=" + roomId);
                        return;
                    }

                    Object candidate = findAlternateLineBeanStrategyA(roomRtmpInfo, defaultMainCdn);
                    markPreOpenLineHandled(presenter, roomId);
                    if (candidate == null) {
                        DouyuLog.d(TAG, "GB pre-open: no alternate non-RTC line found for roomId=" + roomId);
                        return;
                    }

                    String targetCdn = getStringFieldSafe(candidate, "c");
                    String targetName = getStringFieldSafe(candidate, "b");
                    if (TextUtils.isEmpty(targetCdn)) {
                        return;
                    }
                    if (!applyPreOpenCdnConfig(presenter, targetCdn, lpparam.classLoader)) {
                        return;
                    }

                    String lineDisplay = !TextUtils.isEmpty(targetName) ? targetName : targetCdn;
                    sLineReloadInFlightRoomId = roomId;
                    sPendingPreOpenToastRoomId = roomId;
                    sPendingPreOpenLineDisplay = lineDisplay;

                    // 拦截首次 GB 起播，在开播前 reload 以目标备用线路发起全新请求
                    param.setResult(null);
                    Log.i(TAG, "Pre-open Strategy A: skipped initial GB and reloading line="
                            + lineDisplay + " (defaultMain=" + defaultMainCdn
                            + " -> target=" + targetCdn + "), roomId=" + roomId);

                    final Object finalPresenter = presenter;
                    final String finalRoomId = roomId;
                    boolean posted = sMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            boolean handled = isPreOpenLineHandled(finalPresenter, finalRoomId);
                            if (DouyuPlusConfig.isDebugModeEnabled()) {
                                Log.d(TAG, "Pre-open Strategy A runnable running: roomId=" + finalRoomId
                                        + ", presenter@" + System.identityHashCode(finalPresenter)
                                        + ", handled=" + handled);
                            }
                            if (!handled) {
                                return;
                            }
                            sIsPreOpenReloading = true;
                            try {
                                XposedHelpers.callMethod(finalPresenter, "reload");
                            } catch (Throwable t) {
                                if (TextUtils.equals(finalRoomId, sLineReloadInFlightRoomId)) {
                                    sLineReloadInFlightRoomId = null;
                                }
                                if (TextUtils.equals(finalRoomId, sPendingPreOpenToastRoomId)) {
                                    sPendingPreOpenToastRoomId = null;
                                    sPendingPreOpenLineDisplay = null;
                                }
                                Log.w(TAG, "Pre-open Strategy A reload failed for roomId=" + finalRoomId, t);
                            } finally {
                                sIsPreOpenReloading = false;
                            }
                        }
                    });
                    if (DouyuPlusConfig.isDebugModeEnabled()) {
                        Log.d(TAG, "Pre-open Strategy A posted reload runnable=" + posted
                                + ", presenter@" + System.identityHashCode(presenter)
                                + ", roomId=" + roomId);
                    }
                }
            });

            try {
                XposedHelpers.findAndHookMethod(presenterCls, "VC", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!sIsPreOpenReloading) {
                            handlePresenterRoomExitOrRelease(param.thisObject, false, lpparam.classLoader);
                            if (getBooleanFieldSafe(param.thisObject, "Z", true)) {
                                String closedRoomId = getStringFieldSafe(param.thisObject, "X");
                                resetManualAndSwitchedIfRoomClosed(closedRoomId);
                            }
                        }
                    }
                });
            } catch (Throwable ignored) {}

            try {
                XposedHelpers.findAndHookMethod(presenterCls, "TC", String.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        String newRoomId = param.args[0] instanceof String ? (String) param.args[0] : null;
                        handlePresenterRoomStartOrChange(param.thisObject, newRoomId, true, lpparam.classLoader);
                    }
                });
            } catch (Throwable ignored) {}

            try {
                XposedHelpers.findAndHookMethod(presenterCls, "k1", String.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        String newRoomId = param.args[0] instanceof String ? (String) param.args[0] : null;
                        handlePresenterRoomStartOrChange(param.thisObject, newRoomId, false, lpparam.classLoader);
                    }
                });
            } catch (Throwable ignored) {}

            try {
                XposedHelpers.findAndHookMethod(presenterCls, "BB", String.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        String newRoomId = param.args[0] instanceof String ? (String) param.args[0] : null;
                        handlePresenterPreloadRoom(param.thisObject, newRoomId, lpparam.classLoader);
                    }
                });
            } catch (Throwable ignored) {}

            try {
                XposedHelpers.findAndHookMethod(presenterCls, "detachView", boolean.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        handlePresenterRoomExitOrRelease(param.thisObject, true, lpparam.classLoader);
                    }
                });
            } catch (Throwable ignored) {}

            try {
                XposedHelpers.findAndHookMethod(presenterCls, "release", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        handlePresenterRoomExitOrRelease(param.thisObject, true, lpparam.classLoader);
                    }
                });
            } catch (Throwable ignored) {}

            try {
                Class<?> batchPreStreamCls = XposedHelpers.findClassIfExists(
                        "com.douyu.module.player.p.rambobase.prestream.BatchPreStreamManager",
                        lpparam.classLoader
                );
                if (batchPreStreamCls != null) {
                    XposedHelpers.findAndHookMethod(batchPreStreamCls, "requestStreamInfos", List.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (sIsPreOpenReloading || !TextUtils.isEmpty(sLineReloadInFlightRoomId)) {
                                return;
                            }
                            if (!DouyuPlusConfig.isAutoSwitchLineEnabled(null) && !sModuleOverrodeGlobalCdn) {
                                return;
                            }
                            Object config = resolveDouyuConfig(null, lpparam.classLoader);
                            if (config == null) {
                                return;
                            }
                            String prevCdn = (String) XposedHelpers.callMethod(config, "k");
                            if (!TextUtils.isEmpty(prevCdn)) {
                                param.setObjectExtra("dp_prev_cdn", prevCdn);
                                XposedHelpers.callMethod(config, "l0", "");
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object prevObj = param.getObjectExtra("dp_prev_cdn");
                            if (!(prevObj instanceof String) || TextUtils.isEmpty((String) prevObj)) {
                                return;
                            }
                            // 仅在仍有活跃房间会话或正在预开播重载时恢复原值；空闲列表中保持清空状态
                            if (!TextUtils.isEmpty(sActiveDefaultMainRoomId)
                                    || sIsPreOpenReloading
                                    || !TextUtils.isEmpty(sLineReloadInFlightRoomId)) {
                                Object config = resolveDouyuConfig(null, lpparam.classLoader);
                                if (config != null) {
                                    XposedHelpers.callMethod(config, "l0", prevObj);
                                }
                            }
                        }
                    });
                }
            } catch (Throwable ignored) {}

            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook RamboPlayerPresenter pre-open line switching", t);
            return false;
        }
    }

    private static String getRoomIdFromNeuronOrGlobal(Object neuron, ClassLoader classLoader) {
        String roomId = null;
        if (neuron != null) {
            try {
                Object rtmpInfo = XposedHelpers.getObjectField(neuron, "roomRtmpInfo");
                if (rtmpInfo != null) {
                    roomId = (String) XposedHelpers.getObjectField(rtmpInfo, "roomId");
                }
            } catch (Throwable ignored) {}

            if (TextUtils.isEmpty(roomId)) {
                try {
                    Object roomInfo = XposedHelpers.getObjectField(neuron, "roomInfoBean");
                    if (roomInfo != null) {
                        roomId = (String) XposedHelpers.getObjectField(roomInfo, "roomId");
                    }
                } catch (Throwable ignored) {}
            }
        }

        if (TextUtils.isEmpty(roomId) && classLoader != null) {
            try {
                Class<?> mgrCls = XposedHelpers.findClass("com.douyu.sdk.playerframework.business.manager.RoomInfoManager", classLoader);
                Object mgr = XposedHelpers.callStaticMethod(mgrCls, "k");
                if (mgr != null) {
                    roomId = (String) XposedHelpers.callMethod(mgr, "r");
                }
            } catch (Throwable ignored) {}
        }
        return roomId;
    }

    private static void triggerAutoQuality(final Object neuron, final Object roomRtmpInfo, final String source, final ClassLoader classLoader) {
        try {
            sCurrentNeuronRef = new java.lang.ref.WeakReference<>(neuron);
            sClassLoader = classLoader;

            Activity act = null;
            try {
                Object actObj = XposedHelpers.callMethod(neuron, "lE");
                if (actObj instanceof Activity) {
                    act = (Activity) actObj;
                }
            } catch (Throwable ignored) {}

            Context ctx = act;
            final String roomId = resolveRoomId(neuron, roomRtmpInfo, classLoader);
            if (!TextUtils.isEmpty(roomId) && TextUtils.equals(roomId, sLineReloadInFlightRoomId)) {
                DouyuLog.d(TAG, "triggerAutoQuality(" + source + ") skipped: pre-open line reload in flight for roomId=" + roomId);
                return;
            }

            // 1. 独立评估自动最高画质
            boolean needQualitySwitch = false;
            Object targetRateBean = null;
            String bestRate = null;
            String bestName = null;

            String currentStreamRateVal = null;
            try {
                currentStreamRateVal = (String) XposedHelpers.getObjectField(roomRtmpInfo, "rate");
            } catch (Throwable ignored) {}

            boolean qualityManualSkipped = sHasUserManualSwitched
                    && (roomId == null || TextUtils.equals(roomId, sUserManualRoomId));
            int mode = DouyuPlusConfig.getAutoQualityMode(ctx);
            boolean qualityAllowedByConfig = mode == DouyuPlusConfig.AUTO_QUALITY_ALWAYS
                    || (mode == DouyuPlusConfig.AUTO_QUALITY_WIFI_ONLY && isWifiConnected(ctx));

            if (qualityManualSkipped) {
                DouyuLog.d(TAG, "Auto quality skipped: user manually selected rate=" + sUserManualRate + " for room=" + roomId);
            } else if (!qualityAllowedByConfig) {
                if (mode == DouyuPlusConfig.AUTO_QUALITY_WIFI_ONLY) {
                    DouyuLog.d(TAG, "Auto quality skipped: current network is mobile, not WiFi");
                }
            } else {
                List<?> rateList = (List<?>) XposedHelpers.getObjectField(roomRtmpInfo, "rateBeanList");
                if (rateList != null && !rateList.isEmpty()) {
                    boolean isLoggedIn = isUserLoggedIn(classLoader);
                    Object bestBean = findHighestQualityBean(rateList, isLoggedIn);
                    if (bestBean != null) {
                        bestRate = (String) XposedHelpers.getObjectField(bestBean, "rate");
                        bestName = (String) XposedHelpers.getObjectField(bestBean, "name");
                        int bestRateInt = 0;
                        try {
                            bestRateInt = Integer.parseInt(bestRate);
                        } catch (Throwable ignored) {}

                        boolean alreadyHighest = false;
                        if (!TextUtils.isEmpty(currentStreamRateVal)) {
                            alreadyHighest = TextUtils.equals(currentStreamRateVal, bestRate);
                        } else {
                            try {
                                Object config = XposedHelpers.callMethod(neuron, "VE");
                                if (config != null) {
                                    int currentPlayRate = (int) XposedHelpers.callMethod(config, "r");
                                    boolean isAuto = (boolean) XposedHelpers.callMethod(config, "B");
                                    if (!isAuto && currentPlayRate == bestRateInt) {
                                        alreadyHighest = true;
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }

                        if (alreadyHighest) {
                            // 如果当前已经在播放最高画质，仅确保关闭自适应并同步配置
                            try {
                                XposedHelpers.callMethod(neuron, "lu", false, false);
                            } catch (Throwable ignored) {}
                        } else if (!TextUtils.equals(roomId, sLastSwitchedRoomId) || !TextUtils.equals(bestRate, sLastSwitchedRate)) {
                            needQualitySwitch = true;
                            targetRateBean = bestBean;
                        }
                    }
                }
            }

            // 2. 独立评估进房自动切换备用线路 (策略A: 仅进房触发一次，独立于自动画质配置，不随切画质重切)
            // lC 在 RamboPlayerPresenter.UB 中可能早于 yB()->GB() 1 行触发，跳过 lC_instant 的兜底切线以免与预开播策略 A 竞态
            boolean needLineSwitch = false;
            Object targetLineBean = null;
            String targetLineCdn = null;
            String targetLineName = null;

            if (!"lC_instant".equals(source)
                    && DouyuPlusConfig.isAutoSwitchLineEnabled(ctx)
                    && !TextUtils.isEmpty(roomId)
                    && !isLineHandled(neuron, roomId)
                    && !isUserManualLine(neuron, roomId)
                    && !isAudioOrVideoOrPaidRoom(neuron, roomRtmpInfo)) {
                String currentRtmpCdn = getStringFieldSafe(roomRtmpInfo, "rtmp_cdn");
                String excludedMainCdn = getActiveDefaultMainCdnForRoom(roomId, currentRtmpCdn);
                if (!TextUtils.isEmpty(excludedMainCdn) && !TextUtils.isEmpty(currentRtmpCdn)
                        && !TextUtils.equals(currentRtmpCdn, excludedMainCdn)) {
                    // 当前流已离开本房默认主线，直接记录状态并放行，避免切回默认主线
                    markLineHandled(neuron, roomId);
                } else {
                    Object candidate = findAlternateLineBeanStrategyA(roomRtmpInfo, excludedMainCdn);
                    if (candidate != null) {
                        String cdn = (String) XposedHelpers.getObjectField(candidate, "c");
                        String name = (String) XposedHelpers.getObjectField(candidate, "b");
                        if (!TextUtils.isEmpty(cdn)) {
                            recordActiveDefaultMainCdn(roomId, excludedMainCdn);
                            needLineSwitch = true;
                            targetLineBean = candidate;
                            targetLineCdn = cdn;
                            targetLineName = name;
                        }
                    }
                }
            }

            if (!needQualitySwitch && !needLineSwitch) {
                return;
            }

            if (needQualitySwitch) {
                sLastSwitchedRoomId = roomId;
                sLastSwitchedRate = bestRate;
            }
            if (needLineSwitch) {
                markLineHandled(neuron, roomId);
            }

            final Activity finalAct = act;
            final boolean postQuality = needQualitySwitch;
            final boolean postLine = needLineSwitch;
            final Object finalTargetRateBean = targetRateBean;
            final String finalBestName = bestName;
            final String finalBestRate = bestRate;
            final Object finalTargetLineBean = targetLineBean;
            final String finalTargetLineCdn = targetLineCdn;
            final String finalTargetLineName = targetLineName;
            final String finalCurrentStreamRate = currentStreamRateVal;

            sMainHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        // 若排队期间预开播策略 A 已发起 reload，延迟到 reload 完成后的新流回调再执行，防止抢跑与误记已切状态
                        if (!TextUtils.isEmpty(roomId)
                                && (TextUtils.equals(roomId, sLineReloadInFlightRoomId) || sIsPreOpenReloading)) {
                            rollbackQueuedSwitchTracking(neuron, roomId, postQuality, postLine);
                            DouyuLog.d(TAG, "triggerAutoQuality(" + source + ") runnable deferred: pre-open line reload in flight for roomId=" + roomId);
                            return;
                        }

                        // 防止 post 排队期间用户已切房
                        String currentNeuronRoomId = getRoomIdFromNeuronOrGlobal(neuron, classLoader);
                        if (!TextUtils.isEmpty(roomId)
                                && !TextUtils.isEmpty(currentNeuronRoomId)
                                && !TextUtils.equals(roomId, currentNeuronRoomId)) {
                            rollbackQueuedSwitchTracking(neuron, roomId, postQuality, postLine);
                            return;
                        }

                        boolean runQuality = postQuality && finalTargetRateBean != null
                                && !(sHasUserManualSwitched && (roomId == null || TextUtils.equals(roomId, sUserManualRoomId)));
                        boolean runLine = postLine && finalTargetLineBean != null
                                && !TextUtils.isEmpty(finalTargetLineCdn)
                                && !isUserManualLine(neuron, roomId);

                        if (!runQuality && !runLine) {
                            rollbackQueuedSwitchTracking(neuron, roomId, postQuality, postLine);
                            return;
                        }

                        sSwitchDispatchedInPlayer = false;
                        sPendingOverrideCdn = runLine ? finalTargetLineCdn : null;
                        sIsAutoSwitching = true;
                        try {
                            if (runQuality) {
                                // 1. 关闭自适应画质，防止被官方协商降级
                                try {
                                    XposedHelpers.callMethod(neuron, "lu", false, true);
                                } catch (Throwable ignored) {}

                                // 若本地 Config.r() 已等于目标最高画质但当前实际流码率低于最高画质，
                                // 需同步修正 Config.v0 避免官方 aF / PlayerRateAndLine.i 误判为“已在当前清晰度”而跳过切换
                                try {
                                    Object config = XposedHelpers.callMethod(neuron, "VE");
                                    int bestInt = Integer.parseInt(finalBestRate);
                                    if (config != null && (int) XposedHelpers.callMethod(config, "r") == bestInt) {
                                        int fallbackRate = -1;
                                        if (!TextUtils.isEmpty(finalCurrentStreamRate)) {
                                            try {
                                                int parsed = Integer.parseInt(finalCurrentStreamRate);
                                                if (parsed != bestInt) {
                                                    fallbackRate = parsed;
                                                }
                                            } catch (Throwable ignored) {}
                                        }
                                        XposedHelpers.callMethod(config, "v0", fallbackRate);
                                    }
                                } catch (Throwable ignored) {}

                                // 2. 调用官方切画质；若同时需切换线路，PlayerRateAndLine.i 会消费 sPendingOverrideCdn 合并切流
                                XposedHelpers.callMethod(neuron, "aF", finalTargetRateBean);

                                // 兜底：若 aF 因 auto/equalRate 提前返回或未触发 PlayerRateAndLine.i，则单独触发切线路
                                if (runLine && sPendingOverrideCdn != null) {
                                    sPendingOverrideCdn = null;
                                    XposedHelpers.callMethod(neuron, "ZE", finalTargetLineBean);
                                }
                            } else {
                                XposedHelpers.callMethod(neuron, "ZE", finalTargetLineBean);
                            }
                        } finally {
                            sIsAutoSwitching = false;
                            sPendingOverrideCdn = null;
                        }

                        if (runQuality) {
                            // 3. 刷新入口文字
                            try {
                                Object freshFunc = XposedHelpers.getObjectField(neuron, "freshEntrance");
                                if (freshFunc != null) {
                                    XposedHelpers.callMethod(freshFunc, "invoke");
                                }
                            } catch (Throwable ignored) {}
                        }

                        boolean lineActuallyDispatched = runLine && sSwitchDispatchedInPlayer;
                        if (runLine && !sSwitchDispatchedInPlayer) {
                            // 播放器 Presenter 可能尚未就绪，取消标记以便 onVideoPrepared 重试一次
                            unmarkLineHandledIfRoom(neuron, roomId);
                            DouyuLog.d(TAG, "Line switch deferred: player presenter not ready yet via " + source);
                        }

                        String lineDisplay = !TextUtils.isEmpty(finalTargetLineName) ? finalTargetLineName : finalTargetLineCdn;
                        if (runQuality && lineActuallyDispatched) {
                            Log.i(TAG, "Auto-switched quality=" + finalBestName + " (rate=" + finalBestRate
                                    + ") and line=" + lineDisplay + " (cdn=" + finalTargetLineCdn + ") via " + source);
                            if (finalAct != null) {
                                Toast.makeText(finalAct,
                                        "斗鱼Plus: 已自动切换至最高画质 (" + finalBestName + ") 与备用线路 (" + lineDisplay + ")",
                                        Toast.LENGTH_SHORT).show();
                            }
                        } else if (runQuality) {
                            Log.i(TAG, "Successfully auto-switched to highest quality: " + finalBestName + " (rate=" + finalBestRate + ") via " + source);
                            if (finalAct != null) {
                                Toast.makeText(finalAct, "斗鱼Plus: 已自动切换至最高画质 (" + finalBestName + ")", Toast.LENGTH_SHORT).show();
                            }
                        } else if (lineActuallyDispatched) {
                            Log.i(TAG, "Successfully auto-switched to alternate line: " + lineDisplay + " (cdn=" + finalTargetLineCdn + ") via " + source);
                            if (finalAct != null) {
                                Toast.makeText(finalAct, "斗鱼Plus: 已自动切换至备用线路 (" + lineDisplay + ")", Toast.LENGTH_SHORT).show();
                            }
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "Failed to switch quality/line in runnable", t);
                    }
                }
            });

        } catch (Throwable t) {
            Log.w(TAG, "triggerAutoQuality error", t);
        }
    }

    private static String resolveRoomId(Object neuron, Object roomRtmpInfo, ClassLoader classLoader) {
        String roomId = null;
        if (roomRtmpInfo != null) {
            try {
                roomId = (String) XposedHelpers.getObjectField(roomRtmpInfo, "roomId");
            } catch (Throwable ignored) {}
        }
        if (TextUtils.isEmpty(roomId)) {
            roomId = getRoomIdFromNeuronOrGlobal(neuron, classLoader);
        }
        return roomId;
    }

    private static boolean isAudioOrVideoOrPaidRoom(Object neuron, Object roomRtmpInfo) {
        try {
            if ((boolean) XposedHelpers.callMethod(neuron, "z")) {
                return true;
            }
        } catch (Throwable ignored) {}
        try {
            if (XposedHelpers.getBooleanField(neuron, "isVideoMode")) {
                return true;
            }
        } catch (Throwable ignored) {}
        if (getIntFieldSafe(roomRtmpInfo, "paymentMode", -1) != -1) {
            return true;
        }
        return false;
    }

    /**
     * 策略 A：从 RoomRtmpInfo.lineBeans (cdnsWithName) 中过滤空 cdn、当前 rtmp_cdn、本房首次默认主线 excludedMainCdn 与所有 RTC 线路，
     * 按权重 LineBean.d (re_weight) 降序稳定排序后取第一条备用线路。
     */
    private static Object findAlternateLineBeanStrategyA(Object roomRtmpInfo, String excludedMainCdn) {
        if (roomRtmpInfo == null) return null;

        String currentCdn = null;
        List<?> lineBeans = null;
        try {
            currentCdn = (String) XposedHelpers.getObjectField(roomRtmpInfo, "rtmp_cdn");
            lineBeans = (List<?>) XposedHelpers.getObjectField(roomRtmpInfo, "lineBeans");
        } catch (Throwable ignored) {}

        if (TextUtils.isEmpty(currentCdn) || lineBeans == null || lineBeans.isEmpty()) {
            return null;
        }

        List<Object> candidates = new ArrayList<>();
        for (Object bean : lineBeans) {
            if (bean == null) continue;
            String cdn = null;
            try {
                cdn = (String) XposedHelpers.getObjectField(bean, "c");
            } catch (Throwable ignored) {}
            if (TextUtils.isEmpty(cdn) || cdn.equals(currentCdn)) {
                continue;
            }
            if (!TextUtils.isEmpty(excludedMainCdn) && cdn.equals(excludedMainCdn)) {
                continue;
            }
            if (isRtcLineBean(bean, cdn)) {
                continue;
            }
            candidates.add(bean);
        }

        if (candidates.isEmpty()) {
            return null;
        }

        Collections.sort(candidates, new Comparator<Object>() {
            @Override
            public int compare(Object o1, Object o2) {
                int w1 = getIntFieldSafe(o1, "d", 0);
                int w2 = getIntFieldSafe(o2, "d", 0);
                return Integer.compare(w2, w1);
            }
        });
        return candidates.get(0);
    }

    private static void logRtmpInfoDebug(Object rtmpInfo, String source) {
        if (rtmpInfo == null) return;
        try {
            String roomId = String.valueOf(XposedHelpers.getObjectField(rtmpInfo, "roomId"));
            String cdn = String.valueOf(XposedHelpers.getObjectField(rtmpInfo, "rtmp_cdn"));
            String rate = String.valueOf(XposedHelpers.getObjectField(rtmpInfo, "rate"));
            String rtmpUrl = String.valueOf(XposedHelpers.getObjectField(rtmpInfo, "rtmpUrl"));
            String rtmpLive = String.valueOf(XposedHelpers.getObjectField(rtmpInfo, "rtmpLive"));
            String p2p = String.valueOf(XposedHelpers.getObjectField(rtmpInfo, "p2p"));
            String p2pCid = String.valueOf(XposedHelpers.getObjectField(rtmpInfo, "p2pCid"));
            String rtcUrl = String.valueOf(XposedHelpers.getObjectField(rtmpInfo, "rtcStreamUrl"));
            String mixedUrl = String.valueOf(XposedHelpers.getObjectField(rtmpInfo, "mixedUrl"));
            StringBuilder lb = new StringBuilder("[");
            List<?> lineBeans = (List<?>) XposedHelpers.getObjectField(rtmpInfo, "lineBeans");
            if (lineBeans != null) {
                for (int i = 0; i < lineBeans.size(); i++) {
                    Object b = lineBeans.get(i);
                    if (i > 0) lb.append(", ");
                    if (b == null) {
                        lb.append("null");
                    } else {
                        int lineType = getIntFieldSafe(b, "a", -1);
                        String bName = String.valueOf(XposedHelpers.getObjectField(b, "b"));
                        String bCdn = String.valueOf(XposedHelpers.getObjectField(b, "c"));
                        int weight = getIntFieldSafe(b, "d", -1);
                        boolean rtc = isRtcLineBean(b, bCdn);
                        lb.append("{idx=").append(i)
                                .append(",a=").append(lineType)
                                .append(",b=").append(bName)
                                .append(",c=").append(bCdn)
                                .append(",d=").append(weight)
                                .append(",rtc=").append(rtc)
                                .append("}");
                    }
                }
            }
            lb.append("]");
            Log.d(TAG, "[DEBUG " + source + "] roomId=" + roomId
                    + ", rtmp_cdn=" + cdn
                    + ", rate=" + rate
                    + ", p2p=" + p2p
                    + ", p2pCid=" + p2pCid
                    + ", rtcUrl=" + rtcUrl
                    + ", mixedUrl=" + mixedUrl
                    + ", rtmpUrl=" + rtmpUrl
                    + ", rtmpLive=" + rtmpLive
                    + ", lineBeans=" + lb);
        } catch (Throwable t) {
            Log.w(TAG, "[DEBUG " + source + "] dump RoomRtmpInfo failed", t);
        }
    }

    private static boolean isRtcLineBean(Object bean, String cdn) {
        try {
            if ((boolean) XposedHelpers.callMethod(bean, "a")) {
                return true;
            }
        } catch (Throwable ignored) {}
        try {
            if ((boolean) XposedHelpers.callMethod(bean, "b")) {
                return true;
            }
        } catch (Throwable ignored) {}
        return "rtc".equalsIgnoreCase(cdn) || cdn.toLowerCase(Locale.ROOT).endsWith("-rtc");
    }

    private static int getIntFieldSafe(Object target, String fieldName, int defaultVal) {
        if (target == null) return defaultVal;
        try {
            return XposedHelpers.getIntField(target, fieldName);
        } catch (Throwable ignored) {
            return defaultVal;
        }
    }

    private static boolean getBooleanFieldSafe(Object target, String fieldName, boolean defaultVal) {
        if (target == null) return defaultVal;
        try {
            return XposedHelpers.getBooleanField(target, fieldName);
        } catch (Throwable ignored) {
            return defaultVal;
        }
    }

    private static String getStringFieldSafe(Object target, String fieldName) {
        if (target == null) return null;
        try {
            Object val = XposedHelpers.getObjectField(target, fieldName);
            return val instanceof String ? (String) val : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Context getContextFromPresenter(Object presenter) {
        if (presenter == null) return null;
        try {
            Object a0 = XposedHelpers.getObjectField(presenter, "a0");
            if (a0 instanceof Context) {
                return (Context) a0;
            }
        } catch (Throwable ignored) {}
        try {
            Object sw = XposedHelpers.callMethod(presenter, "sw");
            if (sw instanceof Context) {
                return (Context) sw;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean isAudioOrMixedOrPaidBeforeOpen(Object presenter, Object roomRtmpInfo) {
        try {
            if ((boolean) XposedHelpers.callMethod(presenter, "z")) {
                return true;
            }
        } catch (Throwable ignored) {}
        try {
            Object r1 = XposedHelpers.getObjectField(presenter, "r1");
            if (r1 != null && (boolean) XposedHelpers.callMethod(r1, "h")) {
                return true;
            }
        } catch (Throwable ignored) {}
        if (!TextUtils.isEmpty(getStringFieldSafe(presenter, "S"))) {
            return true;
        }
        if (getIntFieldSafe(roomRtmpInfo, "paymentMode", -1) != -1) {
            return true;
        }
        return !TextUtils.isEmpty(getStringFieldSafe(roomRtmpInfo, "mixedUrl"));
    }

    private static Object resolveDouyuConfig(Object presenter, ClassLoader classLoader) {
        if (presenter != null) {
            try {
                Object config = XposedHelpers.callMethod(presenter, "By");
                if (config != null) {
                    return config;
                }
            } catch (Throwable ignored) {}
        }
        ClassLoader loader = classLoader != null ? classLoader : sClassLoader;
        if (loader == null) {
            return null;
        }
        try {
            Class<?> cfgCls = XposedHelpers.findClassIfExists(
                    "com.douyu.sdk.playerframework.framework.config.Config",
                    loader
            );
            if (cfgCls == null) {
                return null;
            }
            Context ctx = getContextFromPresenter(presenter);
            if (ctx != null) {
                Object cfg = XposedHelpers.callStaticMethod(cfgCls, "g", ctx);
                if (cfg != null) {
                    return cfg;
                }
            }
            return XposedHelpers.getStaticObjectField(cfgCls, "R");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean applyPreOpenCdnConfig(Object presenter, String targetCdn, ClassLoader classLoader) {
        try {
            Object config = resolveDouyuConfig(presenter, classLoader);
            if (config == null) {
                return false;
            }
            XposedHelpers.callMethod(config, "l0", targetCdn);
            sModuleOverrodeGlobalCdn = true;
            try {
                XposedHelpers.callMethod(config, "T");
            } catch (Throwable ignored) {}

            ClassLoader loaderClsLoader = classLoader != null ? classLoader : sClassLoader;
            if (loaderClsLoader != null) {
                Class<?> loaderCls = XposedHelpers.findClassIfExists(
                        "com.douyu.sdk.playerframework.business.live.liveuser.rtmp.DYRtmpPlayerLoader",
                        loaderClsLoader
                );
                if (loaderCls != null) {
                    Object loader = XposedHelpers.callStaticMethod(loaderCls, "k");
                    if (loader != null) {
                        XposedHelpers.setBooleanField(loader, "i", false);
                    }
                }
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "applyPreOpenCdnConfig failed for cdn=" + targetCdn, t);
            return false;
        }
    }

    private static void clearGlobalCdnConfigIfNeeded(Object presenter, Context ctx, ClassLoader classLoader) {
        boolean enabled = DouyuPlusConfig.isAutoSwitchLineEnabled(ctx);
        if (!enabled && !sModuleOverrodeGlobalCdn) {
            return;
        }
        Object config = resolveDouyuConfig(presenter, classLoader);
        if (config == null) {
            sModuleOverrodeGlobalCdn = false;
            return;
        }
        try {
            String currentCfgCdn = (String) XposedHelpers.callMethod(config, "k");
            if (!TextUtils.isEmpty(currentCfgCdn)) {
                XposedHelpers.callMethod(config, "l0", "");
                try {
                    XposedHelpers.callMethod(config, "T");
                } catch (Throwable ignored) {}
                if (DouyuPlusConfig.isDebugModeEnabled()) {
                    Log.d(TAG, "Cleared global Config rtmp_cdn (was=" + currentCfgCdn + ") for fresh default line");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "clearGlobalCdnConfigIfNeeded failed", t);
        } finally {
            sModuleOverrodeGlobalCdn = false;
        }
    }

    private static String recordOrGetPresenterDefaultMainCdn(Object presenter, String roomId, String observedCdn) {
        if (presenter == null || TextUtils.isEmpty(roomId)) {
            return observedCdn;
        }
        String recorded;
        synchronized (sDefaultMainLineByPresenter) {
            DefaultLineRecord existing = sDefaultMainLineByPresenter.get(presenter);
            if (existing != null && TextUtils.equals(roomId, existing.roomId) && !TextUtils.isEmpty(existing.cdn)) {
                recorded = existing.cdn;
            } else {
                recorded = observedCdn;
                if (!TextUtils.isEmpty(recorded)) {
                    sDefaultMainLineByPresenter.put(presenter, new DefaultLineRecord(roomId, recorded));
                }
            }
        }
        if (getBooleanFieldSafe(presenter, "Z", true)) {
            recordActiveDefaultMainCdn(roomId, recorded);
        }
        return recorded;
    }

    private static void recordActiveDefaultMainCdn(String roomId, String defaultMainCdn) {
        if (TextUtils.isEmpty(roomId) || TextUtils.isEmpty(defaultMainCdn)) {
            return;
        }
        if (!TextUtils.equals(roomId, sActiveDefaultMainRoomId) || TextUtils.isEmpty(sActiveDefaultMainCdn)) {
            sActiveDefaultMainRoomId = roomId;
            sActiveDefaultMainCdn = defaultMainCdn;
        }
    }

    private static String getActiveDefaultMainCdnForRoom(String roomId, String fallbackCdn) {
        if (!TextUtils.isEmpty(roomId)
                && TextUtils.equals(roomId, sActiveDefaultMainRoomId)
                && !TextUtils.isEmpty(sActiveDefaultMainCdn)) {
            return sActiveDefaultMainCdn;
        }
        return fallbackCdn;
    }

    private static void clearActiveDefaultMainLine(String targetRoomId, boolean clearIfMatches) {
        if (sActiveDefaultMainRoomId == null && sActiveDefaultMainCdn == null) {
            return;
        }
        if (targetRoomId == null) {
            sActiveDefaultMainRoomId = null;
            sActiveDefaultMainCdn = null;
            return;
        }
        boolean matches = TextUtils.equals(targetRoomId, sActiveDefaultMainRoomId);
        if (clearIfMatches == matches) {
            sActiveDefaultMainRoomId = null;
            sActiveDefaultMainCdn = null;
        }
    }

    private static void clearPresenterDefaultMainLine(Object presenter, String newRoomIdIfChanging) {
        if (presenter == null) return;
        synchronized (sDefaultMainLineByPresenter) {
            if (newRoomIdIfChanging == null) {
                sDefaultMainLineByPresenter.remove(presenter);
            } else {
                DefaultLineRecord rec = sDefaultMainLineByPresenter.get(presenter);
                if (rec != null && !TextUtils.equals(newRoomIdIfChanging, rec.roomId)) {
                    sDefaultMainLineByPresenter.remove(presenter);
                }
            }
        }
    }

    private static volatile java.lang.ref.WeakReference<Object> sLastPreOpenPresenterRef = null;

    private static void markPreOpenLineHandled(Object presenter, String roomId) {
        if (presenter == null || TextUtils.isEmpty(roomId)) return;
        synchronized (sLineHandledByPresenter) {
            sLineHandledByPresenter.put(presenter, roomId);
        }
        sLastPreOpenPresenterRef = new java.lang.ref.WeakReference<>(presenter);
        sLineHandledGlobalRoomId = roomId;
    }

    private static boolean isPreOpenLineHandled(Object presenter, String roomId) {
        if (presenter == null || TextUtils.isEmpty(roomId)) return false;
        synchronized (sLineHandledByPresenter) {
            return TextUtils.equals(roomId, sLineHandledByPresenter.get(presenter));
        }
    }

    private static void handlePresenterRoomStartOrChange(Object presenter, String newRoomId, boolean forceClearGlobalCdn, ClassLoader classLoader) {
        if (sIsPreOpenReloading || presenter == null) {
            return;
        }
        if (!TextUtils.isEmpty(getStringFieldSafe(presenter, "S"))) {
            return;
        }
        String oldRoomId = getStringFieldSafe(presenter, "X");
        boolean isFreshEntry = TextUtils.isEmpty(oldRoomId);
        boolean isRoomChanged = !isFreshEntry && !TextUtils.equals(oldRoomId, newRoomId);
        boolean isActivePresenter = getBooleanFieldSafe(presenter, "Z", true);

        if ((forceClearGlobalCdn || isFreshEntry || isRoomChanged)
                && (isActivePresenter || TextUtils.isEmpty(sLineReloadInFlightRoomId))) {
            clearGlobalCdnConfigIfNeeded(presenter, getContextFromPresenter(presenter), classLoader);
        }

        if (isRoomChanged) {
            clearPreOpenLineStateIfRoomChanged(presenter, newRoomId);
            clearPresenterDefaultMainLine(presenter, newRoomId);
            if (isActivePresenter && !TextUtils.isEmpty(newRoomId)) {
                clearActiveDefaultMainLine(newRoomId, false);
            } else if (isActivePresenter) {
                clearActiveDefaultMainLine(null, true);
            }
            resetManualAndSwitchedIfRoomChanged(newRoomId, false);
        } else if (isFreshEntry && isActivePresenter && !TextUtils.isEmpty(newRoomId)) {
            // 新 Presenter 首次进入（含同房间退出重进）：清理退房可能遗留的全局线路状态，保证默认主线重新识别
            clearGlobalPreOpenTracking(newRoomId, true);
            clearActiveDefaultMainLine(newRoomId, true);
        }
    }

    private static void handlePresenterPreloadRoom(Object presenter, String newRoomId, ClassLoader classLoader) {
        if (sIsPreOpenReloading || presenter == null) {
            return;
        }
        if (getBooleanFieldSafe(presenter, "Z", false)) {
            handlePresenterRoomStartOrChange(presenter, newRoomId, false, classLoader);
            return;
        }
        String oldRoomId = getStringFieldSafe(presenter, "X");
        boolean isFreshEntry = TextUtils.isEmpty(oldRoomId);
        boolean isRoomChanged = !isFreshEntry && !TextUtils.equals(oldRoomId, newRoomId);

        if ((isFreshEntry || isRoomChanged) && TextUtils.isEmpty(sLineReloadInFlightRoomId)) {
            clearGlobalCdnConfigIfNeeded(presenter, getContextFromPresenter(presenter), classLoader);
        }
        if (isRoomChanged) {
            clearPreOpenLineStateIfRoomChanged(presenter, newRoomId);
            clearPresenterDefaultMainLine(presenter, newRoomId);
        }
    }

    private static void handlePresenterRoomExitOrRelease(Object presenter, boolean respectFloatFlag, ClassLoader classLoader) {
        if (sIsPreOpenReloading || presenter == null) {
            return;
        }
        if (respectFloatFlag && getBooleanFieldSafe(presenter, "F", false)) {
            return;
        }
        boolean isActivePresenter = getBooleanFieldSafe(presenter, "Z", true);
        String presenterRoomId = getStringFieldSafe(presenter, "X");
        String removedRoomId = clearPreOpenLineStateForPresenter(presenter);
        String targetRoomId = !TextUtils.isEmpty(removedRoomId) ? removedRoomId : presenterRoomId;
        clearPresenterDefaultMainLine(presenter, null);

        if (isActivePresenter) {
            if (!TextUtils.isEmpty(targetRoomId)) {
                clearGlobalPreOpenTracking(targetRoomId, true);
                clearActiveDefaultMainLine(targetRoomId, true);
            } else if (sLastPreOpenPresenterRef == null
                    || sLastPreOpenPresenterRef.get() == null
                    || sLastPreOpenPresenterRef.get() == presenter) {
                clearGlobalPreOpenTracking(sLineHandledGlobalRoomId, true);
                clearActiveDefaultMainLine(sActiveDefaultMainRoomId, true);
            }
            clearGlobalCdnConfigIfNeeded(presenter, getContextFromPresenter(presenter), classLoader);
        }
    }

    private static String clearPreOpenLineStateForPresenter(Object presenter) {
        if (presenter == null) return null;
        String prevRoomId;
        synchronized (sLineHandledByPresenter) {
            prevRoomId = sLineHandledByPresenter.remove(presenter);
        }
        boolean isCurrentPresenter = sLastPreOpenPresenterRef == null
                || sLastPreOpenPresenterRef.get() == null
                || sLastPreOpenPresenterRef.get() == presenter;
        if (isCurrentPresenter && prevRoomId != null) {
            clearGlobalPreOpenTracking(prevRoomId, true);
        }
        return prevRoomId;
    }

    private static void clearGlobalPreOpenTracking(String roomId, boolean clearIfMatches) {
        if (TextUtils.isEmpty(roomId)) return;
        if (sLineHandledGlobalRoomId != null && (clearIfMatches == TextUtils.equals(roomId, sLineHandledGlobalRoomId))) {
            sLineHandledGlobalRoomId = null;
        }
        if (sLineReloadInFlightRoomId != null && (clearIfMatches == TextUtils.equals(roomId, sLineReloadInFlightRoomId))) {
            sLineReloadInFlightRoomId = null;
        }
        if (sPendingPreOpenToastRoomId != null && (clearIfMatches == TextUtils.equals(roomId, sPendingPreOpenToastRoomId))) {
            sPendingPreOpenToastRoomId = null;
            sPendingPreOpenLineDisplay = null;
        }
    }

    private static void clearPreOpenLineStateIfRoomChanged(Object presenter, String newRoomId) {
        if (presenter == null || TextUtils.isEmpty(newRoomId)) return;
        String prev;
        synchronized (sLineHandledByPresenter) {
            prev = sLineHandledByPresenter.get(presenter);
            if (prev != null && !TextUtils.equals(newRoomId, prev)) {
                sLineHandledByPresenter.remove(presenter);
            }
        }
        clearGlobalPreOpenIfRoomChanged(newRoomId);
    }

    private static void resetManualAndSwitchedIfRoomChanged(String newRoomId, boolean logReset) {
        if (TextUtils.isEmpty(newRoomId)) return;
        if (!TextUtils.equals(newRoomId, sLastSwitchedRoomId)) {
            sLastSwitchedRoomId = null;
            sLastSwitchedRate = null;
        }
        if (!TextUtils.equals(newRoomId, sUserManualRoomId)) {
            sHasUserManualSwitched = false;
            sUserManualRoomId = null;
            sUserManualRate = null;
            if (logReset) {
                DouyuLog.d(TAG, "Reset user manual quality state for new room=" + newRoomId);
            }
        }
    }

    private static void resetManualAndSwitchedIfRoomClosed(String closedRoomId) {
        if (TextUtils.isEmpty(closedRoomId)) return;
        if (TextUtils.equals(closedRoomId, sLastSwitchedRoomId)) {
            sLastSwitchedRoomId = null;
            sLastSwitchedRate = null;
        }
        if (TextUtils.equals(closedRoomId, sUserManualRoomId)) {
            sHasUserManualSwitched = false;
            sUserManualRoomId = null;
            sUserManualRate = null;
        }
    }

    private static void clearGlobalPreOpenIfRoomChanged(String newRoomId) {
        if (TextUtils.isEmpty(newRoomId)) return;
        clearGlobalPreOpenTracking(newRoomId, false);
        clearActiveDefaultMainLine(newRoomId, false);
    }

    private static void markLineHandled(Object neuron, String roomId) {
        if (neuron == null || TextUtils.isEmpty(roomId)) return;
        synchronized (sLineHandledByNeuron) {
            sLineHandledByNeuron.put(neuron, roomId);
        }
        sLineHandledGlobalRoomId = roomId;
    }

    private static boolean isLineHandled(Object neuron, String roomId) {
        if (neuron == null || TextUtils.isEmpty(roomId)) return true;
        synchronized (sLineHandledByNeuron) {
            if (TextUtils.equals(roomId, sLineHandledByNeuron.get(neuron))) {
                return true;
            }
        }
        return TextUtils.equals(roomId, sLineHandledGlobalRoomId);
    }

    private static void rollbackQueuedSwitchTracking(Object neuron, String roomId, boolean rollbackQuality, boolean rollbackLine) {
        if (rollbackQuality && TextUtils.equals(roomId, sLastSwitchedRoomId)) {
            sLastSwitchedRoomId = null;
            sLastSwitchedRate = null;
        }
        if (rollbackLine) {
            unmarkLineHandledIfRoom(neuron, roomId);
        }
    }

    private static void unmarkLineHandledIfRoom(Object neuron, String roomId) {
        if (neuron == null) return;
        synchronized (sLineHandledByNeuron) {
            if (roomId == null || TextUtils.equals(roomId, sLineHandledByNeuron.get(neuron))) {
                sLineHandledByNeuron.remove(neuron);
            }
        }
        if (!TextUtils.isEmpty(roomId) && TextUtils.equals(roomId, sLineHandledGlobalRoomId)) {
            boolean handledByPresenter;
            synchronized (sLineHandledByPresenter) {
                handledByPresenter = sLineHandledByPresenter.containsValue(roomId);
            }
            if (!handledByPresenter) {
                sLineHandledGlobalRoomId = null;
            }
        }
    }

    private static void markUserManualLine(Object neuron, String roomId) {
        if (neuron == null || TextUtils.isEmpty(roomId)) return;
        synchronized (sUserManualLineByNeuron) {
            sUserManualLineByNeuron.put(neuron, roomId);
        }
    }

    private static boolean isUserManualLine(Object neuron, String roomId) {
        if (neuron == null || TextUtils.isEmpty(roomId)) return false;
        synchronized (sUserManualLineByNeuron) {
            return TextUtils.equals(roomId, sUserManualLineByNeuron.get(neuron));
        }
    }

    private static void clearLineStateForNeuron(Object neuron) {
        if (neuron == null) return;
        synchronized (sLineHandledByNeuron) {
            sLineHandledByNeuron.remove(neuron);
        }
        synchronized (sUserManualLineByNeuron) {
            sUserManualLineByNeuron.remove(neuron);
        }
    }

    private static void clearLineStateIfRoomChanged(Object neuron, String newRoomId) {
        if (neuron == null || TextUtils.isEmpty(newRoomId)) return;
        synchronized (sLineHandledByNeuron) {
            String prev = sLineHandledByNeuron.get(neuron);
            if (prev != null && !TextUtils.equals(newRoomId, prev)) {
                sLineHandledByNeuron.remove(neuron);
            }
        }
        synchronized (sUserManualLineByNeuron) {
            String prevManual = sUserManualLineByNeuron.get(neuron);
            if (prevManual != null && !TextUtils.equals(newRoomId, prevManual)) {
                sUserManualLineByNeuron.remove(neuron);
            }
        }
    }

    private static volatile Class<?> sUserBoxCls;

    private static boolean isUserLoggedIn(ClassLoader classLoader) {
        try {
            Class<?> userBoxCls = sUserBoxCls;
            if (userBoxCls == null) {
                ClassLoader loader = classLoader != null ? classLoader : sClassLoader;
                if (loader != null) {
                    userBoxCls = XposedHelpers.findClassIfExists("com.douyu.module.base.user.UserBox", loader);
                    if (userBoxCls != null) {
                        sUserBoxCls = userBoxCls;
                    }
                }
            }
            if (userBoxCls != null) {
                Object userBox = XposedHelpers.callStaticMethod(userBoxCls, "b");
                if (userBox != null) {
                    return (boolean) XposedHelpers.callMethod(userBox, "isLogin");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "isUserLoggedIn check failed", t);
        }
        return true;
    }

    private static boolean hasPlayableNonHighBitRate(List<?> rateList) {
        for (Object item : rateList) {
            if (item == null) continue;
            try {
                boolean isAuto = false;
                try {
                    isAuto = (boolean) XposedHelpers.callMethod(item, "isAutoRate");
                } catch (Throwable ignored) {}
                if (isAuto) continue;

                boolean isHighBit = false;
                try {
                    isHighBit = (boolean) XposedHelpers.callMethod(item, "isHighBitRate");
                } catch (Throwable ignored) {}
                if (!isHighBit) {
                    return true;
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private static Object findHighestQualityBean(List<?> rateList, boolean isLoggedIn) {
        if (rateList == null || rateList.isEmpty()) return null;
        boolean hasNonHighBitRate = !isLoggedIn && hasPlayableNonHighBitRate(rateList);
        Object best = null;
        int maxScore = -99999;
        for (Object item : rateList) {
            if (item == null) continue;
            try {
                // 排除自适应画质 (-1)
                boolean isAuto = false;
                try {
                    isAuto = (boolean) XposedHelpers.callMethod(item, "isAutoRate");
                } catch (Throwable ignored) {}
                if (isAuto) continue;

                // 对齐 ViewDefinitionAdapter 登录门槛：未登录且存在非高码率画质时，跳过需登录的高码率画质
                if (!isLoggedIn && hasNonHighBitRate) {
                    boolean isHighBit = false;
                    try {
                        isHighBit = (boolean) XposedHelpers.callMethod(item, "isHighBitRate");
                    } catch (Throwable ignored) {}
                    if (isHighBit) {
                        continue;
                    }
                }

                // 排除需要钻粉/付费的画质，避免切换后黑屏或弹窗
                // 注意：若为 2K/4K 官方赛事房间（如王者荣耀 998）或大主播（如玩机器），2K 档位常标粉丝/会员标签但实际开放，放行 2K/4K
                boolean needVip = false;
                try {
                    needVip = (boolean) XposedHelpers.callMethod(item, "isNeedVipFan");
                } catch (Throwable ignored) {}

                boolean is2kOr4k = false;
                try {
                    is2kOr4k = (boolean) XposedHelpers.callMethod(item, "is4KSuperHigh")
                            || (boolean) XposedHelpers.callMethod(item, "is2KSuperHigh");
                } catch (Throwable ignored) {}
                if (!is2kOr4k) {
                    String rate = (String) XposedHelpers.getObjectField(item, "rate");
                    String name = (String) XposedHelpers.getObjectField(item, "name");
                    String upperName = name != null ? name.toUpperCase() : "";
                    if ("15".equals(rate) || "16".equals(rate)
                            || upperName.contains("2K") || upperName.contains("4K")
                            || upperName.contains("1440") || upperName.contains("2160")) {
                        is2kOr4k = true;
                    }
                }

                if (needVip && !is2kOr4k) {
                    continue;
                }

                int score = calculateQualityScore(item);
                String rate = (String) XposedHelpers.getObjectField(item, "rate");
                String name = (String) XposedHelpers.getObjectField(item, "name");
                DouyuLog.d(TAG, "RateCandidate: name=" + name + ", rate=" + rate + ", score=" + score + ", is2kOr4k=" + is2kOr4k + ", needVip=" + needVip);
                if (score > maxScore) {
                    maxScore = score;
                    best = item;
                }
            } catch (Throwable ignored) {}
        }
        return best;
    }

    /**
     * 严密分层画质评分体系：
     * 梯队 1: 4K 档位 (50,000 基准分)
     * 梯队 2: 2K 档位 (40,000 基准分) - 保证王者荣耀赛事、玩机器等 2K 绝不被原画反超
     * 梯队 3: HDR 档位 (35,000 基准分)
     * 梯队 4: 原画 / 蓝光8M (30,000 基准分)
     * 梯队 5: 蓝光6M / 蓝光4M (20,000 基准分)
     * 梯队 6: 超清 / 1080P (10,000 基准分)
     * 梯队 7: 高清 / 720P (5,000 基准分)
     * 梯队 8: 流畅 / 540P (1,000 基准分)
     * 梯队内微调加分：60帧(+500分)、高码率(+200分)、bit码率微调(最多+100分)
     */
    private static int calculateQualityScore(Object bean) {
        if (bean == null) return -99999;

        String name = "";
        try {
            name = (String) XposedHelpers.getObjectField(bean, "name");
        } catch (Throwable ignored) {}
        if (name == null) name = "";
        String upperName = name.toUpperCase();

        String rateStr = "";
        try {
            rateStr = (String) XposedHelpers.getObjectField(bean, "rate");
        } catch (Throwable ignored) {}
        if (rateStr == null) rateStr = "";

        // 梯队 1: 4K 档位
        boolean is4k = false;
        try {
            is4k = (boolean) XposedHelpers.callMethod(bean, "is4KSuperHigh");
        } catch (Throwable ignored) {}
        if (is4k || upperName.contains("4K") || upperName.contains("2160") || "16".equals(rateStr)) {
            return 50000 + getBonusScore(bean, name);
        }

        // 梯队 2: 2K 档位
        boolean is2k = false;
        try {
            is2k = (boolean) XposedHelpers.callMethod(bean, "is2KSuperHigh");
        } catch (Throwable ignored) {}
        if (is2k || upperName.contains("2K") || upperName.contains("1440") || "15".equals(rateStr)) {
            return 40000 + getBonusScore(bean, name);
        }

        // 梯队 3: HDR 档位
        boolean isHdr = false;
        try {
            isHdr = (boolean) XposedHelpers.callMethod(bean, "isHdrRate");
        } catch (Throwable ignored) {}
        if (isHdr || upperName.contains("HDR") || "13".equals(rateStr)) {
            return 35000 + getBonusScore(bean, name);
        }

        // 梯队 4: 原画 / 蓝光8M / rate=0
        if (name.contains("原画") || "0".equals(rateStr) || name.contains("8M") || upperName.contains("8000")) {
            return 30000 + getBonusScore(bean, name);
        }

        // 梯队 5: 蓝光6M / 蓝光4M / 蓝光
        if (name.contains("6M") || name.contains("4M") || name.contains("蓝光")) {
            return 20000 + getBonusScore(bean, name);
        }

        // 梯队 6: 超清 / 1080P
        if (name.contains("超清") || name.contains("1080") || "2".equals(rateStr)) {
            return 10000 + getBonusScore(bean, name);
        }

        // 梯队 7: 高清 / 720P
        if (name.contains("高清") || name.contains("720") || "1".equals(rateStr)) {
            return 5000 + getBonusScore(bean, name);
        }

        // 梯队 8: 流畅 / 540P
        if (name.contains("流畅") || name.contains("540") || "3".equals(rateStr)) {
            return 1000 + getBonusScore(bean, name);
        }

        return 100 + getBonusScore(bean, name);
    }

    private static int getBonusScore(Object bean, String name) {
        int bonus = 0;
        if (name.contains("60")) {
            bonus += 500;
        }
        try {
            boolean isHighBit = (boolean) XposedHelpers.callMethod(bean, "isHighBitRate");
            if (isHighBit) bonus += 200;
        } catch (Throwable ignored) {}

        // 码率 bit 仅作为同梯队微调 (最大不超过 100 分，杜绝跨档位反超)
        try {
            String bit = (String) XposedHelpers.getObjectField(bean, "bit");
            if (!TextUtils.isEmpty(bit)) {
                int bitVal = Integer.parseInt(bit);
                if (bitVal > 0) {
                    bonus += Math.min(bitVal / 200, 100);
                }
            }
        } catch (Throwable ignored) {}

        return bonus;
    }

    public static boolean isWifiConnected(Context context) {
        if (context == null) return true;
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    android.net.Network activeNetwork = cm.getActiveNetwork();
                    if (activeNetwork != null) {
                        NetworkCapabilities caps = cm.getNetworkCapabilities(activeNetwork);
                        if (caps != null) {
                            return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
                        }
                    }
                }
                NetworkInfo info = cm.getActiveNetworkInfo();
                if (info != null && info.isConnected()) {
                    int type = info.getType();
                    return type == ConnectivityManager.TYPE_WIFI || type == ConnectivityManager.TYPE_ETHERNET;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "isWifiConnected check failed", t);
        }
        return false;
    }
}
