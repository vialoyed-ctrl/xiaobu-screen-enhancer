package dev.codex.xiaobu.gesture;

/** Identity-based cancellation shared by the input looper and native event hooks. */
final class PressGate<T extends PressGate.Attempt> {
    static class Attempt { volatile boolean cancelled; }

    private volatile T current;
    private volatile boolean blocked;

    T current() { return current; }
    boolean blocked() { return blocked; }

    synchronized void begin(T value) {
        if (current != null) current.cancelled = true;
        current = value;
        blocked = false;
    }

    synchronized boolean veto(T expected) {
        if (current != expected || expected == null || expected.cancelled) return false;
        expected.cancelled = true;
        blocked = true;
        return true;
    }

    synchronized void clear(T expected) {
        if (expected != null) expected.cancelled = true;
        if (current == expected) current = null;
    }

    synchronized void block() {
        if (current != null) current.cancelled = true;
        blocked = true;
    }

    synchronized void release() {
        if (current != null) current.cancelled = true;
        current = null;
        blocked = false;
    }

    synchronized boolean isCurrent(T expected) {
        return current == expected && expected != null && !expected.cancelled && !blocked;
    }

    /** A late callback cannot commit twice, or commit a replacement gesture. */
    synchronized boolean commit(T expected) {
        if (!isCurrent(expected)) return false;
        current = null;
        blocked = true;
        return true;
    }
}
