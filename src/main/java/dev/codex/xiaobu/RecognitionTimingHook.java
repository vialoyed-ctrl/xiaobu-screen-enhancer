package dev.codex.xiaobu;

import android.os.SystemClock;
import java.lang.reflect.Method;
import java.util.Set;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Optional device diagnostics. Never reads or logs recognition content or arguments. */
final class RecognitionTimingHook {
    private static final String PREFIX = "XiaobuPerf: ";
    private static final String START = "xiaobu.perf.start";

    static void install(ClassLoader loader) {
        // Read once per process, so normal recognition has no property/logging overhead.
        // Enable with: adb shell setprop debug.xiaobu.perf 1, then restart the host app.
        if (!enabled()) return;
        hook(loader, "com.coloros.colordirectservice.identify.entrance.DirectEntranceHandler", "o", "n");
        hook(loader, "com.coloros.directui.identifymusic.card.MusicCardTool", "j", "k");
        hook(loader, "com.coloros.colordirectservice.identify.FavoriteExtractManager", "d");
        hook(loader, "com.coloros.colordirectservice.util.UITrack$d", "c");
        hook(loader, "com.oplus.infocollection.floating.DirectCollectStarter", "c", "k", "l");
        hook(loader, "com.oplus.infocollection.floating.CollectAnimator", "t", "z", "w");
        hook(loader, "com.oplus.infocollection.collection.common.b", "c");
        hook(loader, "com.oplus.infocollection.collection.extract.PageInfoOcrSDK", "d", "e");
        hook(loader, "com.oplus.aiunit.ocr.client.OcrScheduleClient", "z", "s", "w");
        hook(loader, "com.oplus.aiunit.ocr.client.schedule.CloudResultObserver", "c");
        hook(loader, "com.oplus.aiunit.ocr.client.schedule.WrapOcrScheduleCallback", "onLocal", "onOcr", "onSchedule");
        hook(loader, "com.coloros.directui.ui.CollectionInfoFragment", "D");
        XposedBridge.log(PREFIX + "enabled; intervals cover synchronous calls only");
    }

    private static boolean enabled() {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            Method get = properties.getDeclaredMethod("getBoolean", String.class, boolean.class);
            return Boolean.TRUE.equals(get.invoke(null, "debug.xiaobu.perf", false));
        } catch (Throwable unavailable) {
            return false;
        }
    }

    private static void hook(ClassLoader loader, String className, String... methods) {
        final Class<?> type;
        try {
            type = Class.forName(className, false, loader);
        } catch (Throwable unavailable) {
            XposedBridge.log(PREFIX + "unavailable " + className);
            return;
        }
        for (String name : methods) {
            final String label = className + "." + name;
            try {
                Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(type, name, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam hook) {
                        long start = SystemClock.elapsedRealtimeNanos();
                        hook.setObjectExtra(START, start);
                        log("begin", label, start, 0L, false);
                    }

                    @Override protected void afterHookedMethod(MethodHookParam hook) {
                        Object value = hook.getObjectExtra(START);
                        if (!(value instanceof Long)) return;
                        long end = SystemClock.elapsedRealtimeNanos();
                        log("end", label, end, end - (Long) value, hook.hasThrowable());
                    }
                });
                if (hooks.isEmpty()) XposedBridge.log(PREFIX + "unavailable " + label);
            } catch (Throwable unavailable) {
                // Each optional timing point fails independently of the native pipeline.
                XposedBridge.log(PREFIX + "unavailable " + label);
            }
        }
    }

    private static void log(String phase, String label, long timeNanos, long durationNanos, boolean failed) {
        Thread thread = Thread.currentThread();
        XposedBridge.log(PREFIX + phase + " " + label
                + " atMs=" + timeNanos / 1000000L
                + " durationUs=" + durationNanos / 1000L
                + " thread=" + thread.getName() + "/" + thread.getId()
                + (failed ? " failed" : ""));
    }
}
