package dev.codex.xiaobu;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class LauncherHook implements IXposedHookLoadPackage {
    private static final String TARGET = "com.coloros.colordirectservice";
    private static final String TRANSLATE = "com.coloros.translate";
    private static final String SAVE_LABEL = "保存截图";
    private static final String SEARCH_LABEL = "识图";
    private static volatile Bitmap lastCapture;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam params) {
        if ("android".equals(params.packageName)) {
            TranslationLayerHook.install(params.classLoader);
            return;
        }
        if (TRANSLATE.equals(params.packageName)) {
            installTranslationHooks(params.classLoader);
            return;
        }
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
        try {
            XposedHelpers.findAndHookMethod("com.coloros.directui.business.beans.CardUIInfo",
                    params.classLoader, "isEnable", new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam hook) throws Throwable {
                            if ("TRANSLATE".equals(String.valueOf(call(hook.thisObject, "getCardType")))) {
                                hook.setResult(Boolean.TRUE);
                            }
                        }
                    });
            XposedBridge.log("XiaobuLauncherHook: translation action card enabled");
        } catch (Throwable error) {
            XposedBridge.log("XiaobuLauncherHook: translation card hook failed: " + error);
        }
        installSaveButton(params.classLoader);
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
            XposedHelpers.findAndHookMethod("com.coloros.translate.screen.utils.m", loader,
                    "handleEvent", String.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam hook) throws Throwable {
                            java.lang.reflect.Field field = hook.thisObject.getClass().getDeclaredField("b");
                            field.setAccessible(true);
                            Object callback = field.get(hook.thisObject);
                            Class<?> function = Class.forName("kotlin.jvm.functions.Function1", false, loader);
                            function.getMethod("invoke", Object.class).invoke(callback, Boolean.FALSE);
                            hook.setResult(null);
                        }
                    });
            XposedBridge.log("XiaobuLauncherHook: translation secure-window precheck hook installed");
        } catch (Throwable error) {
            XposedBridge.log("XiaobuLauncherHook: translation secure-window hook failed: " + error);
        }
    }

    private static void installSaveButton(ClassLoader loader) {
        try {
            // CaptureUtils returns the picture before the recognition overlay is shown.
            XposedHelpers.findAndHookMethod(
                    "com.oplus.infocollection.collection.common.b", loader, "c",
                    Context.class, String.class, Rect.class, boolean.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam hook) {
                            if (hook.getResult() instanceof Bitmap) {
                                try {
                                    Bitmap source = (Bitmap) hook.getResult();
                                    Bitmap retained = source.copy(Bitmap.Config.ARGB_8888, false);
                                    if (retained == null) throw new IllegalStateException("Bitmap copy returned null");
                                    lastCapture = retained;
                                    XposedBridge.log("XiaobuLauncherHook: copied picture " + retained.getWidth() + "x" + retained.getHeight());
                                } catch (Throwable error) {
                                    XposedBridge.log("XiaobuLauncherHook: picture copy failed: " + error);
                                }
                            }
                        }
                    });

            Class<?> alertClass = Class.forName("com.coloros.directui.ui.DirectCardAlert", false, loader);
            XposedBridge.hookAllConstructors(alertClass, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam hook) {
                    if (hook.args.length == 5 && hook.args[3] instanceof List && hook.args[0] instanceof Context) {
                        try {
                            List<?> cards = (List<?>) hook.thisObject.getClass().getField("n").get(hook.thisObject);
                            insertSaveCard(cards, (Context) hook.args[0]);
                        } catch (Throwable e) {
                            XposedBridge.log("XiaobuLauncherHook: initial card insertion failed: " + e);
                        }
                    }
                }
            });
            XposedHelpers.findAndHookMethod(alertClass.getName(), loader, "Z", List.class,
                    boolean.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam hook) throws Throwable {
                            if (hook.args[0] instanceof List) {
                                List<Object> copy = new ArrayList<>((List<?>) hook.args[0]);
                                insertSaveCard(copy, ((android.view.View) hook.thisObject).getContext());
                                hook.args[0] = copy;
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod(alertClass.getName(), loader, "a", int.class,
                    android.view.View.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam hook) throws Throwable {
                            List<?> cards = (List<?>) hook.thisObject.getClass().getField("n").get(hook.thisObject);
                            int index = (Integer) hook.args[0];
                            if (index < 0 || index >= cards.size()) return;
                            Object label = call(cards.get(index), "getName");
                            if (!SAVE_LABEL.equals(label) && !SEARCH_LABEL.equals(label)) return;
                            Context context = ((android.view.View) hook.thisObject).getContext();
                            Context app = context.getApplicationContext();
                            hook.setResult(null);
                            Bitmap picture = lastCapture;
                            if (SEARCH_LABEL.equals(label)) {
                                showImageSearch(context, hook.thisObject, picture, (android.view.View) hook.args[1]);
                                return;
                            }
                            closeRecognition(context, hook.thisObject);
                            saveCapture(app, picture);
                        }
                    });
            XposedBridge.log("XiaobuLauncherHook: save screenshot button hooks installed");
        } catch (Throwable error) {
            XposedBridge.log("XiaobuLauncherHook: save screenshot button hook failed: " + error);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void insertSaveCard(List cards, Context context) throws Exception {
        // Rebuild only our two entries so refreshes cannot duplicate them.
        for (int i = cards.size() - 1; i >= 0; i--) {
            Object name = call(cards.get(i), "getName");
            if (SAVE_LABEL.equals(name) || SEARCH_LABEL.equals(name)) cards.remove(i);
        }
        int index = -1;
        for (int i = 0; i < cards.size(); i++) {
            Object category = call(cards.get(i), "getCategory");
            if ("FUNCTION".equals(String.valueOf(category))) { index = i; break; }
        }
        if (index < 0) return;
        Object template = cards.get(index);
        Class<?> type = template.getClass();
        ClassLoader loader = type.getClassLoader();
        Constructor<?> ctor = type.getConstructor(String.class, Drawable.class,
                Class.forName("com.coloros.directui.business.beans.Category", false, loader),
                Class.forName("com.coloros.directui.business.beans.CardType", false, loader), Object.class,
                boolean.class, boolean.class, boolean.class, boolean.class, boolean.class,
                List.class, List.class, boolean.class);
        Object search = ctor.newInstance(SEARCH_LABEL, new SearchIcon(context),
                call(template, "getCategory"), call(template, "getCardType"), null,
                true, true, false, false, true, null, null, false);
        Object button = ctor.newInstance(SAVE_LABEL, new SaveIcon(context),
                call(template, "getCategory"), call(template, "getCardType"), null,
                true, true, false, false, true, null, null, false);
        cards.add(index, search);
        cards.add(index + 1, button);
        XposedBridge.log("XiaobuLauncherHook: save screenshot button inserted at " + index);
    }

    private static Object call(Object obj, String method) throws Exception {
        Method m = obj.getClass().getMethod(method);
        return m.invoke(obj);
    }

    private static void closeRecognition(Context context, Object alert) {
        Context current = context;
        while (current instanceof ContextWrapper && !(current instanceof Activity)) {
            Context next = ((ContextWrapper) current).getBaseContext();
            if (next == current) break;
            current = next;
        }
        if (current instanceof Activity) {
            try {
                Activity activity = (Activity) current;
                // Use Xiaobu's normal Back handler. It fades the recognition
                // surface out, then finishes the activity after the animation.
                activity.onBackPressed();
                XposedBridge.log("XiaobuLauncherHook: native back animation started on tap");
                return;
            } catch (Throwable error) {
                XposedBridge.log("XiaobuLauncherHook: native back failed: " + error);
            }
        }
        try {
            call(alert, "I");
        } catch (Throwable error) {
            XposedBridge.log("XiaobuLauncherHook: dismiss fallback failed: " + error);
        }
    }

    private interface SavedCallback {
        void onSaved(Uri uri);
    }

    private static final class SearchRequest {
        Uri uri;
        String engine;
        boolean cancelled;
        boolean launched;
    }

    private static void showImageSearch(Context context, Object alert, Bitmap picture, android.view.View anchor) {
        if (picture == null || picture.isRecycled()) {
            toast(context, "未找到识屏截图，请重新启动识屏后再试");
            return;
        }
        try {
            // Reuse Xiaobu's own anchored, blurred COUI menu, including its
            // native spacing, rounded surface, selection and dismiss animation.
            ClassLoader loader = alert.getClass().getClassLoader();
            Class<?> popupClass = Class.forName("com.coloros.directui.ui.panel.FloatPopupList", false, loader);
            Class<?> itemClass = Class.forName("com.coloros.directui.ui.panel.FloatPopupList$b", false, loader);
            Class<?> functionClass = Class.forName("kotlin.jvm.functions.Function3", false, loader);
            Object popup = popupClass.getConstructor(Context.class).newInstance(context);
            Constructor<?> itemConstructor = itemClass.getConstructor(String.class, Drawable.class, List.class);
            List<Object> items = new ArrayList<>();
            items.add(itemConstructor.newInstance("Google", context.getPackageManager().getApplicationIcon(
                    "com.google.android.googlequicksearchbox"), null));
            items.add(itemConstructor.newInstance("Yandex", context.getPackageManager().getApplicationIcon(
                    "ru.yandex.searchplugin"), null));
            popupClass.getMethod("p", List.class).invoke(popup, items);
            Object unit = Class.forName("kotlin.Unit", false, loader).getField("INSTANCE").get(null);
            Object callback = Proxy.newProxyInstance(loader, new Class<?>[]{functionClass}, (proxy, method, args) -> {
                if ("invoke".equals(method.getName())) {
                    SearchRequest request = new SearchRequest();
                    request.engine = ((Integer) args[1]) == 0 ? "google" : "yandex";
                    saveCapture(context.getApplicationContext(), picture, uri -> {
                        request.uri = uri;
                        launchImageSearch(context, alert, request);
                    });
                    return unit;
                }
                if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                if ("equals".equals(method.getName())) return proxy == args[0];
                return "XiaobuImageSearchCallback";
            });
            popupClass.getMethod("q", functionClass).invoke(popup, callback);
            popupClass.getMethod("t", android.view.View.class).invoke(popup, anchor);
        } catch (Throwable error) {
            XposedBridge.log("XiaobuLauncherHook: native search menu failed: " + error);
            toast(context, "无法显示识图菜单");
        }
    }

    private static void launchImageSearch(Context context, Object alert, SearchRequest request) {
        // Both dialog selection and the save callback run on the main thread.
        if (request.cancelled || request.launched || request.uri == null || request.engine == null) return;
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("image/png");
        intent.putExtra(Intent.EXTRA_STREAM, request.uri);
        intent.setClipData(ClipData.newRawUri("识屏截图", request.uri));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        if ("google".equals(request.engine)) {
            intent.setClassName("com.google.android.googlequicksearchbox",
                    "com.google.android.apps.search.lens.LensShareEntryPointActivity");
        } else {
            intent.setClassName("ru.yandex.searchplugin", "com.yandex.browser.ShareImageActivity");
        }
        if (context.getPackageManager().resolveActivity(intent, 0) == null) {
            toast(context, "未找到对应的识图应用");
            return;
        }
        request.launched = true;
        closeRecognition(context, alert);
        // Let the same native Back fade finish before opening the search app.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                context.getApplicationContext().startActivity(intent);
                XposedBridge.log("XiaobuLauncherHook: image search launched: " + request.engine);
            } catch (Throwable error) {
                XposedBridge.log("XiaobuLauncherHook: image search failed: " + error);
                toast(context, "无法打开识图应用");
            }
        }, 320);
    }

    private static void saveCapture(Context app, final Bitmap picture) {
        saveCapture(app, picture, null);
    }

    private static void saveCapture(Context app, final Bitmap picture, SavedCallback callback) {
        if (picture == null || picture.isRecycled()) {
            toast(app, "未找到识屏截图，请重新启动识屏后再试");
            return;
        }
        new Thread(() -> {
            Uri uri = null;
            try {
                ContentValues values = new ContentValues();
                String date = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
                values.put(MediaStore.Images.Media.DISPLAY_NAME, "小布识屏_" + date + ".png");
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                if (Build.VERSION.SDK_INT >= 29) {
                    values.put("relative_path", "Pictures/小布识屏增强");
                    values.put("is_pending", 1);
                }
                uri = app.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IllegalStateException("MediaStore insert returned null");
                try (OutputStream stream = app.getContentResolver().openOutputStream(uri)) {
                    if (stream == null || !picture.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                        throw new IllegalStateException("PNG write failed");
                    }
                }
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues ready = new ContentValues();
                    ready.put("is_pending", 0);
                    app.getContentResolver().update(uri, ready, null, null);
                }
                XposedBridge.log("XiaobuLauncherHook: screenshot saved to " + uri);
                if (callback != null) {
                    final Uri savedUri = uri;
                    new Handler(Looper.getMainLooper()).post(() -> callback.onSaved(savedUri));
                }
            } catch (Throwable error) {
                if (uri != null) app.getContentResolver().delete(uri, null, null);
                XposedBridge.log("XiaobuLauncherHook: screenshot save failed: " + error);
                toast(app, "保存截图失败");
            }
        }, "XiaobuSaveScreenshot").start();
    }

    private static void toast(Context context, String message) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
    }

    private static final class SaveIcon extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int size;

        SaveIcon(Context context) {
            size = Math.round(24 * context.getResources().getDisplayMetrics().density);
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.8f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            canvas.save();
            canvas.translate(bounds.left, bounds.top);
            canvas.scale(bounds.width() / 24f, bounds.height() / 24f);
            canvas.drawRoundRect(new RectF(3, 3, 21, 21), 2, 2, paint);
            canvas.drawLine(8, 3, 8, 10, paint);
            canvas.drawLine(8, 10, 16, 10, paint);
            canvas.drawLine(16, 10, 16, 3, paint);
            canvas.drawLine(7, 20, 7, 15, paint);
            canvas.drawLine(7, 15, 17, 15, paint);
            canvas.drawLine(17, 15, 17, 20, paint);
            canvas.restore();
        }

        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private static final class SearchIcon extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int size;

        SearchIcon(Context context) {
            size = Math.round(24 * context.getResources().getDisplayMetrics().density);
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.8f);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            canvas.save();
            canvas.translate(bounds.left, bounds.top);
            canvas.scale(bounds.width() / 24f, bounds.height() / 24f);
            canvas.drawCircle(10, 10, 6.5f, paint);
            canvas.drawLine(15, 15, 21, 21, paint);
            canvas.restore();
        }

        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
