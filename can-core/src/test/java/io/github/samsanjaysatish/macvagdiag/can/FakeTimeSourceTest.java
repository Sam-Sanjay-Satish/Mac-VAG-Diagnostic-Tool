package io.github.samsanjaysatish.macvagdiag.can;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class FakeTimeSourceTest {

    @Test
    void timeMovesOnlyWhenAdvanced() {
        FakeTimeSource time = new FakeTimeSource(Instant.parse("2026-10-22T08:00:00Z"));
        assertEquals(0, time.nanoTime());
        assertEquals(Instant.parse("2026-10-22T08:00:00Z"), time.now());
        time.advance(Duration.ofMillis(1500));
        assertEquals(1_500_000_000L, time.nanoTime());
        assertEquals(Instant.parse("2026-10-22T08:00:01.500Z"), time.now());
        assertThrows(IllegalArgumentException.class, () -> time.advance(Duration.ofMillis(-1)));
    }

    @Test
    void advanceWakesSleeper() throws Exception {
        FakeTimeSource time = new FakeTimeSource();
        long deadline = time.deadlineAfter(Duration.ofSeconds(30));
        CompletableFuture<Void> slept = CompletableFuture.runAsync(() -> {
            try {
                time.sleepUntil(deadline);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        time.awaitWaiters(1);
        time.advance(Duration.ofSeconds(29));
        time.awaitWaiters(1);
        assertFalse(slept.isDone());
        time.advance(Duration.ofSeconds(1));
        slept.get();
        assertEquals(0, time.waiterCount());
    }

    @Test
    void autoAdvanceJumpsToTheDeadline() throws Exception {
        FakeTimeSource time = FakeTimeSource.autoAdvancing();
        time.sleepUntil(time.deadlineAfter(Duration.ofMinutes(5)));
        assertEquals(Duration.ofMinutes(5), time.elapsed());
    }

    @Test
    void awaitUntilRequiresTheMonitor() {
        FakeTimeSource time = new FakeTimeSource();
        assertThrows(IllegalMonitorStateException.class, () -> time.awaitUntil(new Object(), 10));
    }

    @Test
    void awaitUntilPastDeadlineReturnsImmediately() throws Exception {
        FakeTimeSource time = new FakeTimeSource();
        time.advanceMillis(10);
        Object monitor = new Object();
        synchronized (monitor) {
            time.awaitUntil(monitor, 5);
        }
        assertEquals(0, time.waiterCount());
    }

    @Test
    void deadlineAfterSaturates() {
        FakeTimeSource time = new FakeTimeSource();
        time.advanceMillis(1);
        assertEquals(Long.MAX_VALUE, time.deadlineAfter(Duration.ofSeconds(Long.MAX_VALUE)));
        assertEquals(Long.MAX_VALUE, time.deadlineAfter(Duration.ofNanos(Long.MAX_VALUE)));
        assertEquals(1_000_000L, time.deadlineAfter(Duration.ZERO));
    }

    @Test
    void systemTimeSourceIsMonotonicAndNonNegative() {
        TimeSource system = TimeSource.system();
        long a = system.nanoTime();
        long b = system.nanoTime();
        assertTrue(a >= 0 && b >= a);
    }
}
