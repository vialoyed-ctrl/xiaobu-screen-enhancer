package dev.codex.xiaobu.gesture;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** JVM regression checks for native-veto versus asynchronous final-check races. */
public final class PressGateTest {
    public static void main(String[] args) throws Exception {
        PressGate<PressGate.Attempt> gate = new PressGate<PressGate.Attempt>();
        PressGate.Attempt first = new PressGate.Attempt();
        gate.begin(first);
        require(gate.isCurrent(first), "new stationary press must be eligible");
        require(gate.veto(first), "native selection must cancel an armed press");
        require(!gate.commit(first), "provider completion must not revive native-vetoed press");
        gate.clear(first);
        require(gate.blocked(), "cancelled stream stays blocked until release");
        gate.release();
        require(!gate.blocked(), "all fingers released must reset block");

        PressGate.Attempt second = new PressGate.Attempt();
        gate.begin(second);
        gate.block();
        require(!gate.commit(second), "second finger or movement must prevent launch");
        gate.release();

        PressGate.Attempt old = new PressGate.Attempt();
        gate.begin(old);
        PressGate.Attempt replacement = new PressGate.Attempt();
        gate.begin(replacement);
        require(!gate.veto(old), "late native callback must not veto the replacement gesture");
        gate.clear(old);
        require(gate.isCurrent(replacement), "late cleanup must not clear replacement gesture");
        require(!gate.commit(old), "late provider result must not launch the old gesture");
        require(gate.commit(replacement), "replacement may launch once");
        require(!gate.commit(replacement), "duplicate provider callbacks must not launch twice");
        require(gate.blocked(), "launched gesture stays blocked until release");

        // The commit/veto boundary must have exactly one winner, even when native
        // accessibility delivery races the final configuration check.
        for (int i = 0; i < 100; i++) {
            gate.release();
            PressGate.Attempt press = new PressGate.Attempt();
            gate.begin(press);
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger winners = new AtomicInteger();
            Thread commit = new Thread(() -> {
                await(go);
                if (gate.commit(press)) winners.incrementAndGet();
            });
            Thread veto = new Thread(() -> {
                await(go);
                if (gate.veto(press)) winners.incrementAndGet();
            });
            commit.start();
            veto.start();
            go.countDown();
            commit.join();
            veto.join();
            require(winners.get() == 1, "commit/veto race must have exactly one winner");
            require(!gate.commit(press), "completed race must not allow a second launch");
        }
        System.out.println("PressGateTest: PASS (cancellation, stale callbacks, duplicate launch, 100 races)");
    }

    private static void await(CountDownLatch value) {
        try { value.await(); }
        catch (InterruptedException error) { throw new AssertionError(error); }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
