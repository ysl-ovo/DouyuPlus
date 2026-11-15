package io.github.ysl_ovo.douyuplus;

import android.util.Log;

/**
 * 调试日志门面：仅在开启调试模式时输出细粒度 Log.d，避免常规运行时日志刷屏与缓存溢出。
 */
public final class DouyuLog {
    private DouyuLog() {}

    public static void d(String tag, String msg) {
        if (DouyuPlusConfig.isDebugModeEnabled()) {
            Log.d(tag, msg);
        }
    }
}
