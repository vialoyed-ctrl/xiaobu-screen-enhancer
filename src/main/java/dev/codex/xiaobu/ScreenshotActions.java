package dev.codex.xiaobu;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Toast;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import de.robv.android.xposed.XposedBridge;

/** Native menu, bounded image writes and package-restricted engine handoff. */
final class ScreenshotActions {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final String[] PACKAGES = {
            "com.google.android.googlequicksearchbox", "ru.yandex.searchplugin" };
    private static final String[] ENTRY_POINTS = {
            "com.google.android.apps.search.lens.LensShareEntryPointActivity",
            "com.yandex.browser.ShareImageActivity" };
    private static final ThreadPoolExecutor WRITER = new ThreadPoolExecutor(1, 1, 30,
            TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(1), runnable -> {
                Thread thread = new Thread(runnable, "XiaobuSaveScreenshot");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    static { WRITER.allowCoreThreadTimeOut(true); }

    static Activity activity(Context context) {
        // Bound traversal also tolerates malformed wrappers from an unsupported host.
        for (int depth = 0; depth < 32; depth++) {
            if (context instanceof Activity) return (Activity) context;
            if (!(context instanceof ContextWrapper)) return null;
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) return null;
            context = next;
        }
        return null;
    }

    static void saveAndClose(View alert, ScreenshotButtons.Binding binding, ScreenshotButtons.NativeUi ui) {
        Context app = alert.getContext().getApplicationContext();
        ScreenshotSession.Action<Bitmap> action = claim(app, binding);
        if (action != null && enqueue(app, action, null)) closeRecognition(alert, ui);
    }

    static void showMenu(View alert, View anchor, ScreenshotButtons.Binding binding,
            ScreenshotButtons.NativeUi ui) {
        Context context = alert.getContext();
        if (!ScreenshotButtons.CAPTURES.hasImage(binding.generation)) {
            toast(context, "未找到识屏截图，请重新启动识屏后再试");
            return;
        }
        if (ScreenshotButtons.CAPTURES.actionUnavailable(binding.generation)) return;
        try {
            Object previous = binding.popup.get();
            if (previous != null && Boolean.TRUE.equals(ui.popupShowing.invoke(previous))) return;
            Object popup = ui.popup.newInstance(context);
            List<Object> items = new ArrayList<Object>(2);
            items.add(ui.popupItem.newInstance("Google", engineIcon(context, 0), null));
            items.add(ui.popupItem.newInstance("Yandex", engineIcon(context, 1), null));
            ui.popupItems.invoke(popup, items);
            final Context app = context.getApplicationContext();
            final WeakReference<View> target = new WeakReference<View>(alert);
            final AtomicBoolean selected = new AtomicBoolean();
            Object callback = Proxy.newProxyInstance(ui.loader, new Class<?>[]{ui.function},
                    (proxy, method, args) -> {
                        if ("invoke".equals(method.getName())) {
                            if (args == null || args.length < 2 || !(args[1] instanceof Integer)) return ui.unit;
                            int engine = (Integer) args[1];
                            if (engine < 0 || engine >= PACKAGES.length || !selected.compareAndSet(false, true)) return ui.unit;
                            View view = target.get();
                            if (view == null || binding.closed) return ui.unit;
                            if (resolveShare(app, engine, null, null) == null) {
                                toast(app, "未找到对应的识图应用");
                                return ui.unit;
                            }
                            // Selection is the first point where an image is borrowed or saved.
                            ScreenshotSession.Action<Bitmap> action = claim(app, binding);
                            if (action != null) enqueue(app, action, uri -> {
                                View current = target.get();
                                if (current == null || binding.closed
                                        || !ScreenshotButtons.CAPTURES.isGeneration(binding.generation)) return;
                                Intent intent = resolveShare(app, engine, uri, null);
                                if (intent == null) { toast(app, "未找到对应的识图应用"); return; }
                                closeRecognition(current, ui);
                                MAIN.postDelayed(() -> {
                                    // A newer recognition must not be interrupted by an older save.
                                    if (!ScreenshotButtons.CAPTURES.isGeneration(binding.generation)) return;
                                    launch(app, engine, uri, intent);
                                }, 320);
                            });
                            return ui.unit;
                        }
                        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                        if ("equals".equals(method.getName())) return args != null && args.length == 1 && proxy == args[0];
                        return "XiaobuImageSearchCallback";
                    });
            ui.popupCallback.invoke(popup, callback);
            binding.popup = new WeakReference<Object>(popup);
            ui.popupShow.invoke(popup, anchor);
        } catch (Throwable error) {
            ScreenshotButtons.log("native search menu failed", error);
            toast(context, "无法显示识图菜单");
        }
    }

    private static Drawable engineIcon(Context context, int engine) {
        try { return context.getPackageManager().getApplicationIcon(PACKAGES[engine]); }
        catch (Exception unavailable) {
            // Missing one engine must not prevent the other entry from being shown.
            return context.getPackageManager().getDefaultActivityIcon();
        }
    }

    private static ScreenshotSession.Action<Bitmap> claim(Context app, ScreenshotButtons.Binding binding) {
        ScreenshotSession.Action<Bitmap> action = ScreenshotButtons.CAPTURES.beginAction(binding.generation);
        if (action == null && !ScreenshotButtons.CAPTURES.hasImage(binding.generation)) {
            toast(app, "未找到识屏截图，请重新启动识屏后再试");
        }
        return action;
    }

    private interface SavedCallback { void onSaved(Uri uri); }

    private static boolean enqueue(Context app, ScreenshotSession.Action<Bitmap> action, SavedCallback callback) {
        try {
            WRITER.execute(() -> write(app, action, callback));
            return true;
        } catch (RejectedExecutionException full) {
            action.finish(false);
            toast(app, "正在保存截图，请稍后重试");
            return false;
        } catch (Throwable error) {
            action.finish(false);
            ScreenshotButtons.log("screenshot writer unavailable", error);
            toast(app, "保存截图失败");
            return false;
        }
    }

    private static void write(Context app, ScreenshotSession.Action<Bitmap> action, SavedCallback callback) {
        Uri uri = null;
        boolean success = false;
        try {
            Bitmap picture = action.value();
            if (picture.isRecycled()) throw new IllegalStateException("Screenshot no longer available");
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
                if (app.getContentResolver().update(uri, ready, null, null) != 1) {
                    throw new IllegalStateException("MediaStore image was not published");
                }
            }
            XposedBridge.log("XiaobuLauncherHook: screenshot saved to " + uri);
            success = true;
        } catch (Throwable error) {
            if (uri != null) {
                try { app.getContentResolver().delete(uri, null, null); }
                catch (Throwable cleanup) { ScreenshotButtons.log("partial screenshot cleanup failed", cleanup); }
            }
            ScreenshotButtons.log("screenshot save failed", error);
            toast(app, "保存截图失败");
        } finally {
            action.finish(success);
        }
        if (success && callback != null) {
            final Uri saved = uri;
            MAIN.post(() -> {
                try { callback.onSaved(saved); }
                catch (Throwable error) {
                    ScreenshotButtons.log("image search handoff failed", error);
                    toast(app, "无法打开识图应用");
                }
            });
        }
    }

