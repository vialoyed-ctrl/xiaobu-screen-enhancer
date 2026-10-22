package dev.codex.xiaobu;

import android.content.Context;
import android.view.WindowManager;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Places translation overlays above the shade without granting extra window permissions. */
final class TranslationLayerHook {
    private static final String TRANSLATE = "com.coloros.translate";
    private static final int OVERLAY = 2038;
    // Above notification shade (17), below secure system overlay (33).
    private static final int LAYER = 32;
    private static final ThreadLocal<Boolean> addingTranslation = new ThreadLocal<Boolean>();
    private static final Map<Object, Boolean> tokens =
            Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());
    private static volatile boolean ready;

    static void install(ClassLoader loader) {
        try {
            Class<?> service = Class.forName("com.android.server.wm.WindowManagerService", false, loader);
            Class<?> session = Class.forName("com.android.server.wm.Session", false, loader);
            Class<?> token = Class.forName("com.android.server.wm.WindowToken", false, loader);
            Class<?> window = Class.forName("com.android.server.wm.WindowState", false, loader);
            final Field context = field(service, "mContext");
            final Field uid = field(session, "mUid");
            final Field owner = field(window, "mOwnerUid");
            final Field attrs = field(window, "mAttrs");
            final Field baseLayer = field(window, "mBaseLayer");
            final Field windowToken = field(window, "mToken");
            // Resolve all required members before enabling any layer changes.
            token.getDeclaredMethod("getWindowLayerFromType");
            XposedBridge.hookAllMethods(service, "addWindow", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    addingTranslation.set(Boolean.FALSE);
                    if (!ready || hook.args.length < 3) return;
                    try {
                        Object params = hook.args[2];
                        if (!(params instanceof WindowManager.LayoutParams)) return;
                        WindowManager.LayoutParams layout = (WindowManager.LayoutParams) params;
                        if (layout.type != OVERLAY || !TRANSLATE.equals(layout.packageName)) return;
                        Context app = (Context) context.get(hook.thisObject);
                        int actualUid = uid.getInt(hook.args[0]);
                        String[] packages = app.getPackageManager().getPackagesForUid(actualUid);
                        if (packages != null) for (String name : packages) {
                            if (TRANSLATE.equals(name)) addingTranslation.set(Boolean.TRUE);
                        }
                    } catch (Throwable ignored) {
                        // Unknown framework versions keep the original window placement.
                    }
                }
                @Override protected void afterHookedMethod(MethodHookParam hook) {
                    addingTranslation.remove();
                }
            });
            XposedBridge.hookAllConstructors(token, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    if (ready && Boolean.TRUE.equals(addingTranslation.get())
                            && hook.args.length > 2 && Integer.valueOf(OVERLAY).equals(hook.args[2])) {
                        tokens.put(hook.thisObject, Boolean.TRUE);
                    }
                }
            });
            XposedHelpers.findAndHookMethod(token.getName(), loader, "getWindowLayerFromType",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam hook) {
                            if (ready && tokens.containsKey(hook.thisObject)
                                    && hook.getResult() instanceof Integer) {
                                hook.setResult(Math.max(LAYER, (Integer) hook.getResult()));
                            }
                        }
                    });
            XposedBridge.hookAllConstructors(window, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam hook) {
                    if (!ready || !Boolean.TRUE.equals(addingTranslation.get())) return;
                    try {
                        WindowManager.LayoutParams layout = (WindowManager.LayoutParams) attrs.get(hook.thisObject);
                        if (layout.type != OVERLAY || !TRANSLATE.equals(layout.packageName)
                                || !tokens.containsKey(windowToken.get(hook.thisObject))) return;
                        int oldLayer = baseLayer.getInt(hook.thisObject);
                        baseLayer.setInt(hook.thisObject, Math.max(oldLayer, LAYER * 10000 + 1000));
                        XposedBridge.log("XiaobuLauncherHook: translation overlay raised, uid="
                                + owner.getInt(hook.thisObject));
                    } catch (Throwable error) {
                        XposedBridge.log("XiaobuLauncherHook: translation layer unchanged: " + error);
                    }
                }
            });
            ready = true;
            XposedBridge.log("XiaobuLauncherHook: translation system layer hooks installed");
        } catch (Throwable error) {
            ready = false;
            XposedBridge.log("XiaobuLauncherHook: translation system layer unavailable: " + error);
        }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field result = current.getDeclaredField(name);
                result.setAccessible(true);
                return result;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
}
