package dev.codex.xiaobu.gesture;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/** Independent bottom touch surface, including when navigation UI is hidden. */
final class NavigationTapShield {
    private final WindowManager windows;
    private final View view;
    private final Rect bounds = new Rect();
    private boolean attached;

    NavigationTapShield(Context context) {
        windows = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        view = new View(context) {
            @Override public boolean onTouchEvent(MotionEvent event) { return true; }
        };
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    boolean contains(float x, float y) {
        return attached && bounds.contains((int) x, (int) y);
    }

    void update(Rect target) {
        if (target == null) { remove(); return; }
        if (attached && bounds.equals(target)) return;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                // Independent system_server window, not the navigation bar itself.
                // TYPE_SYSTEM_ERROR suppresses lower windows' brightness overrides.
                // A navigation panel stays above applications without that policy.
                target.width(), target.height(), 2024 /* TYPE_NAVIGATION_BAR_PANEL */,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = target.left;
        params.y = target.top;
        params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        params.buttonBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        params.setTitle("XiaobuNavigationTapShield");
        params.layoutInDisplayCutoutMode = 3 /* ALWAYS */;
        try {
            GestureSystemHook.method(params.getClass(), "setFitInsetsTypes", int.class).invoke(params, 0);
            if (attached) windows.updateViewLayout(view, params);
            else { windows.addView(view, params); attached = true; }
            bounds.set(target);
        } catch (Throwable error) {
            remove();
            throw new IllegalStateException("Navigation touch surface unavailable", error);
        }
    }

    void remove() {
        if (!attached) return;
        attached = false;
        bounds.setEmpty();
        try { windows.removeViewImmediate(view); }
        catch (Throwable error) { GestureSystemHook.log("navigation surface removal isolated: " + error); }
    }
}
