package dev.codex.xiaobu.settings;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;
import dev.codex.xiaobu.gesture.NativeActionPolicy;

/** Event-only observer: never requests a node tree, touch exploration, or gesture injection. */
public final class GestureObserverService extends AccessibilityService {
    private static volatile boolean connected;
    private static volatile long lastActionPopupTime;
    private static volatile String lastActionPopupPackage = "";

    public static boolean isConnected() { return connected; }
    static long lastActionPopupTime() { return lastActionPopupTime; }
    static String lastActionPopupPackage() { return lastActionPopupPackage; }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        updateConnected(true);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && NativeActionPolicy.isActionPopup(event.getClassName())) {
            // Only event metadata is retained. In particular, do not inspect event.getText()/getSource().
            CharSequence packageName = event.getPackageName();
            lastActionPopupPackage = packageName == null ? "" : packageName.toString();
            lastActionPopupTime = event.getEventTime();
        }
    }

    @Override public void onInterrupt() {
        // No ongoing feedback or injected gesture to cancel.
    }

    @Override public boolean onUnbind(Intent intent) {
        updateConnected(false);
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        updateConnected(false);
        super.onDestroy();
    }

    private void updateConnected(boolean value) {
        if (connected == value) return;
        connected = value;
        GestureConfig.notifyChanged(this);
    }
}
