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
            final java.lang.reflect.Field latchField = type.getDeclaredField("a");
            latchField.setAccessible(true);
            XposedHelpers.findAndHookMethod(observer, loader, "c", long.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    if ((Long) hook.args[0] <= 0L) return;
                    if (StartupAnimationHook.nativeDesktopEntry()) {
                        // Give an already returning cloud result a short head start, then
                        // continue the SDK's own local fallback instead of waiting six seconds.
                        java.util.concurrent.CountDownLatch latch = null;
                        try {
                            latch = (java.util.concurrent.CountDownLatch) latchField.get(hook.thisObject);
                            if (latch == null) {
                                latch = new java.util.concurrent.CountDownLatch(1);
                                latchField.set(hook.thisObject, latch);
                            }
                            latch.await(150L, java.util.concurrent.TimeUnit.MILLISECONDS);
                            hook.setResult(null);
                            XposedBridge.log("XiaobuLauncherHook: desktop cloud head start capped at 150ms");
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            hook.setResult(null);
                        } catch (Throwable unavailable) {
                            XposedBridge.log("XiaobuLauncherHook: native desktop OCR wait retained: " + unavailable);
                        } finally {
                            try { if (latch != null && latchField.get(hook.thisObject) == latch) latchField.set(hook.thisObject, null); }
                            catch (Throwable ignored) { }
                        }
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

