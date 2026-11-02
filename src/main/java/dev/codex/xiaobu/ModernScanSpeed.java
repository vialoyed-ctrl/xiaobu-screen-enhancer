package dev.codex.xiaobu;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** 17.7.8 native scan speed, scoped by the engine owned by CollectAnimator's singleton. */
final class ModernScanSpeed {
    static void install(ClassLoader loader) {
        try {
            Class.forName("com.oplus.infocollection.floating.CollectAnimator", false, loader);
            return; // StartupAnimationHook retains the original 17.5.14 implementation.
        } catch (ClassNotFoundException renamed) { }
        try {
            Class<?> collect = Class.forName("bk0", false, loader);
            Class<?> engine = Class.forName("com.oplus.vfxsdk.naive.COEEngine", false, loader);
            final Field singleton = collect.getDeclaredField("m");
            final Field ownedEngine = collect.getDeclaredField("a");
            final Method getAnimator = engine.getMethod("getAnimator", String.class);
            final Method setSpeed = getAnimator.getReturnType().getMethod("setSpeed", float.class);
            Method play = engine.getMethod("playAnim", String.class);
            if (singleton.getType() != collect || !Modifier.isStatic(singleton.getModifiers())
                    || ownedEngine.getType() != engine || Modifier.isStatic(ownedEngine.getModifiers())
                    || collect.getDeclaredField("c").getType() != long.class
                    || setSpeed.getReturnType() != void.class || play.getReturnType() != void.class) {
                throw new NoSuchMethodException("Unknown 17.7.8 scan playback contract");
            }
            singleton.setAccessible(true);
            ownedEngine.setAccessible(true);
            XposedBridge.hookMethod(play, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    String name = (String) hook.args[0];
                    if (!"Start".equals(name) && !"SelectStart".equals(name) && !"Loop".equals(name)) return;
                    try {
                        Object owner = singleton.get(null);
                        if (owner == null || ownedEngine.get(owner) != hook.thisObject) return;
                        Object animation = getAnimator.invoke(hook.thisObject, name);
                        // Native d0.run() uses 0.6 for SelectStart. Start/Loop use 1.0.
                        // Set absolute values on each play, including yj0's async Loop,
                        // so repeated scans never multiply an already accelerated speed.
                        if (animation != null) setSpeed.invoke(animation, "SelectStart".equals(name) ? 1.2f : 2.0f);
                    } catch (Throwable unavailable) {
                        XposedBridge.log("XiaobuLauncherHook: native 17.7.8 scan speed retained: " + unavailable);
                    }
                }
            });
            XposedBridge.log("XiaobuLauncherHook: 17.7.8 owned native scan playback accelerated 2x");
        } catch (Throwable unavailable) {
            XposedBridge.log("XiaobuLauncherHook: native 17.7.8 scan speed retained: " + unavailable);
        }
    }
}
