package io.github.samsanjaysatish.macvagdiag.can;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A manually advanced clock for tests. Time only moves when the test calls {@link #advance}, and every
 * advance wakes threads waiting in {@link #awaitUntil}, so timeouts are tested instantly with no sleeping.
 *
 * <p>Two ways to use it:
 * <ul>
 *   <li><b>Manual</b> (default): a thread blocked in, say, {@code adapter.receive(1s)} stays blocked until
 *       a frame arrives or the test advances the clock past its deadline. For a multi-threaded test, call
 *       {@link #awaitWaiters(int)} first so the advance happens after the other thread started waiting.</li>
 *   <li><b>Auto-advance</b> ({@link #autoAdvancing()}): any wait jumps the clock straight to its deadline.
 *       Good for single-threaded tests ("receive times out after 500 ms") where nothing else could wake the
 *       waiter. Don't use it when another thread is meant to deliver something in real time.</li>
 * </ul>
 *
 * <p>Other projects get it with {@code testImplementation(testFixtures(project(":can-core")))}.
 */
public final class FakeTimeSource implements TimeSource {

    /** Default wall-clock start: 2026-01-01T00:00:00Z. */
    public static final Instant DEFAULT_WALL_START = Instant.parse("2026-01-01T00:00:00Z");

    private final AtomicLong nanos = new AtomicLong();
    private final Instant wallStart;
    private final boolean autoAdvance;
    /** Monitors that threads are waiting on right now (one entry per waiting thread). */
    private final List<Object> waiting = new CopyOnWriteArrayList<>();
    private final Object waiterSignal = new Object();

    public FakeTimeSource() {
        this(DEFAULT_WALL_START, false);
    }

    public FakeTimeSource(Instant wallStart) {
        this(wallStart, false);
    }

    private FakeTimeSource(Instant wallStart, boolean autoAdvance) {
        this.wallStart = wallStart;
        this.autoAdvance = autoAdvance;
    }

    /** A clock that jumps to each wait's deadline instead of blocking. */
    public static FakeTimeSource autoAdvancing() {
        return new FakeTimeSource(DEFAULT_WALL_START, true);
    }

    @Override
    public long nanoTime() {
        return nanos.get();
    }

    @Override
    public Instant now() {
        return wallStart.plusNanos(nanos.get());
    }

    /** Time since this clock was created. */
    public Duration elapsed() {
        return Duration.ofNanos(nanos.get());
    }

    /** Moves time forward and wakes every waiting thread so it can recheck its deadline. */
    public void advance(Duration amount) {
        if (amount.isNegative()) {
            throw new IllegalArgumentException("Time cannot go backwards: " + amount);
        }
        advanceTo(nanos.get() + amount.toNanos());
    }

    public void advanceMillis(long millis) {
        advance(Duration.ofMillis(millis));
    }

    private void advanceTo(long target) {
        // Set the time first, then notify: a waiter registers before reading the time, so it either sees
        // the new time or is registered and gets notified.
        nanos.accumulateAndGet(target, Math::max);
        for (Object monitor : waiting) {
            synchronized (monitor) {
                monitor.notifyAll();
            }
        }
    }

    @Override
    public void awaitUntil(Object monitor, long deadlineNanos) throws InterruptedException {
        if (!Thread.holdsLock(monitor)) {
            throw new IllegalMonitorStateException("awaitUntil requires holding the monitor's lock");
        }
        waiting.add(monitor);
        try {
            if (nanos.get() >= deadlineNanos) {
                return;
            }
            if (autoAdvance) {
                advanceTo(deadlineNanos);
                return;
            }
            synchronized (waiterSignal) {
                waiterSignal.notifyAll();
            }
            monitor.wait();
        } finally {
            waiting.remove(monitor);
        }
    }

    /** Number of threads currently blocked in {@link #awaitUntil}. */
    public int waiterCount() {
        return waiting.size();
    }

    /**
     * Blocks (in real time, at most 5 s) until at least {@code count} threads are waiting on this clock.
     * Test synchronisation only: lets a test advance the clock knowing the other thread is already waiting.
     *
     * @throws AssertionError if they don't show up in time
     */
    public void awaitWaiters(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        synchronized (waiterSignal) {
            while (waiting.size() < count) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new AssertionError("Expected " + count + " thread(s) waiting on the clock, found " + waiting.size());
                }
                TimeUnit.NANOSECONDS.timedWait(waiterSignal, Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(10)));
            }
        }
    }
}
