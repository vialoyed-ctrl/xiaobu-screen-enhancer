package dev.codex.xiaobu;

import android.content.Context;
import android.view.WindowManager;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Verified 17.7.8 entry contracts; the legacy installer remains available. */
final class ModernEntryHooks {
    static boolean install(ClassLoader loader) {
        final Class<?> entrance;
        try {
            entrance = Class.forName("com.coloros.colordirectservice.identify.entrance.c", false, loader);
            entrance.getDeclaredMethod("a", entrance, Context.class,
                    Class.forName("com.coloros.colordirectservice.identify.beans.DirectStartInfo", false, loader),
                    boolean.class, Class.forName("kotlin.coroutines.jvm.internal.ContinuationImpl", false, loader));
        } catch (ReflectiveOperationException legacy) { return false; }
        try (HookTransaction hooks = new HookTransaction()) {
            Class<?> start = Class.forName("com.coloros.colordirectservice.identify.beans.DirectStartInfo", false, loader);
            Class<?> continuation = Class.forName("kotlin.coroutines.jvm.internal.ContinuationImpl", false, loader);
            Method check = entrance.getDeclaredMethod("a", entrance, Context.class, start, boolean.class, continuation);
            Method launcher = Class.forName("xk0", false, loader).getDeclaredMethod("a");
            Class<?> windowHelper = Class.forName("z66", false, loader);
            Method analyze = windowHelper.getDeclaredMethod("a", Context.class);
            Class<?> manager = Class.forName("android.view.OplusWindowManager", false, loader);
            Method visible = manager.getDeclaredMethod("getVisibleWindowInfo", int.class);
            Field attributes = Class.forName("com.oplus.app.OplusWindowInfo", false, loader).getField("windowAttributes");
            if (!Modifier.isStatic(check.getModifiers()) || check.getReturnType() != Object.class
                    || !Modifier.isStatic(launcher.getModifiers()) || launcher.getReturnType() != boolean.class
                    || !Modifier.isStatic(analyze.getModifiers())
                    || !List.class.isAssignableFrom(visible.getReturnType())
                    || attributes.getType() != WindowManager.LayoutParams.class) {
                throw new NoSuchMethodException("Unknown modern entry contract");
            }
            hooks.add(XposedBridge.hookMethod(launcher, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) { hook.setResult(false); }
            }));
            hooks.add(XposedBridge.hookMethod(check, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    // Static R8 bridge includes the receiver as argument zero. Resumed
                    // suspend calls pass null start info and must finish normally.
                    if (hook.args[2] != null) hook.setResult(Boolean.TRUE);
                }
            }));
            ThreadLocal<List<Restore>> scope = new ThreadLocal<>();
            hooks.add(XposedBridge.hookMethod(analyze, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    hook.setObjectExtra("xiaobu.previousWindows", scope.get());
                    scope.set(new ArrayList<Restore>());
                }
                @Override protected void afterHookedMethod(MethodHookParam hook) throws Throwable {
                    List<Restore> changed = scope.get();
                    try {
                        if (changed != null) for (Restore entry : changed) attributes.set(entry.window, entry.original);
                    } finally {
                        @SuppressWarnings("unchecked") List<Restore> previous =
                                (List<Restore>) hook.getObjectExtra("xiaobu.previousWindows");
                        if (previous == null) scope.remove(); else scope.set(previous);
                    }
                }
            }));
            hooks.add(XposedBridge.hookMethod(visible, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam hook) {
                    List<Restore> changed = scope.get();
                    if (changed == null || hook.hasThrowable() || !(hook.getResult() instanceof List)) return;
                    try {
                        for (Object window : (List<?>) hook.getResult()) {
                            if (window == null) continue;
                            WindowManager.LayoutParams original = (WindowManager.LayoutParams) attributes.get(window);
                            if (original == null || (original.flags & WindowManager.LayoutParams.FLAG_SECURE) == 0) continue;
                            // Only change this Binder reply's local parameter copy while
                            // running the host's inlined precheck. Never modify a live WMS window.
                            WindowManager.LayoutParams copy = new WindowManager.LayoutParams();
                            copy.copyFrom(original);
                            copy.flags &= ~WindowManager.LayoutParams.FLAG_SECURE;
                            changed.add(new Restore(window, original));
                            attributes.set(window, copy);
                        }
                    } catch (Throwable error) { XposedBridge.log("XiaobuLauncherHook: modern window precheck retained: " + error); }
                }
            }));
            hooks.commit();
            XposedBridge.log("XiaobuLauncherHook: 17.7.8 launcher, blacklist and scoped window precheck installed");
        } catch (Throwable error) {
            XposedBridge.log("XiaobuLauncherHook: modern entry retained: " + error);
        }
        return true;
    }

    private static final class Restore {
        final Object window;
        final WindowManager.LayoutParams original;
        Restore(Object window, WindowManager.LayoutParams original) { this.window = window; this.original = original; }
    }
}
