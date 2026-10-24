package dev.codex.xiaobu;

import android.graphics.PointF;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.Paint;

import android.graphics.RectF;

import android.icu.text.BreakIterator;
import android.view.View;
import android.view.MotionEvent;
import java.lang.ref.WeakReference;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Changes native hit selection boundaries. Never creates, moves or replaces a view. */
final class InPlaceWordSelection {
    private static volatile boolean installed;
    private static final Map<Object, Index> indices = new WeakHashMap<>();
    private static final Map<Object, Touch> touches = new WeakHashMap<>();

    static boolean ready() { return installed; }

    static void install(ClassLoader loader) {
        try {
            Contract ui = new Contract(loader);
            Class<?> nativeView = Class.forName("com.oplus.supertext.core.view.supertext.SuperTextView", false, loader);
            Field viewModel = field(nativeView, "d");
            XposedHelpers.findAndHookMethod(nativeView, "e1", boolean.class, boolean.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) throws Throwable {
                    if (installed && indices.containsKey(viewModel.get(hook.thisObject))) hook.args[0] = false;
                }
            });
            // Keep native text pixels, backgrounds and geometry. Tint only the native selection paint.
            Class<?> renderer = Class.forName("com.oplus.supertext.core.view.supertext.SuperTextRender", false, loader);
            Field rendererModel = field(renderer, "c"), selectionPaint = field(renderer, "l");
            Map<Object, Integer> paintColors = new WeakHashMap<>();
            XposedHelpers.findAndHookMethod(renderer, "O", Canvas.class, int.class, int.class, Bitmap.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) throws Throwable {
                    if (!installed) return;
                    Object model = rendererModel.get(hook.thisObject);
                    Index index = indices.get(model);
                    if (index == null || index.selection == null || index.data.get() != ui.data.get(model)) return;
                    Paint paint = (Paint) selectionPaint.get(hook.thisObject);
                    paintColors.put(hook.thisObject, paint.getColor());
                    paint.setColor(0x73efb4c9);
                }
                @Override protected void afterHookedMethod(MethodHookParam hook) throws Throwable {
                    Integer previous = paintColors.remove(hook.thisObject);
                    if (previous != null) ((Paint) selectionPaint.get(hook.thisObject)).setColor(previous);
                }
            });
            XposedHelpers.findAndHookMethod(ui.model, "G0", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    Index index = indices.get(hook.thisObject);
                    if (index != null && index.selection != null) hook.setResult(true);
                }
            });
            XposedHelpers.findAndHookMethod(ui.model, "getSelectedText", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam hook) throws Throwable {
                    Index index = indices.get(hook.thisObject);
                    if (index != null && index.selection != null && index.data.get() == ui.data.get(hook.thisObject)
                            && hook.getResult() instanceof String)
                        hook.setResult(SelectedText.singleLine((String) hook.getResult()));
                }
            });
            XposedHelpers.findAndHookMethod(ui.controller, "a", MotionEvent.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    if (!installed) return;
                    try {
                        MotionEvent event = (MotionEvent) hook.args[0];
                        Object model = ui.controllerModel.get(hook.thisObject);
                        Touch touch = touches.get(hook.thisObject);
                        int action = event.getActionMasked();
                        if (action == MotionEvent.ACTION_DOWN) {
                            touches.remove(hook.thisObject);
                            PointF point = new PointF(event.getX(), event.getY());
                            ui.toModel.invoke(model, point);
                            int hit = hit(ui, model, point, (View) ui.controllerView.get(hook.thisObject));
                            if (hit < 0 || Boolean.TRUE.equals(ui.isImage.invoke(model, hit)) || range(ui, model, hit) == null) return;
                            touch = new Touch();
                            touch.hit = hit; touch.x = event.getX(); touch.y = event.getY();
                            touch.data = new WeakReference<>(ui.data.get(model));
                            Index index = indices.get(model);
                            touch.lastWord = wordAt(index, hit);
                            boolean[] baseline = index.selection != null ? index.selection
                                    : (boolean[]) ui.mask.invoke(ui.data.get(model));
                            touch.drag = new WordSelectionDrag(baseline, index.ranges, touch.lastWord);
                            View view = (View) ui.controllerView.get(hook.thisObject);
                            touch.slop = android.view.ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
                            touches.put(hook.thisObject, touch);
                            ((PointF) ui.downPoint.get(hook.thisObject)).set(point);
                            if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(true);
                            ui.hideTools.invoke(view, false);
                        }
                        if (touch == null) return;
                        // Consume the complete text gesture before native continuous selection.
                        hook.setResult(true);
                        if (event.getPointerCount() != 1 || action == MotionEvent.ACTION_CANCEL) touch.cancelled = true;
                        if (!touch.cancelled && touch.data.get() == ui.data.get(model)
                                && (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP)) {
                            if (Math.hypot(event.getX() - touch.x, event.getY() - touch.y) > touch.slop) touch.swiping = true;
                            if (touch.swiping) {
                                PointF point = new PointF(event.getX(), event.getY());
                                ui.toModel.invoke(model, point);
                                int hit = hit(ui, model, point, (View) ui.controllerView.get(hook.thisObject));
                                Index index = indices.get(model);
                                int word = wordAt(index, hit);
                                if (word >= 0) touch.lastWord = word;
                                setSelection(ui, hook.thisObject, model, index.ranges.get(touch.lastWord),
                                        touch.drag.update(touch.lastWord), false);
                            }
                        }
                        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                            touches.remove(hook.thisObject);
                            View view = (View) ui.controllerView.get(hook.thisObject);
                            if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(false);
                            if (touch.data.get() == ui.data.get(model)) {
                                Index index = indices.get(model);
                                if (touch.cancelled) {
                                    if (touch.swiping) setSelection(ui, hook.thisObject, model,
                                            index.ranges.get(touch.lastWord), touch.drag.baseline, false);
                                } else setSelection(ui, hook.thisObject, model, index.ranges.get(touch.lastWord),
                                        touch.drag.update(touch.lastWord), true);
                            }
                        }
                    } catch (Throwable error) { ScreenshotButtons.log("word input isolated", error); }
                }
            });
            installed = true;
            XposedBridge.log("XiaobuLauncherHook: in-place native word selection installed");
        } catch (Throwable error) {
            installed = false;
            ScreenshotButtons.log("word selection unavailable; native extraction retained", error);
        }
    }

    private static final class Touch {
        int hit, slop;
        float x, y;
        boolean cancelled;
        boolean swiping;
        int lastWord;
        WordSelectionDrag drag;
        WeakReference<Object> data;
    }

    private static int wordAt(Index index, int hit) {
        for (int i = 0; i < index.ranges.size(); i++) {
            int[] span = index.ranges.get(i);
            if (hit >= span[0] && hit <= span[1]) return i;
        }
        return -1;
    }

    private static int hit(Contract ui, Object model, PointF point, View view) throws Exception {
        int nativeHit = (Integer) ui.hitTest.invoke(model, point, false);
        range(ui, model, 0);
        Index index = indices.get(model);
        if (wordAt(index, nativeHit) >= 0 || (nativeHit >= 0 && Boolean.TRUE.equals(ui.isImage.invoke(model, nativeHit))))
            return nativeHit;
        float padding = 7f * view.getResources().getDisplayMetrics().density;
        float best = padding * padding;
        int nearest = -1;
        for (Token token : index.tokens) {
            RectF box = token.box;
            float dx = Math.max(Math.max(box.left - point.x, 0), point.x - box.right);
            float dy = Math.max(Math.max(box.top - point.y, 0), point.y - box.bottom);
            float distance = dx * dx + dy * dy;
            if (distance < best) { best = distance; nearest = token.start; }
        }
        return nearest >= 0 ? nearest : nativeHit;
    }

    private static void setSelection(Contract ui, Object controller, Object model, int[] range,
                                     boolean[] next, boolean finish) throws Exception {
        Index index = indices.get(model);
        apply(ui, model, next);
        index.selection = next.clone();

        ui.cursor.invoke(model, range[0], range[1]);

        Object view = ui.controllerView.get(controller);
        String selected = (String) ui.selectedText.invoke(model);
        List<?> listeners = (List<?>) ui.listeners.invoke(view);
        if (listeners != null) for (Object listener : listeners) ui.selectionChanged.invoke(listener, selected);
        ui.limitTools.invoke(view, false);
        if (finish) {
            if (selected == null || selected.isEmpty()) ui.hideTools.invoke(view, false);
            else ui.updateHandles.invoke(model);
        }
        ui.invalidate.invoke(view);
        int count = 0; for (boolean value : next) if (value) count++;
        if (finish) XposedBridge.log("XiaobuLauncherHook: in-place word selection range=" + range[0] + ".." + range[1]
                + " selected=" + count + " textLength=" + (selected == null ? 0 : selected.length()));
    }

    private static void apply(Contract ui, Object model, boolean[] selection) throws Exception {
        Object data = ui.data.get(model);
        Object oldTop = ui.top.get(data), oldBottom = ui.bottom.get(data), oldCombined = ui.combined.get(data);
        try {
            ui.top.set(data, new boolean[selection.length]);
            ui.bottom.set(data, selection.clone());
            ui.combined.set(data, selection.clone());
            ui.rebuild.invoke(data, true, false);
            ui.antPath.invoke(data);
        } catch (Exception error) {
            ui.top.set(data, oldTop); ui.bottom.set(data, oldBottom); ui.combined.set(data, oldCombined);
            ui.rebuild.invoke(data, true, false);
            throw error;
        }
    }

    private static int[] range(Contract ui, Object model, int hit) throws Exception {
        Object data = ui.data.get(model);
        if (data == null || hit < 0) return null;
        Index index = indices.get(model);
        if (index == null || index.data.get() != data) {
            List<int[]> ranges = new ArrayList<>();
            List<Token> tokens = new ArrayList<>();
            List<?> lines = (List<?>) ui.lines.invoke(data);
            BreakIterator iterator = BreakIterator.getWordInstance(Locale.SIMPLIFIED_CHINESE);

            for (Object line : lines) {

                if (Boolean.TRUE.equals(ui.imageLine.invoke(line))) continue;
                List<?> points = (List<?>) ui.points.invoke(line);
                StringBuilder text = new StringBuilder();
                List<Integer> mapping = new ArrayList<>();
                Map<Integer, RectF> boxes = new java.util.HashMap<>();
                for (Object point : points) {
                    if (Boolean.TRUE.equals(ui.imagePoint.invoke(point))) continue;
                    String value = (String) ui.pointText.invoke(point);
                    int nativeIndex = (Integer) ui.pointIndex.invoke(point);
                    if (value == null || value.contains("==START==") || value.contains("==END==")) continue;
                    text.append(value);
                    boxes.put(nativeIndex, new RectF(((Number) ui.minX.invoke(point)).floatValue(),
                            ((Number) ui.minY.invoke(point)).floatValue(), ((Number) ui.maxX.invoke(point)).floatValue(),
                            ((Number) ui.maxY.invoke(point)).floatValue()));
                    // OCR positions count native character objects, not UTF-16 code units.
                    for (int i = 0; i < value.length(); i++) mapping.add(nativeIndex);
                }
                iterator.setText(text.toString());
                int start = iterator.first();
                for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
                    if (end <= start || text.substring(start, end).trim().isEmpty()) continue;
                    int from = mapping.get(start), to = mapping.get(end - 1);
                    if (from >= 0 && to >= from) {
                        ranges.add(new int[]{from, to});
                        RectF box = new RectF();
                        for (int i = from; i <= to; i++) { RectF part = boxes.get(i); if (part != null) box.union(part); }
                        tokens.add(new Token(from, box));
                    }
                }
            }
            index = new Index(data, ranges, tokens);
            indices.put(model, index);

        }
        for (int[] span : index.ranges) if (hit >= span[0] && hit <= span[1]) return span;
        return null;
    }

    private static final class Index {
        final WeakReference<Object> data;
        final List<int[]> ranges;
        boolean[] selection;
        final List<Token> tokens;
        Index(Object data, List<int[]> ranges, List<Token> tokens) {
            this.data = new WeakReference<Object>(data); this.ranges = ranges; this.tokens = tokens;
        }
    }
    private static final class Token {
        final int start;
        final RectF box;
        Token(int start, RectF box) { this.start = start; this.box = box; }
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field value = type.getDeclaredField(name); value.setAccessible(true); return value;
    }
    private static Method method(Class<?> type, String name, Class<?>... args) throws Exception {
        Method value = type.getDeclaredMethod(name, args); value.setAccessible(true); return value;
    }
    private static final class Contract {
        final Class<?> model, controller;

        final Field controllerModel, controllerView, downPoint, data, top, bottom, combined;
        final Method minX, minY, maxX, maxY;
        final Method lines, points, imageLine, imagePoint, pointText, pointIndex, mask, rebuild, antPath;
        final Method isImage, cursor, selectedText, listeners, selectionChanged, invalidate, hitTest, toModel, updateHandles, limitTools, hideTools;
        Contract(ClassLoader loader) throws Exception {
            model = Class.forName("com.oplus.supertext.core.view.supertext.SuperTextModel", false, loader);
            controller = Class.forName("com.oplus.supertext.core.view.supertext.c0", false, loader);
            Class<?> state = Class.forName("com.oplus.supertext.core.data.SuperTextData", false, loader);
            Class<?> line = Class.forName("com.oplus.supertext.core.data.LineTextData", false, loader);
            Class<?> point = Class.forName("com.oplus.supertext.core.data.TextPointData", false, loader);
            Class<?> view = Class.forName("com.oplus.supertext.core.view.supertext.w", false, loader);

            controllerModel = field(controller, "b"); controllerView = field(controller, "c"); downPoint = field(controller, "g");
            data = field(model, "h"); top = field(state, "mTextSwipedMaskTop"); bottom = field(state, "mTextSwipedMaskBottom");

            minX = point.getMethod("getMinX"); minY = point.getMethod("getMinY");
            maxX = point.getMethod("getMaxX"); maxY = point.getMethod("getMaxY");
            combined = field(state, "mTmpMask");
            lines = state.getMethod("getOriginalData"); points = line.getMethod("getTextPointDataList");
            imageLine = line.getMethod("isImageMark"); imagePoint = point.getMethod("isImageMark");
            pointText = point.getMethod("getText"); pointIndex = point.getMethod("getTextIndex");
            mask = state.getMethod("getSwipedMask"); rebuild = method(state, "updateSwipedArea", boolean.class, boolean.class);
            antPath = method(state, "buildAntPath");
            isImage = model.getMethod("b0", int.class);
            hitTest = model.getMethod("M", PointF.class, boolean.class); toModel = model.getMethod("a0", PointF.class);

            updateHandles = model.getMethod("q");
            limitTools = Class.forName("com.oplus.supertext.core.view.supertext.SuperTextView", false, loader)
                    .getMethod("setLimitTextToolShow", boolean.class);
            cursor = model.getMethod("c0", int.class, int.class); selectedText = model.getMethod("getSelectedText");

            listeners = view.getMethod("getSuperTextEventListeners");
            selectionChanged = Class.forName("lm.a", false, loader).getMethod("f", String.class);
            invalidate = view.getMethod("t");
            hideTools = view.getMethod("b", boolean.class);
        }
    }
}
