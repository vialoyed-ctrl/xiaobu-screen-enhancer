package dev.codex.xiaobu;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
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
    private static final String SAVE_LABEL = "保存截图";
    private static volatile Bitmap lastCapture;

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
        installSaveButton(params.classLoader);
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
                            if (index < 0 || index >= cards.size()
                                    || !SAVE_LABEL.equals(call(cards.get(index), "getName"))) return;
                            Context context = ((android.view.View) hook.thisObject).getContext();
                            Context app = context.getApplicationContext();
                            hook.setResult(null);
                            Bitmap picture = lastCapture;
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
        for (Object card : cards) if (SAVE_LABEL.equals(call(card, "getName"))) return;
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
        Object button = ctor.newInstance(SAVE_LABEL, new SaveIcon(context),
                call(template, "getCategory"), call(template, "getCardType"), null,
                true, true, false, false, true, null, null, false);
        cards.add(index, button);
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

    private static void saveCapture(Context app, final Bitmap picture) {
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
                toast(app, "已保存截图");
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
            paint.setStrokeWidth(Math.max(2, size / 12f));
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
}
