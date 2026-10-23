package dev.codex.xiaobu;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Installs screenshot UI only when the complete native contract is available. */
final class ScreenshotButtons {
    static final String SAVE_LABEL = "截图";
    static final String SEARCH_LABEL = "识图";
    static final ScreenshotSession<Bitmap> CAPTURES = new ScreenshotSession<Bitmap>(bitmap -> {
        try { if (!bitmap.isRecycled()) bitmap.recycle(); }
        catch (Throwable error) { log("picture release failed", error); }
    });
    private static final Map<Object, Binding> bindings = new WeakHashMap<Object, Binding>();
    private static final ThreadLocal<ArrayDeque<Long>> captures = new ThreadLocal<ArrayDeque<Long>>();
    private static boolean attempted;
    private static volatile boolean ready;

    static synchronized void install(ClassLoader loader) {
        if (attempted) return;
        attempted = true;
        try {
            final NativeUi ui = new NativeUi(loader);
            // These callbacks are inert until every required hook below is installed.
            XposedHelpers.findAndHookMethod(ui.alert.getName(), loader, "a", int.class, View.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam hook) {
                            if (!ready) return;
                            try {
                                Binding binding = bindings.get(hook.thisObject);
                                if (binding == null) return;
                                List<?> list = (List<?>) ui.cards.get(hook.thisObject);
                                int index = (Integer) hook.args[0];
                                if (index < 0 || index >= list.size()) return;
                                Object card = list.get(index);
                                boolean search = card == binding.searchCard;
                                if (!search && card != binding.saveCard) {
                                    // A failed native refresh must not send an older injected card
                                    // through the original template action either.
                                    if (ui.icon.invoke(card) instanceof ActionIcon) hook.setResult(null);
                                    return;
                                }
                                // An injected card must never reach the template's native action.
                                hook.setResult(null);
                                if (binding.closed) return;
                                View alert = (View) hook.thisObject;
                                if (search) ScreenshotActions.showMenu(alert, (View) hook.args[1], binding, ui);
                                else ScreenshotActions.saveAndClose(alert, binding, ui);
                            } catch (Throwable error) { log("screenshot action failed", error); }
                        }
                    });
            XposedHelpers.findAndHookMethod(ui.capture.getName(), loader, "c", Context.class,
                    String.class, Rect.class, boolean.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam hook) {
                            if (!ready) return;
                            ArrayDeque<Long> stack = captures.get();
                            if (stack == null) { stack = new ArrayDeque<Long>(); captures.set(stack); }
                            // Invalidate the old picture before the native capture can fail or throw.
                            stack.push(CAPTURES.beginCapture());
                        }
                        @Override protected void afterHookedMethod(MethodHookParam hook) {
                            ArrayDeque<Long> stack = captures.get();
                            if (stack == null || stack.isEmpty()) return;
                            long id = stack.pop();
                            if (stack.isEmpty()) captures.remove();
                            Bitmap retained = null;
                            try {
                                Object result = hook.getResult();
                                if (CAPTURES.isGeneration(id) && result instanceof Bitmap) {
                                    Bitmap source = (Bitmap) result;
                                    if (!source.isRecycled()) retained = source.copy(Bitmap.Config.ARGB_8888, false);
                                    if (retained == null) throw new IllegalStateException("Capture copy returned null");
                                }
                            } catch (Throwable error) { log("picture copy failed", error); }
                            finally { CAPTURES.publish(id, retained); }
                        }
                    });
            if (XposedBridge.hookAllMethods(ui.activity, "onDestroy", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    if (!ready) return;
                    try {
                        Activity activity = (Activity) hook.thisObject;
                        for (Binding binding : new ArrayList<Binding>(bindings.values())) {
                            if (binding.activity.get() == activity) {
                                binding.closed = true;
                                if (!activity.isChangingConfigurations()) CAPTURES.endCapture(binding.generation);
                            }
                        }
                    } catch (Throwable error) { log("screenshot UI release failed", error); }
                }
            }).isEmpty()) throw new NoSuchMethodException("Missing recognition activity teardown");
            XposedHelpers.findAndHookMethod(ui.alert.getName(), loader, "Z", List.class, boolean.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam hook) {
                            if (!ready || !(hook.args[0] instanceof List)) return;
                            try {
                                View alert = (View) hook.thisObject;
                                Binding binding = bindingFor(alert, alert.getContext());
                                hook.args[0] = ui.withButtons((List<?>) hook.args[0], binding, alert.getContext());
                                bindings.put(alert, binding);
                            } catch (Throwable error) { log("card refresh retained", error); }
                        }
                    });
            if (XposedBridge.hookAllConstructors(ui.alert, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    if (!ready || hook.args.length != 5 || !(hook.args[0] instanceof Context)
                            || !(hook.args[3] instanceof List)) return;
                    try {
                        Context context = (Context) hook.args[0];
                        Binding binding = bindingFor(hook.thisObject, context);
                        // Prepare the whole list first; the native constructor commits it normally.
                        hook.args[3] = ui.withButtons((List<?>) hook.args[3], binding, context);
                        bindings.put(hook.thisObject, binding);
                    } catch (Throwable error) { log("initial cards retained", error); }
                }
            }).isEmpty()) throw new NoSuchMethodException("Missing recognition card constructor");
            ready = true;
            XposedBridge.log("XiaobuLauncherHook: complete screenshot UI hooks installed");
        } catch (Throwable error) {
            ready = false;
            log("screenshot UI unavailable; native cards retained", error);
        }
    }

    private static Binding bindingFor(Object alert, Context context) {
        Binding binding = bindings.get(alert);
        long generation = CAPTURES.generation();
        if (binding == null || binding.generation != generation || binding.closed) {
            binding = new Binding(generation, ScreenshotActions.activity(context));
        }
        return binding;
    }

    static void log(String message, Throwable error) {
        XposedBridge.log("XiaobuLauncherHook: " + message + ": " + error);
    }

    static final class Binding {
        final long generation;
        final WeakReference<Activity> activity;
        Object searchCard;
        Object saveCard;
        WeakReference<Object> popup = new WeakReference<Object>(null);
        boolean closed;
        Binding(long generation, Activity activity) {
            this.generation = generation;
            this.activity = new WeakReference<Activity>(activity);
        }
    }

    /** Cached per host process/class loader; no Activity or View is retained here. */
    static final class NativeUi {
        final Class<?> alert;
        final Class<?> activity;
        final Class<?> capture;
        final Field cards;
        final Constructor<?> card;
        final Method category;
        final Method cardType;
        final Method icon;
        final Method dismiss;
        final ClassLoader loader;
        final Constructor<?> popup;
        final Constructor<?> popupItem;
        final Class<?> function;
        final Method popupItems;
        final Method popupCallback;
        final Method popupShow;
        final Method popupShowing;
        final Object unit;

        NativeUi(ClassLoader loader) throws Exception {
            this.loader = loader;
            alert = Class.forName("com.coloros.directui.ui.DirectCardAlert", false, loader);
            activity = Class.forName("com.coloros.directui.ui.CollectInfoActivity", false, loader);
            capture = Class.forName("com.oplus.infocollection.collection.common.b", false, loader);
            if (capture.getDeclaredMethod("c", Context.class, String.class, Rect.class, boolean.class)
                    .getReturnType() != Bitmap.class) throw new NoSuchMethodException("Unknown capture contract");
            cards = alert.getField("n");
            if (!List.class.isAssignableFrom(cards.getType())) throw new NoSuchFieldException("Unknown card list");
            alert.getDeclaredConstructor(Context.class, android.view.ViewGroup.class,
                    Class.forName("com.coloros.directui.business.beans.DirectIntentData", false, loader),
                    List.class, Drawable.class);
            alert.getDeclaredMethod("Z", List.class, boolean.class);
            alert.getDeclaredMethod("a", int.class, View.class);
            activity.getDeclaredMethod("onDestroy");
            dismiss = alert.getMethod("I");
            Class<?> type = Class.forName("com.coloros.directui.business.beans.CardUIInfo", false, loader);
            card = type.getConstructor(String.class, Drawable.class,
                    Class.forName("com.coloros.directui.business.beans.Category", false, loader),
                    Class.forName("com.coloros.directui.business.beans.CardType", false, loader), Object.class,
                    boolean.class, boolean.class, boolean.class, boolean.class, boolean.class,
                    List.class, List.class, boolean.class);
            category = type.getMethod("getCategory");
            cardType = type.getMethod("getCardType");
            icon = type.getMethod("getIcon");
            Class<?> popupType = Class.forName("com.coloros.directui.ui.panel.FloatPopupList", false, loader);
            popup = popupType.getConstructor(Context.class);
            popupItem = Class.forName("com.coloros.directui.ui.panel.FloatPopupList$b", false, loader)
                    .getConstructor(String.class, Drawable.class, List.class);
            function = Class.forName("kotlin.jvm.functions.Function3", false, loader);
            popupItems = popupType.getMethod("p", List.class);
            popupCallback = popupType.getMethod("q", function);
            popupShow = popupType.getMethod("t", View.class);
            popupShowing = popupType.getMethod("n");
            unit = Class.forName("kotlin.Unit", false, loader).getField("INSTANCE").get(null);
        }

        List<Object> withButtons(List<?> source, Binding binding, Context context) throws Exception {
            List<Object> result = new ArrayList<Object>(source.size() + 2);
            int index = -1;
            Object template = null;
            for (Object value : source) {
                if (value == binding.searchCard || value == binding.saveCard
                        || icon.invoke(value) instanceof ActionIcon) continue;
                if (index < 0 && "FUNCTION".equals(String.valueOf(category.invoke(value)))) {
                    index = result.size(); template = value;
                }
                result.add(value);
            }
            if (index < 0) return result;
            Object search = binding.searchCard;
            Object save = binding.saveCard;
            if (search == null || save == null) {
                search = newCard(SEARCH_LABEL, new ActionIcon(context, true), template);
                save = newCard(SAVE_LABEL, new ActionIcon(context, false), template);
            }
            result.add(index, search);
            result.add(index + 1, save);
            binding.searchCard = search;
            binding.saveCard = save;
            return result;
        }

        private Object newCard(String name, Drawable icon, Object template) throws Exception {
            return card.newInstance(name, icon, category.invoke(template), cardType.invoke(template), null,
                    true, true, false, false, true, null, null, false);
        }
    }

    private static final class ActionIcon extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF outline = new RectF(3, 3, 21, 21);
        private final int size;
        private final boolean search;
        ActionIcon(Context context, boolean search) {
            this.search = search;
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
            if (search) {
                canvas.drawCircle(10, 10, 6.5f, paint);
                canvas.drawLine(15, 15, 21, 21, paint);
            } else {
                canvas.drawRoundRect(outline, 2, 2, paint);
                canvas.drawLine(8, 3, 8, 10, paint);
                canvas.drawLine(8, 10, 16, 10, paint);
                canvas.drawLine(16, 10, 16, 3, paint);
                canvas.drawLine(7, 20, 7, 15, paint);
                canvas.drawLine(7, 15, 17, 15, paint);
                canvas.drawLine(17, 15, 17, 20, paint);
            }
            canvas.restore();
        }
        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
