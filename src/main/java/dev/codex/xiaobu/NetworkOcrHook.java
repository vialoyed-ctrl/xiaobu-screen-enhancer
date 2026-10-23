package dev.codex.xiaobu;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Starts native local OCR without first waiting for the cloud response. */
final class NetworkOcrHook {
    static void install(ClassLoader loader) {
        try {
            String observer = "com.oplus.aiunit.ocr.client.schedule.CloudResultObserver";
            Class<?> type = Class.forName(observer, false, loader);
            if (type.getDeclaredMethod("c", long.class).getReturnType() != void.class
                    || type.getDeclaredField("a").getType() != java.util.concurrent.CountDownLatch.class) {
                throw new NoSuchMethodException("Unknown cloud wait implementation");
            }
            XposedHelpers.findAndHookMethod(observer, loader, "c", long.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    try {
                        if (StartupAnimationHook.nativeDesktopEntry()) return;
                        Class<?> manager = Class.forName("android.app.OplusActivityManager", false, loader);
                        Object top = manager.getMethod("getTopActivityComponentName").invoke(manager.getConstructor().newInstance());
                        if (top instanceof android.content.ComponentName
                                && "com.android.launcher".equals(((android.content.ComponentName) top).getPackageName())) {
                            XposedBridge.log("XiaobuLauncherHook: desktop retains native cloud/local OCR scheduling");
                            return;
                        }
                    } catch (Throwable unavailable) {
                        // Unknown foreground state retains the reliable native scheduler.
                        return;
                    }
                    // Native c() uses CountDownLatch.await(timeout, SECONDS).
                    // Zero means an immediate poll, so the existing local path starts next.
                    // The original cloud request, callbacks and local timeout remain intact.
                    long original = (Long) hook.args[0];
                    if (original > 0) {
                        hook.args[0] = 0L;
                        XposedBridge.log("XiaobuLauncherHook: cloud pre-wait " + original
                                + "s bypassed; native local OCR starts immediately");
                    }
                }
            });
            XposedBridge.log("XiaobuLauncherHook: nonblocking cloud OCR hook installed");
        } catch (Throwable error) {
            XposedBridge.log("XiaobuLauncherHook: native OCR scheduling retained: " + error);
        }
    }
}