    private static Intent imageIntent(Uri uri) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("image/png");
        if (uri != null) {
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.setClipData(ClipData.newRawUri("识屏截图", uri));
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    private static boolean canOpen(Context context, ActivityInfo info, String requiredPackage) {
        return info != null && requiredPackage.equals(info.packageName) && info.exported && info.enabled
                && (info.applicationInfo == null || info.applicationInfo.enabled)
                && (info.permission == null || context.getPackageManager().checkPermission(
                        info.permission, context.getPackageName()) == PackageManager.PERMISSION_GRANTED);
    }

    private static Intent resolveShare(Context app, int engine, Uri uri, String excludedClass) {
        try {
            PackageManager packages = app.getPackageManager();
            String name = PACKAGES[engine];
            Intent explicit = imageIntent(uri).setClassName(name, ENTRY_POINTS[engine]);
            ResolveInfo direct = packages.resolveActivity(explicit, 0);
            if (!ENTRY_POINTS[engine].equals(excludedClass) && direct != null
                    && canOpen(app, direct.activityInfo, name)) return explicit;
            Intent withinEngine = imageIntent(uri).setPackage(name);
            List<ResolveInfo> matches = packages.queryIntentActivities(withinEngine, PackageManager.MATCH_DEFAULT_ONLY);
            for (ResolveInfo match : matches) {
                ActivityInfo info = match.activityInfo;
                if (canOpen(app, info, name) && !info.name.equals(excludedClass)) {
                    // Keep fallback inside the selected engine, even with multiple share targets.
                    return imageIntent(uri).setClassName(name, info.name);
                }
            }
        } catch (Throwable error) { ScreenshotButtons.log("image engine resolution failed", error); }
        return null;
    }

    private static void launch(Context app, int engine, Uri uri, Intent intent) {
        try {
            app.startActivity(intent);
        } catch (Exception first) {
            String previous = intent.getComponent() == null ? null : intent.getComponent().getClassName();
            Intent fallback = resolveShare(app, engine, uri, previous);
            if (fallback != null) {
                try { app.startActivity(fallback); return; }
                catch (Exception second) { ScreenshotButtons.log("image engine fallback failed", second); }
            }
            ScreenshotButtons.log("image engine launch failed", first);
            toast(app, "无法打开识图应用");
        }
    }

    private static void closeRecognition(View alert, ScreenshotButtons.NativeUi ui) {
        Activity activity = activity(alert.getContext());
        if (activity != null && !activity.isFinishing()) {
            try { activity.onBackPressed(); return; }
            catch (Throwable error) { ScreenshotButtons.log("native back failed", error); }
        }
        try { ui.dismiss.invoke(alert); }
        catch (Throwable error) { ScreenshotButtons.log("dismiss fallback failed", error); }
    }

    private static void toast(Context context, String message) {
        final Context app = context.getApplicationContext();
        MAIN.post(() -> {
            try { Toast.makeText(app, message, Toast.LENGTH_SHORT).show(); }
            catch (Throwable error) { ScreenshotButtons.log("screenshot failure notice unavailable", error); }
        });
    }
}
