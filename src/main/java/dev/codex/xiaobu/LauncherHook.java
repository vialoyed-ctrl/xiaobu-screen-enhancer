package dev.codex.xiaobu;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class LauncherHook implements IXposedHookLoadPackage {
    private static final String TARGET = "com.coloros.colordirectservice";
    private static final String TRANSLATE = "com.coloros.translate";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam params) {
        if ("android".equals(params.packageName) || "system".equals(params.packageName)) {
            TranslationLayerHook.install(params.classLoader);
            dev.codex.xiaobu.gesture.GestureSystemHook.install(params.classLoader);
            return;
        }
        if (TRANSLATE.equals(params.packageName)) {
            installTranslationHooks(params.classLoader);
            return;
        }
        if (!TARGET.equals(params.packageName)) return;
        RecognitionTimingHook.install(params.classLoader);
        StartupAnimationHook.install(params.classLoader);
        NetworkOcrHook.install(params.classLoader);
        RecognitionRuleCacheHook.install(params.classLoader);
        try {
            XposedHelpers.findAndHookMethod(
                    "com.oplus.infocollection.common.a",
                    params.classLoader,
                    "a",
                    XC_MethodReplacement.returnConstant(false));
            XposedBridge.log("XiaobuLauncherHook: launcher gate bypass installed");
        } catch (Throwable error) {
            XposedBridge.log("XiaobuLauncherHook: hook failed: " + error);
        }
        try {
            XposedHelpers.findAndHookMethod(
                    "com.coloros.colordirectservice.identify.entrance.DirectEntranceHandler",
                    params.classLoader,
                    "j",
                    "android.content.Context",
                    "com.coloros.colordirectservice.identify.beans.DirectStartInfo",
                    boolean.class,
                    "kotlin.coroutines.Continuation",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam hook) throws Throwable {
                            // A resumed Kotlin suspend call may pass null here. Only
                            // intercept the initial call that carries DirectStartInfo.
                            if (hook.args.length > 1 && hook.args[1] != null) {
                                hook.setResult(Boolean.TRUE);
                                XposedBridge.log("XiaobuLauncherHook: app blacklist bypassed");
                            }
                        }
                    });
            XposedBridge.log("XiaobuLauncherHook: app blacklist hook installed");
        } catch (Throwable error) {
            XposedBridge.log("XiaobuLauncherHook: blacklist hook failed: " + error);
        }
        try {
            XposedHelpers.findAndHookMethod(
                    "com.oplus.infocollection.utils.WindowManagerUtils",
                    params.classLoader,
                    "i",
                    "com.oplus.app.OplusWindowInfo",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam hook) throws Throwable {
                            hook.setResult(null);
                        }
                    });
            XposedBridge.log("XiaobuLauncherHook: secure-window precheck hook installed");
        } catch (Throwable error) {
            XposedBridge.log("XiaobuLauncherHook: secure-window precheck hook failed: " + error);
        }
        try {
            XposedHelpers.findAndHookMethod("com.coloros.directui.business.beans.CardUIInfo",
                    params.classLoader, "isEnable", new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam hook) throws Throwable {
                            try {
                                if ("TRANSLATE".equals(String.valueOf(call(hook.thisObject, "getCardType")))) {
                                    hook.setResult(Boolean.TRUE);
                                }
                            } catch (ReflectiveOperationException ignored) { }
                        }
                    });
            XposedBridge.log("XiaobuLauncherHook: translation action card enabled");
        } catch (Throwable error) {
            XposedBridge.log("XiaobuLauncherHook: translation card hook failed: " + error);
        }
        installSaveButton(params.classLoader);
        SelectionToolsHook.install(params.classLoader);
        SelectedTextHook.install();
        InPlaceWordSelection.install(params.classLoader);
    }

    private static void installTranslationHooks(ClassLoader loader) {
        String[][] checks = {
                {"com.coloros.translate.utils.j", "b"},
                {"com.coloros.translate.screen.utils.d0", "h"},
                {"com.coloros.translate.screen.ScreenTranslationService", "m"}
        };
        for (String[] check : checks) {
            try {
                XposedHelpers.findAndHookMethod(check[0], loader, check[1],
                        XC_MethodReplacement.returnConstant(false));
                XposedBridge.log("XiaobuLauncherHook: translation page gate bypass installed: " + check[0]);
            } catch (Throwable error) {
                XposedBridge.log("XiaobuLauncherHook: translation page hook failed: " + error);
            }
        }
        try {
            Class<?> event = Class.forName("com.coloros.translate.screen.utils.m", false, loader);
            final java.lang.reflect.Field callbackField = event.getDeclaredField("b");
            callbackField.setAccessible(true);
            Class<?> function = Class.forName("kotlin.jvm.functions.Function1", false, loader);
            final java.lang.reflect.Method invoke = function.getMethod("invoke", Object.class);
            XposedHelpers.findAndHookMethod("com.coloros.translate.screen.utils.m", loader,
                    "handleEvent", String.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam hook) {
                            try {
                                Object callback = callbackField.get(hook.thisObject);
                                if (callback == null) return;
                                invoke.invoke(callback, Boolean.FALSE);
                                hook.setResult(null);
                            } catch (Throwable ignored) {
                                // Keep the native path when this version's callback differs.
                            }
                        }
                    });
            XposedBridge.log("XiaobuLauncherHook: translation secure-window precheck hook installed");
        } catch (Throwable error) {
            XposedBridge.log("XiaobuLauncherHook: translation secure-window hook failed: " + error);
        }
    }

    private static void installSaveButton(ClassLoader loader) {
        ScreenshotButtons.install(loader);
    }

    private static Object call(Object obj, String method) throws Exception {
        return obj.getClass().getMethod(method).invoke(obj);
    }
}
