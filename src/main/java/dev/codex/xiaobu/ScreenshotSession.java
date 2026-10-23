package dev.codex.xiaobu;

/** Owns one capture generation and keeps accepted saves alive across UI teardown. */
final class ScreenshotSession<T> {
    interface Releaser<T> { void release(T value); }

    private final Releaser<T> releaser;
    private long generation;
    private boolean active;
    private boolean pending;
    private Frame<T> current;

    ScreenshotSession(Releaser<T> releaser) { this.releaser = releaser; }

    long beginCapture() {
        T discarded;
        long id;
        synchronized (this) {
            discarded = dropOwnerLocked();
            id = ++generation;
            active = true;
            pending = true;
        }
        release(discarded);
        return id;
    }

    /** Always takes ownership of value, including a result from an obsolete capture. */
    void publish(long id, T value) {
        boolean accepted = false;
        synchronized (this) {
            if (generation == id && active && pending) {
                pending = false;
                if (value != null) {
                    current = new Frame<T>(value);
                    accepted = true;
                }
            }
        }
        if (!accepted) release(value);
    }

    synchronized long generation() { return generation; }
    synchronized boolean isGeneration(long id) { return generation == id; }
    synchronized boolean hasImage(long id) {
        return active && generation == id && current != null;
    }
    synchronized boolean actionUnavailable(long id) {
        return !hasImage(id) || current.busy || current.completed;
    }

    /** Claims the only pending action for this image and pins its resource. */
    synchronized Action<T> beginAction(long id) {
        if (actionUnavailable(id)) return null;
        current.busy = true;
        current.references++;
        return new Action<T>(this, current);
    }

    void endCapture(long id) {
        T discarded = null;
        synchronized (this) {
            if (generation == id) {
                active = false;
                pending = false;
                discarded = dropOwnerLocked();
            }
        }
        release(discarded);
    }

    private T dropOwnerLocked() {
        Frame<T> frame = current;
        current = null;
        return frame != null && --frame.references == 0 ? frame.value : null;
    }

    private void finish(Action<T> action, boolean success) {
        T discarded = null;
        synchronized (this) {
            if (action.finished) return;
            action.finished = true;
            action.frame.busy = false;
            action.frame.completed |= success;
            if (--action.frame.references == 0) discarded = action.frame.value;
        }
        release(discarded);
    }

    private void release(T value) { if (value != null) releaser.release(value); }

    private static final class Frame<T> {
        final T value;
        int references = 1;
        boolean busy;
        boolean completed;
        Frame(T value) { this.value = value; }
    }

    static final class Action<T> {
        private final ScreenshotSession<T> owner;
        private final Frame<T> frame;
        private boolean finished;
        private Action(ScreenshotSession<T> owner, Frame<T> frame) {
            this.owner = owner;
            this.frame = frame;
        }
        T value() {
            synchronized (owner) {
                if (finished) throw new IllegalStateException("Screenshot action already released");
                return frame.value;
            }
        }
        void finish(boolean success) { owner.finish(this, success); }
    }
}
