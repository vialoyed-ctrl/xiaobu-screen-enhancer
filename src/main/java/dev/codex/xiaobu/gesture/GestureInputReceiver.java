package dev.codex.xiaobu.gesture;

import android.os.Looper;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.MotionEvent;

/** Owns the monitor channel on the backend's dedicated looper. */
final class GestureInputReceiver extends InputEventReceiver {
    private final LongPressBackend owner;

    GestureInputReceiver(InputChannel channel, Looper looper, LongPressBackend owner) {
        super(channel, looper);
        this.owner = owner;
    }

    @Override public void onInputEvent(InputEvent event) {
        try {
            if (event instanceof MotionEvent) owner.onMotionEvent((MotionEvent) event);
        } catch (Throwable error) {
            owner.onInputFailure(error);
        } finally {
            // Monitoring does not consume application events. Pilfering is explicit and
            // happens only after the long press has passed every final check.
            try { finishInputEvent(event, false); }
            catch (Throwable error) { owner.onInputFailure(error); }
        }
    }
}
