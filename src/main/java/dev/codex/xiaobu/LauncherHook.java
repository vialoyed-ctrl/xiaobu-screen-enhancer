package dev.codex.xiaobu;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class LauncherHook implements IXposedHookLoadPackage {
    private static final String TARGET = "com.coloros.colordirectservice";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam params) {
        if (!TARGET.equals(params.packageName)) return;
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
    }
}
