package dev.codex.xiaobu;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.view.View;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Adds native-sized image search and word actions to the selection toolbar. */
final class SelectionToolsHook {
    static void install(ClassLoader loader) {
        try {
            String base = "com.oplus.supertext.core.view.focusmenu.";
            Class<?> menu = Class.forName(base + "FocusMenuView", false, loader);
            Class<?> popup = Class.forName(base + "PopupListItem", false, loader);
            Class<?> item = Class.forName(base + "BasicFunctionsItem", false, loader);
            Class<?> type = Class.forName(base + "FocusMenuType", false, loader);
            Class<?> click;
            boolean modern;
            try { click = Class.forName(base + "FocusMenuView$initBasicFunctions$1$1", false, loader); modern = false; }
            catch (ClassNotFoundException renamed) { click = Class.forName(base + "a", false, loader); modern = true; }
            final boolean indexedClick = modern;
            Method clickMethod = modern ? click.getDeclaredMethod("a", int.class)
                    : click.getDeclaredMethod("onItemClick", View.class, int.class);
            Constructor<?> itemConstructor = item.getConstructor(Drawable.class, String.class, type);
            Constructor<?> popupConstructor = popup.getConstructor(List.class, List.class, List.class);
            Method items = popup.getMethod("getBasicFunctionsItemList");
            Method scenes = popup.getMethod("getSceneIntelligenceItemList");
            Method ai = popup.getMethod("getAiMenuListItemList");
            Method kind = item.getMethod("getFocusMenuType");
            Method icon = item.getMethod("getIconResId");
            Object none = type.getField("NONE").get(null);
            Field owner = click.getDeclaredField("a"), clickItems = click.getDeclaredField("b");
            owner.setAccessible(true); clickItems.setAccessible(true);
            if (owner.getType() != menu || !List.class.isAssignableFrom(clickItems.getType()))
                throw new NoSuchFieldException("Unknown selection callback owner/items");
            Method identification = null;
            for (Method method : menu.getMethods()) {
                if (method.getName().startsWith("getIdentificationData$") && method.getParameterTypes().length == 0) identification = method;
            }
            if (identification == null) throw new NoSuchMethodException("Selection data getter");
            final Method dataGetter = identification;
            Class<?> data = identification.getReturnType();
            Method bitmapGetter = data.getMethod("getBitmap");
            ScreenshotButtons.NativeUi nativeUi = new ScreenshotButtons.NativeUi(loader);
            // Resolve teardown before injecting any item so a partial installation
            // never exposes a synthetic action without a usable click handler.
            menu.getDeclaredMethod("setIdentificationData", data);
            menu.getDeclaredMethod("onDetachedFromWindow");
            final java.util.concurrent.atomic.AtomicBoolean ready = new java.util.concurrent.atomic.AtomicBoolean();
            XposedHelpers.findAndHookMethod(menu, "setPopListItem", popup, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    try {
                        if (!ready.get() || hook.args[0] == null) return;
                        List<?> original = (List<?>) items.invoke(hook.args[0]);
                        if (original == null || original.isEmpty()) return;
                        List<Object> result = new ArrayList<>();
                        boolean image = false;
                        for (Object entry : original) {
                            if (icon.invoke(entry) instanceof ToolIcon) continue;
                            String name = String.valueOf(kind.invoke(entry));
                            if ("SAVE".equals(name) || "ANNOTATION".equals(name)) image = true;

                            result.add(entry);
                        }
                        Context context = ((View) hook.thisObject).getContext();
                        if (image) result.add(Math.min(2, result.size()), itemConstructor.newInstance(new ToolIcon(context), "搜图", none));
                        hook.args[0] = popupConstructor.newInstance(ai.invoke(hook.args[0]), result, scenes.invoke(hook.args[0]));
                    } catch (Throwable error) { ScreenshotButtons.log("selection toolbar retained", error); }
                }
            });
            XposedBridge.hookMethod(clickMethod, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    try {
                        List<?> list = (List<?>) clickItems.get(hook.thisObject);
                        int index = (Integer) hook.args[indexedClick ? 0 : 1];
                        if (index < 0 || index >= list.size()) return;
                        Object entry = list.get(index);
                        Object drawable = icon.invoke(entry);
                        if (!(drawable instanceof ToolIcon)) return;
                        // Consume our synthetic item even if the selection expired.
                        hook.setResult(null);
                        View target = (View) owner.get(hook.thisObject);
                        Object selected = dataGetter.invoke(target);
                        if (selected == null) return;
                        Bitmap bitmap = (Bitmap) bitmapGetter.invoke(selected);
                        if (bitmap == null || bitmap.isRecycled()) return;
                        ScreenshotButtons.Binding binding = ScreenshotButtons.selectedImage(target, bitmap);
                        ScreenshotActions.showMenu(target, indexedClick ? target : (View) hook.args[0], binding, nativeUi);
                        hook.setResult(null);
                    } catch (Throwable error) { ScreenshotButtons.log("selection action retained", error); }
                }
            });
            Class<?> identificationType = dataGetter.getReturnType();
            XposedHelpers.findAndHookMethod(menu, "setIdentificationData", identificationType, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    ScreenshotButtons.releaseSelection((View) hook.thisObject, nativeUi);
                }
            });
            XposedHelpers.findAndHookMethod(menu, "onDetachedFromWindow", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    ScreenshotButtons.releaseSelection((View) hook.thisObject, nativeUi);
                }
            });
            ready.set(true);
            XposedBridge.log("XiaobuLauncherHook: selected-image search installed");
        } catch (Throwable error) { ScreenshotButtons.log("selection tools unavailable; native toolbar retained", error); }
    }

    private static final class ToolIcon extends RecognitionActionIcon {
        ToolIcon(Context context) { super(context, SEARCH); }
    }
}
