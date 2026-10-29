package dev.codex.xiaobu;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Completes native image results using visible view bounds and the same capture. */
final class VisibleImageHook {
    static void install(ClassLoader loader) {
        try (HookTransaction hooks = new HookTransaction()) {
            Class<?> parser = Class.forName("com.oplus.infocollection.collection.core.CollectionParse", false, loader);
            Class<?> info = Class.forName("yi.n", false, loader);
            Class<?> extracted = Class.forName("com.oplus.infocollection.collection.extract.c", false, loader);
            Class<?> node = Class.forName("com.oplus.infocollection.collection.extract.g", false, loader);
            Class<?> child = Class.forName("com.oplus.infocollection.collection.extract.a", false, loader);
            Class<?> item = Class.forName("yi.c", false, loader);
            Class<?> continuation = Class.forName("kotlin.coroutines.Continuation", false, loader);
            final Method bitmap = info.getMethod("c");
            final Method minAreaSide = info.getMethod("L", int.class);
            final Method minSide = info.getMethod("M", int.class);
            final Method nodes = extracted.getMethod("c");
            final Method visible = node.getMethod("s");
            final Method bounds = node.getMethod("r");
            final Method children = node.getMethod("c");
            final Method childBounds = child.getMethod("j");
            final Method itemBounds = item.getMethod("g");
            final Method itemType = item.getMethod("f");
            final Method crop = parser.getDeclaredMethod("L", Bitmap.class, List.class, CopyOnWriteArrayList.class);
            Method prepare = parser.getDeclaredMethod("s0", Context.class, info, ComponentName.class);
            Method merge = parser.getDeclaredMethod("h0", info, extracted, List.class, CopyOnWriteArrayList.class, continuation);
            Method sort = parser.getDeclaredMethod("r0", CopyOnWriteArrayList.class);
            if (bitmap.getReturnType() != Bitmap.class || bounds.getReturnType() != Rect.class
                    || childBounds.getReturnType() != Rect.class || itemBounds.getReturnType() != Rect.class
                    || visible.getReturnType() != boolean.class || itemType.getReturnType() != int.class
                    || prepare.getReturnType() != void.class || crop.getReturnType() != void.class
                    || sort.getReturnType() != void.class || merge.getReturnType() != Object.class) {
                throw new NoSuchMethodException("Unknown visible image contract");
            }
            crop.setAccessible(true);
            final List<Pending> pending = new ArrayList<Pending>();
            hooks.add(XposedBridge.hookMethod(prepare, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam hook) {
                    if (hook.hasThrowable()) return;
                    try {
                        // Native defaults (58dp area side, 40dp shortest side) exclude avatars.
                        minAreaSide.invoke(hook.args[1], 1);
                        minSide.invoke(hook.args[1], 1);
                    } catch (Throwable error) { log(error); }
                }
            }));
            hooks.add(XposedBridge.hookMethod(merge, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    // Kotlin resumed calls carry null arguments; capture only the initial call.
                    if (hook.args[0] == null || hook.args[1] == null || hook.args[3] == null) return;
                    try {
                        Bitmap source = (Bitmap) bitmap.invoke(hook.args[0]);
                        if (source == null || source.isRecycled()) return;
                        ArrayList<Rect> rectangles = new ArrayList<Rect>();
                        for (Object value : (List<?>) nodes.invoke(hook.args[1])) {
                            if (!Boolean.TRUE.equals(visible.invoke(value))) continue;
                            add(rectangles, (Rect) bounds.invoke(value), source);
                            for (Object nested : (List<?>) children.invoke(value)) {
                                add(rectangles, (Rect) childBounds.invoke(nested), source);
                            }
                        }
                        synchronized (pending) {
                            for (Iterator<Pending> it = pending.iterator(); it.hasNext();) {
                                Object output = it.next().output.get();
                                if (output == null || output == hook.args[3]) it.remove();
                            }
                            if (pending.size() >= 8) pending.remove(0);
                            pending.add(new Pending(hook.args[3], source, rectangles));
                        }
                    } catch (Throwable error) { log(error); }
                }
            }));
            hooks.add(XposedBridge.hookMethod(sort, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    Pending state = null;
                    synchronized (pending) {
                        for (Iterator<Pending> it = pending.iterator(); it.hasNext();) {
                            Pending next = it.next();
                            Object output = next.output.get();
                            if (output == null || output == hook.args[0]) {
                                it.remove();
                                if (output != null) state = next;
                            }
                        }
                    }
                    if (state == null) return;
                    try {
                        Bitmap source = state.source.get();
                        if (source == null || source.isRecycled()) return;
                        List<?> output = (List<?>) hook.args[0];
                        ArrayList<Rect> missing = new ArrayList<Rect>();
                        for (Rect rectangle : state.rectangles) {
                            boolean found = false;
                            for (Object value : output) {
                                if (((Integer) itemType.invoke(value)) == 2
                                        && sameBounds(rectangle, (Rect) itemBounds.invoke(value))) { found = true; break; }
                            }
                            if (!found) missing.add(rectangle);
                        }
                        // Native cropper owns saving, URI creation, and bitmap lifetime.
                        if (!missing.isEmpty()) crop.invoke(hook.thisObject, source, missing, hook.args[0]);
                        XposedBridge.log("XiaobuLauncherHook: visible images candidates=" + state.rectangles.size()
                                + " screenshot fallback=" + missing.size());
                    } catch (Throwable error) { log(error); }
                }
            }));
            hooks.commit();
            XposedBridge.log("XiaobuLauncherHook: visible image fallback and small images enabled");
        } catch (Throwable error) { log(error); }
    }

    private static void add(List<Rect> output, Rect bounds, Bitmap source) {
        // An empty visible bound means fully clipped/covered; never restore its full bounds.
        if (bounds == null || bounds.isEmpty()) return;
        Rect clipped = new Rect(bounds);
        if (!clipped.intersect(0, 0, source.getWidth(), source.getHeight()) || output.contains(clipped)) return;
        output.add(clipped);
    }

    private static boolean sameBounds(Rect first, Rect second) {
        if (second == null || second.isEmpty()) return false;
        // Native crops can round or trim a few pixels at the edge of a view.
        int toleranceX = Math.max(2, Math.min(first.width(), second.width()) / 20);
        int toleranceY = Math.max(2, Math.min(first.height(), second.height()) / 20);
        return Math.abs(first.left - second.left) <= toleranceX
                && Math.abs(first.right - second.right) <= toleranceX
                && Math.abs(first.top - second.top) <= toleranceY
                && Math.abs(first.bottom - second.bottom) <= toleranceY;
    }

    private static void log(Throwable error) {
        XposedBridge.log("XiaobuLauncherHook: native visible image path retained: " + error);
    }

    private static final class Pending {
        final WeakReference<Object> output;
        final WeakReference<Bitmap> source;
        final List<Rect> rectangles;
        Pending(Object output, Bitmap source, List<Rect> rectangles) {
            this.output = new WeakReference<Object>(output);
            this.source = new WeakReference<Bitmap>(source);
            this.rectangles = rectangles;
        }
    }
}
