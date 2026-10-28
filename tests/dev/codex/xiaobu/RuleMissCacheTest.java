package dev.codex.xiaobu;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public final class RuleMissCacheTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        RuleMissCache cache = new RuleMissCache(60000L, 64);
        cache.response("app", "Page", 500, 100L);
        cache.response("app", "Page", -1400, 100L);
        check(!cache.contains("app", "Page", 100L), "Errors must never suppress a rule request");
        cache.response("app", "Page", -1401, 100L);
        check(cache.contains("app", "Page", 60099L), "Explicit no-match response should be reusable");
        check(!cache.contains("app", "Page", 60100L), "A hit must not extend the exact TTL boundary");
        cache.response("app", "Page", -1401, 70000L);
        cache.response("app", "Page", -1401, 80000L);
        check(cache.contains("app", "Page", 139999L), "A fresh server miss renews its own deadline");
        check(!cache.contains("app", "Page", 140000L), "Renewed response must still expire");
        cache.response("app", "Page", -1401, 150000L);
        cache.response("app", "Page", 200, 150001L);
        check(!cache.contains("app", "Page", 150001L), "A successful update invalidates a prior miss");
        cache.response("app", "Page", -1401, 160000L);
        check(!cache.contains("app", "OtherPage", 160000L), "Activities must have separate cache keys");
        check(!cache.contains("other.app", "Page", 160000L), "Packages must have separate cache keys");
        cache.response("a/b", "c", -1401, 160000L);
        check(!cache.contains("a", "b/c", 160000L), "A delimiter inside a key must not cause collisions");
        check(!cache.contains("app", "Page", 159999L), "Clock rollback must retry conservatively");
        cache.response(null, "Page", -1401, 160000L);
        cache.response("app", null, -1401, 160000L);
        cache.response("", "Page", -1401, 160000L);
        check(!cache.contains(null, "Page", 160000L), "Suspend-call null arguments are never hits");

        RuleMissCache bounded = new RuleMissCache(60000L, 64);
        for (int i = 0; i < 64; i++) bounded.response("app", "Page" + i, -1401, 0L);
        check(bounded.contains("app", "Page0", 1L), "Recent activity can retain its entry");
        bounded.response("app", "Page64", -1401, 1L);
        check(bounded.size(1L) == 64, "The cache must never exceed its configured capacity");
        check(bounded.contains("app", "Page0", 1L), "Recently used entries should survive eviction");
        check(!bounded.contains("app", "Page1", 1L), "Least recently used entry should be evicted");
        check(bounded.size(60001L) == 0, "Expired pages must be released");

        RuleMissCache saved = new RuleMissCache(60000L, 64);
        saved.response("app", "Page", -1401, 1000L);
        java.util.List<RuleMissCache.Entry> snapshot = saved.snapshot(2000L);
        RuleMissCache restarted = new RuleMissCache(60000L, 64);
        restarted.restore(snapshot, restarted.revision(), 3000L);
        check(restarted.contains("app", "Page", 60999L), "A process restart can reuse an unexpired miss");
        check(!restarted.contains("app", "Page", 61000L), "Restoring must retain the server response's deadline");
        RuleMissCache raced = new RuleMissCache(60000L, 64);
        long loadingRevision = raced.revision();
        raced.response("app", "Page", 200, 2500L);
        raced.restore(snapshot, loadingRevision, 3000L);
        check(!raced.contains("app", "Page", 3000L), "An update during disk load must not resurrect a stale miss");
        RuleMissCache clockRollback = new RuleMissCache(60000L, 64);
        clockRollback.restore(snapshot, clockRollback.revision(), 999L);
        check(clockRollback.size(999L) == 0, "Persisted entries from a future clock must be discarded");

        RuleMissCache concurrent = new RuleMissCache(60000L, 64);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread[] workers = new Thread[8];
        for (int worker = 0; worker < workers.length; worker++) {
            final int id = worker;
            workers[worker] = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 1000; i++) {
                        String page = "Worker" + id + "/Page" + i;
                        concurrent.response("app", page, -1401, 10L);
                        concurrent.contains("app", page, 11L);
                        if ((i & 1) == 0) concurrent.response("app", page, 200, 12L);
                        check(concurrent.size(12L) <= 64, "Concurrent responses must respect capacity");
                    }
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                }
            });
            workers[worker].start();
        }
        start.countDown();
        for (Thread worker : workers) worker.join();
        if (failure.get() != null) throw new AssertionError("Concurrent cache access failed", failure.get());
        concurrent.response("app", "Final", -1401, 20L);
        concurrent.response("app", "Final", 200, 21L);
        check(!concurrent.contains("app", "Final", 21L), "Success must still invalidate after concurrent use");
        System.out.println("RuleMissCacheTest: TTL, response refresh, key isolation, eviction and concurrency passed");
    }
}
