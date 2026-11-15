package io.github.ysl_ovo.douyuplus.hooks;

import android.util.Log;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import io.github.ysl_ovo.douyuplus.DouyuLog;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 需求 4: 性能深度优化与瘦身
 * 1. 禁用 Bugly 崩溃上报 SDK (大量后台扫描与异常轮询线程)
 * 2. 禁用 友盟 (Umeng) 统计分析 SDK (后台定时唤醒并频繁发送统计包)
 * 3. 禁用 腾讯 TuringFD (神盾风控，频繁读取应用列表与设备指纹)
 * 4. 禁用 斗鱼后台保活心跳推送服务 (DYPushKeepLiveServiceV1)
 */
public class PerformanceHook {
    private static final String TAG = "DouyuPlus_Performance";

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        List<String> summary = new ArrayList<>();

        // 1. 禁用 Bugly 初始化
        try {
            Class<?> buglyCls = XposedHelpers.findClass("com.tencent.bugly.library.Bugly", lpparam.classLoader);
            int count = 0;
            for (Method m : buglyCls.getDeclaredMethods()) {
                if (m.getName().equals("init")) {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(false));
                    count++;
                }
            }
            if (count > 0) summary.add("Bugly(" + count + ")");
        } catch (Throwable t) {
            DouyuLog.d(TAG, "Bugly not found or hook failed: " + t.getMessage());
        }

        // 2. 禁用 友盟 (Umeng) 统计分析 SDK
        try {
            Class<?> umengCls = XposedHelpers.findClass("com.umeng.commonsdk.UMConfigure", lpparam.classLoader);
            int count = 0;
            for (Method m : umengCls.getDeclaredMethods()) {
                if (m.getName().equals("init")) {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING);
                    count++;
                }
            }
            if (count > 0) summary.add("UMeng(" + count + ")");
        } catch (Throwable t) {
            DouyuLog.d(TAG, "Umeng not found or hook failed: " + t.getMessage());
        }

        // 3. 禁用 腾讯 TuringFD (神盾风控进程与应用列表扫描)
        try {
            Class<?> turingCoreCls = XposedHelpers.findClass("com.tencent.turingfd.sdk.ams.ga.Core", lpparam.classLoader);
            int count = 0;
            for (Method m : turingCoreCls.getDeclaredMethods()) {
                if (m.getName().equals("init") || m.getName().equals("start") || m.getName().equals("b")) {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING);
                    count++;
                }
            }
            if (count > 0) summary.add("TuringFD(" + count + ")");
        } catch (Throwable ignored) {}

        // 4. 拦截推送保活与守护服务 (DYPushKeepLiveServiceV1)
        try {
            Class<?> keepLiveServiceCls = XposedHelpers.findClass("com.douyu.module.push.service.DYPushKeepLiveServiceV1", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(keepLiveServiceCls, "onCreate", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    android.app.Service service = (android.app.Service) param.thisObject;
                    service.stopSelf();
                    param.setResult(null);
                    DouyuLog.d(TAG, "Stopped DYPushKeepLiveServiceV1 on creation");
                }
            });
            summary.add("PushKeepLive");
        } catch (Throwable ignored) {}

        // 5. 拦截神策数据 (SensorsData) 统计分析全埋点代理
        try {
            Class<?> sensorsCls = XposedHelpers.findClass("com.sensorsdata.analytics.android.sdk.SensorsDataAPI", lpparam.classLoader);
            for (Method m : sensorsCls.getDeclaredMethods()) {
                if (m.getName().equals("track") || m.getName().equals("trackViewScreen")) {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING);
                }
            }
            summary.add("SensorsData");
        } catch (Throwable ignored) {}

        Log.i(TAG, "Hooked Performance (" + String.join(", ", summary) + ")");
    }
}
