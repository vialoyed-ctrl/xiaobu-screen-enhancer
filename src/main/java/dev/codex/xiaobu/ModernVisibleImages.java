package dev.codex.xiaobu;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Rect;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** 17.7.8 uses inlined data accessors but retains native crop and sort boundaries. */
final class ModernVisibleImages {
    static boolean install(ClassLoader loader) {
        final Class<?> parser;
        try {
            parser = Class.forName("com.oplus.infocollection.collection.core.a", false, loader);
            parser.getDeclaredMethod("z", Class.forName("y34", false, loader),
                    Class.forName("ig2", false, loader), List.class, CopyOnWriteArrayList.class,
                    Class.forName("kotlin.coroutines.jvm.internal.ContinuationImpl", false, loader));
        } catch (ReflectiveOperationException legacy) { return false; }
        try (HookTransaction hooks = new HookTransaction()) {
            Class<?> info = Class.forName("y34", false, loader);
            Class<?> extracted = Class.forName("ig2", false, loader);
            Class<?> node = Class.forName("yz5", false, loader);
            Class<?> child = Class.forName("dg0", false, loader);
            Class<?> item = Class.forName("fl0", false, loader);
            Class<?> continuation = Class.forName("kotlin.coroutines.jvm.internal.ContinuationImpl", false, loader);
            Field bitmap = member(info, "a", Bitmap.class);
            Field nodes = member(extracted, "b", List.class);
            Field visible = member(node, "f", boolean.class);
            Field bounds = member(node, "l", Rect.class);
            Field children = member(node, "o", ArrayList.class);
            Field childBounds = member(child, "g", Rect.class);
            Field itemBounds = member(item, "a", Rect.class);
            Field itemType = member(item, "c", int.class);
            Method crop = parser.getDeclaredMethod("o", Bitmap.class, List.class, CopyOnWriteArrayList.class);
            Method merge = parser.getDeclaredMethod("z", info, extracted, List.class, CopyOnWriteArrayList.class, continuation);
            Method sort = parser.getDeclaredMethod("D", CopyOnWriteArrayList.class);
            Class<?> dimensions = Class.forName("com.oplus.infocollection.collection.R$dimen", false, loader);
            final int side = dimensions.getField("collection_min_content_side_size").getInt(null);
            final int smallest = dimensions.getField("collection_min_content_smallest_side_size").getInt(null);
            if (crop.getReturnType() != void.class || !Modifier.isStatic(crop.getModifiers())
                    || merge.getReturnType() != Object.class || sort.getReturnType() != void.class
                    || !Modifier.isStatic(sort.getModifiers())) throw new NoSuchMethodException("Unknown modern image contract");
            crop.setAccessible(true);
            // The old setters disappeared. Change only these two verified host resource
            // IDs, before the native parser stores the limits in its per-capture data.
            hooks.add(XposedBridge.hookMethod(Resources.class.getMethod("getDimensionPixelSize", int.class), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam hook) {
                    int id = (Integer) hook.args[0];
                    if (!hook.hasThrowable() && (id == side || id == smallest)) hook.setResult(1);
                }
            }));
            List<Pending> pending = new ArrayList<>();
            hooks.add(XposedBridge.hookMethod(merge, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    if (hook.args[0] == null || hook.args[1] == null || hook.args[3] == null) return;
                    try {
                        Bitmap source = (Bitmap) bitmap.get(hook.args[0]);
                        if (source == null || source.isRecycled()) return;
                        List<Rect> rectangles = new ArrayList<>();
                        for (Object value : (List<?>) nodes.get(hook.args[1])) {
                            if (!visible.getBoolean(value)) continue;
                            VisibleImageHook.add(rectangles, (Rect) bounds.get(value), source);
                            for (Object nested : (List<?>) children.get(value))
                                VisibleImageHook.add(rectangles, (Rect) childBounds.get(nested), source);
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
                            Pending entry = it.next();
                            Object output = entry.output.get();
                            if (output == null || output == hook.args[0]) {
                                it.remove();
                                if (output != null) state = entry;
                            }
                        }
                    }
                    if (state == null) return;
                    try {
                        Bitmap source = state.source.get();
                        if (source == null || source.isRecycled()) return;
                        List<Rect> missing = new ArrayList<>();
                        for (Rect rectangle : state.rectangles) {
                            boolean found = false;
                            for (Object value : (List<?>) hook.args[0]) {
                                if (itemType.getInt(value) == 2 && VisibleImageHook.sameBounds(rectangle, (Rect) itemBounds.get(value))) {
                                    found = true; break;
                                }
                            }
                            if (!found) missing.add(rectangle);
                        }
                        if (!missing.isEmpty()) crop.invoke(null, source, missing, hook.args[0]);
                        XposedBridge.log("XiaobuLauncherHook: 17.7.8 visible images candidates="
                                + state.rectangles.size() + " screenshot fallback=" + missing.size());
                    } catch (Throwable error) { log(error); }
                }
            }));
            hooks.commit();
            XposedBridge.log("XiaobuLauncherHook: 17.7.8 visible image fallback and small images enabled");
        } catch (Throwable error) { log(error); }
        return true;
    }

    private static Field member(Class<?> type, String name, Class<?> expected) throws Exception {
        Field field = type.getDeclaredField(name);
        if (field.getType() != expected) throw new NoSuchFieldException(type.getName() + "." + name);
        field.setAccessible(true);
        return field;
    }

    private static void log(Throwable error) { XposedBridge.log("XiaobuLauncherHook: modern visible images retained: " + error); }

    private static final class Pending {
        final WeakReference<Object> output;
        final WeakReference<Bitmap> source;
        final List<Rect> rectangles;
        Pending(Object output, Bitmap source, List<Rect> rectangles) {
            this.output = new WeakReference<>(output);
            this.source = new WeakReference<>(source);
            this.rectangles = rectangles;
        }
    }
}
