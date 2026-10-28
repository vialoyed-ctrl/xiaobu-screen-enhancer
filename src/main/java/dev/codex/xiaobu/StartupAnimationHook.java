package dev.codex.xiaobu;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.view.MotionEvent;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Keeps native startup scan playback and removes waits after recognition completes. */
final class StartupAnimationHook {
    private static final ThreadLocal<Boolean> enteringFragment = new ThreadLocal<Boolean>();
    private static volatile boolean entering;

    private static boolean fastEntry() {
        return entering;
    }

    static void install(ClassLoader loader) {
        installEntryPhase(loader);
        installToolbarScopes(loader);
        installDoubleScanSpeed(loader);
        // Keep native engine initialization, asset loading, screenshot preparation,
        // scan visibility and animation playback. Only remove completion delays.
        try (HookTransaction hooks = new HookTransaction()) {
            Class<?> animator = Class.forName("com.oplus.infocollection.floating.CollectAnimator", false, loader);
            Class<?> function = Class.forName("kotlin.jvm.functions.Function0", false, loader);
            final Method complete = animator.getDeclaredMethod("v", function);
            final Method wait = animator.getDeclaredMethod("w", function);
            final Method delay = animator.getDeclaredMethod("n", long.class, function);
            if (complete.getReturnType() != void.class || wait.getReturnType() != void.class
                    || delay.getReturnType() != void.class) {
                throw new NoSuchMethodException("Unknown scan completion contract");
            }
            complete.setAccessible(true);
            hooks.add(XposedBridge.hookMethod(wait, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) throws Throwable {
                    // Recognition has finished: stop the scan now, without its 1200ms floor.
                    complete.invoke(hook.thisObject, hook.args[0]);
                    hook.setResult(null);
                    XposedBridge.log("XiaobuLauncherHook: recognition ready, minimum scan wait bypassed");
                }
            }));
            hooks.add(XposedBridge.hookMethod(delay, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    // Also remove the native 50ms tail after stopAllAnim().
                    hook.args[0] = 0L;
                }
            }));
            hooks.commit();
            XposedBridge.log("XiaobuLauncherHook: native startup scan restored; completion wait bypassed");
        } catch (Throwable error) {
            XposedBridge.log("XiaobuLauncherHook: native scan completion retained: " + error);
        }
        try {
            String enter = "com.oplus.infocollection.view.anim.g";
            Class<?> type = Class.forName(enter, false, loader);
            final Field animations = type.getDeclaredField("b");
            animations.setAccessible(true);
            XposedHelpers.findAndHookMethod(enter, loader, "f", boolean.class, boolean.class,
                    View[].class, instantPreparedAnimation(animations, 0, true));
            XposedHelpers.findAndHookMethod(enter, loader, "h", View.class, boolean.class,
                    instantPreparedAnimation(animations, 1, true));
            XposedHelpers.findAndHookMethod(enter, loader, "j",
                    "com.oplus.infocollection.view.viewer.CollectionImgViewer", boolean.class,
                    instantPreparedAnimation(animations, 1, false));
            XposedBridge.log("XiaobuLauncherHook: result entry animations shortened");
        } catch (Throwable error) {
            XposedBridge.log("XiaobuLauncherHook: result entry hooks unavailable: " + error);
        }
        try {
            XposedHelpers.findAndHookMethod("com.coloros.directui.ui.CollectionInfoFragment", loader,
                    "D", new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam hook) {
                            hook.setObjectExtra("xiaobu.previousEntry", enteringFragment.get());
                            enteringFragment.set(Boolean.TRUE);
                        }
                        @Override protected void afterHookedMethod(MethodHookParam hook) {
                            Object previous = hook.getObjectExtra("xiaobu.previousEntry");
                            if (previous == null) enteringFragment.remove();
                            else enteringFragment.set((Boolean) previous);
                        }
                    });
            XposedHelpers.findAndHookMethod(ValueAnimator.class.getName(), loader, "start",
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam hook) {
                            if (Boolean.TRUE.equals(enteringFragment.get())) {
                                Animator animation = (Animator) hook.thisObject;
                                animation.setStartDelay(0);
                                animation.setDuration(0);
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod("com.coloros.directui.ui.CollectInfoActivity", loader,
                    "initView", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam hook) {
                            Activity activity = (Activity) hook.thisObject;
                            activity.getWindow().setWindowAnimations(0);
                            activity.overridePendingTransition(0, 0);
                            try {
                                Activity.class.getMethod("overrideActivityTransition", int.class, int.class, int.class)
                                        .invoke(activity, 0, 0, 0);
                            } catch (ReflectiveOperationException ignored) {
                                // The window animation attribute covers Android versions before API 34.
                            }
                        }
                    });
            XposedBridge.log("XiaobuLauncherHook: recognition activity fast entry installed");
        } catch (Throwable error) {
            XposedBridge.log("XiaobuLauncherHook: recognition activity entry hook unavailable: " + error);
        }
    }

    private static void installDoubleScanSpeed(ClassLoader loader) {
        try (HookTransaction hooks = new HookTransaction()) {
            Class<?> collect = Class.forName("com.oplus.infocollection.floating.CollectAnimator", false, loader);
            Class<?> engine = Class.forName("com.oplus.vfxsdk.naive.COEEngine", false, loader);
            final Field ownedEngine = collect.getDeclaredField("b");
            final Method getAnimator = engine.getMethod("getAnimator", String.class);
            final Method setSpeed = getAnimator.getReturnType().getMethod("setSpeed", float.class);
            final Method play = engine.getMethod("playAnim", String.class);
            if (ownedEngine.getType() != engine || setSpeed.getReturnType() != void.class) {
                throw new NoSuchMethodException("Unknown scan playback contract");
            }
            ownedEngine.setAccessible(true);
            final ThreadLocal<Object> scanEngine = new ThreadLocal<Object>();
            XC_MethodHook scope = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) throws Throwable {
                    hook.setObjectExtra("xiaobu.previousSpeedEngine", scanEngine.get());
                    scanEngine.set(ownedEngine.get(hook.thisObject));
                }
                @Override protected void afterHookedMethod(MethodHookParam hook) {
                    Object previous = hook.getObjectExtra("xiaobu.previousSpeedEngine");
                    if (previous == null) scanEngine.remove();
                    else scanEngine.set(previous);
                }
            };
            Class<?> function = Class.forName("kotlin.jvm.functions.Function0", false, loader);
            hooks.add(XposedHelpers.findAndHookMethod(collect, "u", function, scope));
            hooks.add(XposedHelpers.findAndHookMethod(collect, "x", function, scope));
            hooks.add(XposedHelpers.findAndHookMethod(collect, "y", scope));
            hooks.add(XposedBridge.hookMethod(play, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    if (scanEngine.get() != hook.thisObject) return;
                    String name = (String) hook.args[0];
                    if (!"Start".equals(name) && !"SelectStart".equals(name) && !"Loop".equals(name)) return;
                    try {
                        Object animation = getAnimator.invoke(hook.thisObject, name);
                        // Native 17.5.14 uses 0.6 for SelectStart and 1.0 for Start/Loop.
                        // Set absolute speeds each play, so repeated scans do not compound.
                        if (animation != null) setSpeed.invoke(animation, "SelectStart".equals(name) ? 1.2f : 2.0f);
                    } catch (Throwable error) {
                        XposedBridge.log("XiaobuLauncherHook: native scan speed retained: " + error);
                    }
                }
            }));
            hooks.commit();
        } catch (Throwable error) {
            XposedBridge.log("XiaobuLauncherHook: scan speed hook unavailable: " + error);
        }
    }

    private static void installEntryPhase(ClassLoader loader) {
        XC_MethodHook begin = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam hook) {
                entering = true;
            }
        };
        try {
            XposedHelpers.findAndHookMethod("com.oplus.infocollection.floating.DirectCollectStarter",
                    loader, "k", Activity.class,
                    "com.oplus.infocollection.data.CollectionStartInfo", begin);
            Class<?> activity = Class.forName("com.coloros.directui.ui.CollectInfoActivity", false, loader);
            XposedBridge.hookAllMethods(activity, "initView", begin);
            XposedBridge.hookAllMethods(activity, "onNewIntent", begin);
            XC_MethodHook end = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) { entering = false; }
            };
            XposedBridge.hookAllMethods(activity, "onBackPressed", end);
            XposedBridge.hookAllMethods(activity, "onDestroy", end);
            XposedHelpers.findAndHookMethod(Activity.class.getName(), loader, "dispatchTouchEvent",
                    MotionEvent.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam hook) {
                            if ("com.coloros.directui.ui.CollectInfoActivity".equals(hook.thisObject.getClass().getName())
                                    && ((MotionEvent) hook.args[0]).getActionMasked() == MotionEvent.ACTION_DOWN) {
                                entering = false;
                            }
                        }
                    });
            // Remove the stagger between toolbar cards, only during initial presentation.
            XposedHelpers.findAndHookMethod("com.coloros.directui.ui.anim.c", loader, "r", int.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam hook) {
                            if (fastEntry()) hook.setResult(0L);
                        }
                    });
            XposedBridge.log("XiaobuLauncherHook: complete entry phase hooks installed");
        } catch (Throwable error) {
            XposedBridge.log("XiaobuLauncherHook: entry phase hooks unavailable: " + error);
        }
        try {
            Class<?> spring = Class.forName("com.coui.appcompat.animation.dynamicanimation.COUISpringAnimation", false, loader);
            final Method canSkip = spring.getMethod("canSkipToEnd");
            final Method skip = spring.getMethod("skipToEnd");
            XposedBridge.hookAllMethods(spring.getMethod("start").getDeclaringClass(), "start", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam hook) {
                    if (!Boolean.TRUE.equals(enteringFragment.get()) || !spring.isInstance(hook.thisObject)) return;
                    try {
                        if (Boolean.TRUE.equals(canSkip.invoke(hook.thisObject))) skip.invoke(hook.thisObject);
                    } catch (Throwable error) {
                        XposedBridge.log("XiaobuLauncherHook: native spring retained: " + error);
                    }
                }
            });
        } catch (Throwable error) {
            XposedBridge.log("XiaobuLauncherHook: spring entry hooks unavailable: " + error);
        }
    }

    private static void installToolbarScopes(ClassLoader loader) {
        try (HookTransaction hooks = new HookTransaction()) {
            Class<?> function = Class.forName("kotlin.jvm.functions.Function0", false, loader);
            String cards = "com.coloros.directui.ui.anim.c";
            hooks.add(XposedHelpers.findAndHookMethod(cards, loader, "q",
                    "androidx.recyclerview.widget.LinearLayoutManager", function, toolbarScope(false)));
            hooks.add(XposedHelpers.findAndHookMethod(cards, loader, "m", View.class, int.class,
                    int.class, int.class, function, toolbarScope(true)));
            hooks.add(XposedHelpers.findAndHookMethod(cards, loader, "o", View.class, int.class,
                    function, toolbarScope(false)));
            hooks.commit();
            XposedBridge.log("XiaobuLauncherHook: scoped toolbar entry installed");
        } catch (Throwable error) {
            XposedBridge.log("XiaobuLauncherHook: native toolbar entry retained: " + error);
        }
    }

    private static XC_MethodHook toolbarScope(final boolean stagger) {
        return new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam hook) {
                hook.setObjectExtra("xiaobu.previousEntry", enteringFragment.get());
                if (fastEntry()) {
                    enteringFragment.set(Boolean.TRUE);
                    if (stagger) hook.args[3] = 0;
                }
            }
            @Override protected void afterHookedMethod(MethodHookParam hook) {
                Object previous = hook.getObjectExtra("xiaobu.previousEntry");
                if (previous == null) enteringFragment.remove();
                else enteringFragment.set((Boolean) previous);
            }
        };
    }

    private static XC_MethodHook instantPreparedAnimation(final Field animations,
            final int directionArgument, final boolean enteringValue) {
        return new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam hook) throws Throwable {
                if (!Boolean.valueOf(enteringValue).equals(hook.args[directionArgument])) return;
                // Let Animator deliver native final values and start/end listeners normally.
                for (Object value : (List<?>) animations.get(hook.thisObject)) {
                    Animator animation = (Animator) value;
                    animation.setStartDelay(0);
                    animation.setDuration(0);
                }
            }
        };
    }
}


