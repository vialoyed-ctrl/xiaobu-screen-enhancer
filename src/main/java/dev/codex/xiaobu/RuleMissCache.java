package dev.codex.xiaobu;

import java.util.Iterator;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** Short-lived negative responses, keyed by both package and activity. */
final class RuleMissCache {
    static final int NO_MATCH = -1401;
    private final long ttlMs;
    private final int capacity;
    private final LinkedHashMap<Key, Long> misses = new LinkedHashMap<Key, Long>(16, 0.75f, true);
    private long revision;

    RuleMissCache(long ttlMs, int capacity) {
        if (ttlMs <= 0L || capacity <= 0) throw new IllegalArgumentException("Invalid cache limits");
        this.ttlMs = ttlMs;
        this.capacity = capacity;
    }

    synchronized void response(String packageName, String activityName, int code, long nowMs) {
        if (!valid(packageName, activityName)) return;
        Key key = new Key(packageName, activityName);
        if (code == 200) {
            misses.remove(key);
            revision++;
        } else if (code == NO_MATCH) {
            purge(nowMs);
            misses.put(key, nowMs);
            if (misses.size() > capacity) misses.remove(misses.keySet().iterator().next());
            revision++;
        }
        // Transport errors, timeouts and unknown server responses never create misses.
    }

    synchronized boolean contains(String packageName, String activityName, long nowMs) {
        if (!valid(packageName, activityName)) return false;
        Key key = new Key(packageName, activityName);
        Long recordedMs = misses.get(key);
        if (recordedMs == null) return false;
        if (expired(recordedMs, nowMs)) {
            misses.remove(key);
            return false;
        }
        // A hit must not extend the deadline: active users also retry after the TTL.
        return true;
    }

    synchronized int size(long nowMs) {
        purge(nowMs);
        return misses.size();
    }

    synchronized long revision() { return revision; }

    synchronized List<Entry> snapshot(long nowMs) {
        purge(nowMs);
        ArrayList<Entry> result = new ArrayList<Entry>(misses.size());
        for (Map.Entry<Key, Long> entry : misses.entrySet()) {
            result.add(new Entry(entry.getKey().packageName, entry.getKey().activityName, entry.getValue()));
        }
        return result;
    }

    synchronized void restore(List<Entry> entries, long expectedRevision, long nowMs) {
        // A response arriving while disk is loading takes precedence over old persisted data.
        if (revision != expectedRevision || entries.size() > capacity) return;
        purge(nowMs);
        for (Entry entry : entries) {
            if (valid(entry.packageName, entry.activityName) && !expired(entry.recordedMs, nowMs)) {
                misses.put(new Key(entry.packageName, entry.activityName), entry.recordedMs);
                if (misses.size() > capacity) misses.remove(misses.keySet().iterator().next());
            }
        }
    }

    static final class Entry {
        final String packageName;
        final String activityName;
        final long recordedMs;
        Entry(String packageName, String activityName, long recordedMs) {
            this.packageName = packageName;
            this.activityName = activityName;
            this.recordedMs = recordedMs;
        }
    }

    private void purge(long nowMs) {
        Iterator<Map.Entry<Key, Long>> iterator = misses.entrySet().iterator();
        while (iterator.hasNext()) {
            if (expired(iterator.next().getValue(), nowMs)) iterator.remove();
        }
    }

    private boolean expired(long recordedMs, long nowMs) {
        return recordedMs < 0L || nowMs < recordedMs || nowMs - recordedMs >= ttlMs;
    }

    private static boolean valid(String packageName, String activityName) {
        return packageName != null && !packageName.isEmpty()
                && activityName != null && !activityName.isEmpty();
    }

    private static final class Key {
        private final String packageName;
        private final String activityName;

        Key(String packageName, String activityName) {
            this.packageName = packageName;
            this.activityName = activityName;
        }

        @Override public int hashCode() {
            return 31 * packageName.hashCode() + activityName.hashCode();
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key key = (Key) other;
            return packageName.equals(key.packageName) && activityName.equals(key.activityName);
        }
    }
}
