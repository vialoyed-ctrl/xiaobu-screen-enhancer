package dev.codex.xiaobu.gesture;

import android.content.Context;
import android.os.Binder;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Installs only in system_server. Ordinary application processes are untouched. */
public final class GestureSystemHook {
    private static final AtomicBoolean installed = new AtomicBoolean();
    private static final AtomicBoolean callbackFailureLogged = new AtomicBoolean();
    private static volatile LongPressBackend backend;

    private GestureSystemHook() { }

    public static void install(final ClassLoader loader) {
        if (!installed.compareAndSet(false, true)) return;
        try {
            final Class<?> wm = Class.forName("com.android.server.wm.WindowManagerService", false, loader);
            final Class<?> accessibility = Class.forName(
                    "com.android.server.accessibility.AccessibilityManagerService", false, loader);
            final Class<?> drag = Class.forName("com.android.server.wm.DragDropController", false, loader);
            final Field context = field(wm, "mContext");
            // Resolve the known event signature before registering any callback.
            accessibility.getDeclaredMethod("sendAccessibilityEvent", AccessibilityEvent.class, int.class);
            requireHooks(XposedBridge.hookAllMethods(accessibility, "sendAccessibilityEvent", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    try {
                        LongPressBackend current = backend;
                        if (current != null && hook.args.length >= 2
                                && hook.args[0] instanceof AccessibilityEvent) {
                            current.onAccessibilityEvent((AccessibilityEvent) hook.args[0],
                                    (Integer) hook.args[1], Binder.getCallingUid());
                        }
                    } catch (Throwable error) { callbackFailure(error); }
                }
            }), "accessibility events");
            requireHooks(XposedBridge.hookAllMethods(drag, "performDrag", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    // A drag attempt already belongs to the app. Be conservative even if
                    // WMS subsequently rejects the drag, and leave the original call alone.
                    try {
                        LongPressBackend current = backend;
                        if (current != null) current.cancelPending("native drag");
                    } catch (Throwable error) { callbackFailure(error); }
                }
            }), "system drag");
            requireHooks(XposedBridge.hookAllMethods(wm, "updateFocusedWindowLocked", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam hook) {
                    try {
                        LongPressBackend current = backend;
                        if (current != null) current.onFocusMayHaveChanged();
                    } catch (Throwable error) { callbackFailure(error); }
                }
            }), "window focus");
            requireHooks(XposedBridge.hookAllMethods(wm, "addWindow", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam hook) {
                    try {
                        LongPressBackend current = backend;
                        if (current == null || !(hook.getResult() instanceof Integer)
                                || ((Integer) hook.getResult()) < 0) return;
                        for (Object argument : hook.args) {
                            if (argument instanceof WindowManager.LayoutParams) {
                                current.onWindowAdded((WindowManager.LayoutParams) argument);
                                break;
                            }
                        }
                    } catch (Throwable error) { callbackFailure(error); }
                }
            }), "app popups");
            requireHooks(XposedBridge.hookAllMethods(wm, "systemReady", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam hook) {
                    try {
                        synchronized (GestureSystemHook.class) {
                            if (backend != null) return;
                            Context app = (Context) context.get(hook.thisObject);
                            if (app == null) throw new IllegalStateException("Missing WMS context");
                            LongPressBackend next = new LongPressBackend(app, hook.thisObject, loader);
                            backend = next;
                            next.start();
                        }
                    } catch (Throwable error) { log("backend unavailable; native input retained: " + error); }
                }
            }), "system ready");
            log("system callbacks installed; gesture remains off until enabled in module settings");
        } catch (Throwable error) {
            // Partial hooks only observe events. Without a backend they cannot change input.
            log("system gesture unavailable; native input retained: " + error);
        }
    }

    private static void requireHooks(Set<?> hooks, String name) {
        if (hooks == null || hooks.isEmpty()) throw new IllegalStateException("No hook matched " + name);
    }

    static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field result = current.getDeclaredField(name);
                result.setAccessible(true);
                return result;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    static Method method(Class<?> type, String name, Class<?>... parameters) throws NoSuchMethodException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Method result = current.getDeclaredMethod(name, parameters);
                result.setAccessible(true);
                return result;
            } catch (NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException(name);
    }

    static void log(String message) {
        XposedBridge.log("XiaobuLauncherHook: gesture " + message);
    }

    static void callbackFailure(Throwable error) {
        LongPressBackend current = backend;
        if (current != null) current.cancelPending("callback failure");
        if (callbackFailureLogged.compareAndSet(false, true)) log("callback isolated: " + error);
    }
}
