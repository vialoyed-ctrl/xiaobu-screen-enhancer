package dev.codex.xiaobu;

import java.util.ArrayList;
import java.util.List;

/** Runs on a regular JDK; exercises ownership and stale/duplicate action races. */
public final class ScreenshotSessionTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        List<String> released = new ArrayList<String>();
        ScreenshotSession<String> sessions = new ScreenshotSession<String>(released::add);
        long first = sessions.beginCapture();
        sessions.publish(first, "first");
        ScreenshotSession.Action<String> saving = sessions.beginAction(first);
        check(saving != null, "First save must pin its image");
        check(sessions.beginAction(first) == null, "Repeated tap must not start another save");
        long next = sessions.beginCapture();
        check(!sessions.hasImage(first), "New capture must invalidate old UI immediately");
        check(released.isEmpty(), "An accepted save must survive capture replacement");
        check("first".equals(saving.value()), "The save must retain the image it selected");
        sessions.publish(next, null);
        check(sessions.beginAction(next) == null, "Failed capture must never borrow an older image");
        saving.finish(true);
        saving.finish(true);
        check(released.size() == 1, "Release must be idempotent");

        long stale = sessions.beginCapture();
        long live = sessions.beginCapture();
        sessions.publish(stale, "stale");
        check(released.contains("stale"), "Out-of-order capture must be disposed");
        check(!sessions.hasImage(live), "Stale completion must not fill the current session");
        sessions.publish(live, "live");
        ScreenshotSession.Action<String> rejected = sessions.beginAction(live);
        rejected.finish(false);
        ScreenshotSession.Action<String> retry = sessions.beginAction(live);
        check(retry != null, "Queue rejection/save failure must permit a retry");
        retry.finish(true);
        check(sessions.beginAction(live) == null, "Success must prevent duplicate save and launch");
        sessions.endCapture(stale);
        check(sessions.hasImage(live), "Old activity teardown must not discard the new session");
        sessions.endCapture(live);
        check(released.contains("live"), "Closing UI must release an unborrowed current image");

        long closed = sessions.beginCapture();
        sessions.endCapture(closed);
        sessions.publish(closed, "late");
        check(released.contains("late"), "A capture completing after UI teardown must be disposed");

        long concurrent = sessions.beginCapture();
        sessions.publish(concurrent, "concurrent");
        ScreenshotSession.Action<String> action = sessions.beginAction(concurrent);
        Thread finish = new Thread(() -> action.finish(true));
        Thread close = new Thread(() -> sessions.endCapture(concurrent));
        finish.start(); close.start(); finish.join(); close.join();
        check(java.util.Collections.frequency(released, "concurrent") == 1,
                "Save completion and UI teardown must release exactly once");
        System.out.println("ScreenshotSessionTest: all ownership, stale result and duplicate action checks passed");
    }
}
