package io.github.ysl_ovo.douyuplus.hooks;

import android.text.BoringLayout;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.Log;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import io.github.ysl_ovo.douyuplus.DouyuLog;
import java.lang.reflect.Method;

/**
 * 修复斗鱼弹幕富文本排版偶发折行问题（长弹幕末尾字符连同角标被挤到第二行）。
 *
 * 原版 DefaultCacheStuffer.makeLayout() 直接将 Layout.getDesiredWidth() 强转为 (int) 截断，
 * 丢失 0.01~0.99px 小数部分且未校验 StaticLayout 实际测量偏差，导致容器外框略窄于内容总宽，
 * 进而触发 StaticLayout 自动折行。本 Hook 改用向上取整并保留安全边距，且在出现意外折行时
 * 通过探针测量真实单行宽度进行自校准，彻底杜绝意外换行。
 */
public class DanmuLayoutFixHook {
    private static final String TAG = "DouyuPlus_DanmuLayout";
    private static final int SAFETY_PADDING_PX = 2;
    private static final int MIN_PROBE_WIDTH_PX = 32768;

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> defaultCacheStufferCls = XposedHelpers.findClass(
                    "master.flame.danmaku.danmaku.model.android.DefaultCacheStuffer",
                    lpparam.classLoader
            );
            Class<?> baseDanmakuCls = XposedHelpers.findClass(
                    "master.flame.danmaku.danmaku.model.BaseDanmaku",
                    lpparam.classLoader
            );

            int hookedCount = 0;
            for (Method m : defaultCacheStufferCls.getDeclaredMethods()) {
                Class<?>[] params = m.getParameterTypes();
                if ("makeLayout".equals(m.getName())
                        && params.length == 2
                        && baseDanmakuCls.isAssignableFrom(params[0])
                        && TextPaint.class.isAssignableFrom(params[1])) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            Object danmaku = param.args[0];
                            TextPaint textPaint = (TextPaint) param.args[1];
                            if (danmaku == null || textPaint == null) {
                                return;
                            }
                            CharSequence text = (CharSequence) XposedHelpers.getObjectField(danmaku, "text");
                            if (text == null) {
                                return;
                            }
                            Layout fixedLayout = buildSingleLineSafeLayout(danmaku, text, textPaint);
                            XposedHelpers.setObjectField(danmaku, "mLayout", fixedLayout);
                            param.setResult(null);
                        }
                    });
                    hookedCount++;
                    break;
                }
            }

            if (hookedCount > 0) {
                Log.i(TAG, "Hooked DefaultCacheStuffer.makeLayout (" + hookedCount + ")");
            } else {
                Log.w(TAG, "DefaultCacheStuffer.makeLayout not found");
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook DefaultCacheStuffer.makeLayout", t);
        }
    }

    private static Layout buildSingleLineSafeLayout(Object danmaku, CharSequence text, TextPaint textPaint) {
        if (text.length() == 0) {
            return createStaticLayout(text, textPaint, 1);
        }

        boolean forceSingleLine = false;
        try {
            forceSingleLine = XposedHelpers.getBooleanField(danmaku, "mForceSingleLine");
        } catch (Throwable ignored) {}

        float desiredWidth = Layout.getDesiredWidth(text, textPaint);
        BoringLayout.Metrics metrics = forceSingleLine ? BoringLayout.isBoring(text, textPaint) : null;
        if (metrics != null) {
            int boringWidth = Math.max(1, (int) Math.ceil(Math.max(desiredWidth, metrics.width)) + SAFETY_PADDING_PX);
            return BoringLayout.make(
                    text,
                    textPaint,
                    boringWidth,
                    Layout.Alignment.ALIGN_NORMAL,
                    1.0f,
                    0.0f,
                    metrics,
                    true
            );
        }

        int expectedLines = countExplicitLines(text);
        int initialWidth = Math.max(1, (int) Math.ceil(desiredWidth) + SAFETY_PADDING_PX);
        StaticLayout layout = createStaticLayout(text, textPaint, initialWidth);
        if (layout.getLineCount() <= expectedLines) {
            return layout;
        }

        // 若因 StaticLayout 与 getDesiredWidth 亚像素/Span 测量偏差仍意外折行，用大宽度探针测量真实行宽并重建
        int probeWidth = Math.max(initialWidth * 2, MIN_PROBE_WIDTH_PX);
        StaticLayout probe = createStaticLayout(text, textPaint, probeWidth);
        if (probe.getLineCount() > expectedLines) {
            probeWidth = Math.max(probeWidth * 2, 131072);
            probe = createStaticLayout(text, textPaint, probeWidth);
        }

        float maxLineWidth = 0.0f;
        int lineCount = probe.getLineCount();
        for (int i = 0; i < lineCount; i++) {
            float lw = Math.max(probe.getLineWidth(i), probe.getLineRight(i) - probe.getLineLeft(i));
            if (lw > maxLineWidth) {
                maxLineWidth = lw;
            }
        }

        int calibratedWidth = Math.max(initialWidth, (int) Math.ceil(maxLineWidth) + SAFETY_PADDING_PX);
        StaticLayout calibrated = createStaticLayout(text, textPaint, calibratedWidth);
        if (calibrated.getLineCount() > expectedLines) {
            calibratedWidth += 16;
            calibrated = createStaticLayout(text, textPaint, calibratedWidth);
        }
        DouyuLog.d(TAG, "Calibrated danmaku layout width: desired=" + desiredWidth
                + ", initial=" + initialWidth + ", calibrated=" + calibratedWidth
                + ", lines=" + calibrated.getLineCount() + "/" + expectedLines);

        return calibrated;
    }

    private static StaticLayout createStaticLayout(CharSequence text, TextPaint textPaint, int width) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), textPaint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0.0f, 1.0f)
                .setIncludePad(true)
                .build();
    }

    private static int countExplicitLines(CharSequence text) {
        int lines = 1;
        int len = text.length();
        for (int i = 0; i < len; i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }
}
