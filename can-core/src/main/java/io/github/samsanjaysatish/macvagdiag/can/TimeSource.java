package io.github.samsanjaysatish.macvagdiag.can;

import java.time.Duration;
import java.time.Instant;

/**
 * Time for every timeout, interval and rate limit in every layer, so tests can run on a fake clock
 * ({@code FakeTimeSource} in this project's test fixtures) and never sleep.
 *
 * <p>Waiting goes through {@link #awaitUntil(Object, long)} instead of {@code Object.wait} or
 * {@code Thread.sleep}, so that on a fake clock advancing time wakes the waiter.
 */
public interface TimeSource {

    /** The real clock. */
    static TimeSource system() {
        return SystemTimeSource.INSTANCE;
    }

    /**
     * Monotonic time in nanoseconds. Never goes backwards and is never negative, so values can be
     * compared directly. Only differences are meaningful.
     */
    long nanoTime();

    /** Wall-clock time, for capture files and reports. Never use it for timeouts. */
    Instant now();

    /**
     * Waits on {@code monitor} until it is notified or {@link #nanoTime()} reaches {@code deadlineNanos}.
     * The caller must hold {@code monitor}'s lock ({@code synchronized (monitor)}), exactly as for
     * {@link Object#wait()}. Returns immediately if the deadline has passed. May return early (spurious
     * wakeups, clock advances that don't reach the deadline), so callers loop and recheck their condition.
     */
    void awaitUntil(Object monitor, long deadlineNanos) throws InterruptedException;

    /** Blocks until {@link #nanoTime()} reaches {@code deadlineNanos}. */
    default void sleepUntil(long deadlineNanos) throws InterruptedException {
        Object monitor = new Object();
        synchronized (monitor) {
            while (nanoTime() < deadlineNanos) {
                awaitUntil(monitor, deadlineNanos);
            }
        }
    }

    /** {@code nanoTime() + timeout}, saturating at {@link Long#MAX_VALUE} for huge timeouts. */
    default long deadlineAfter(Duration timeout) {
        long now = nanoTime();
        long nanos;
        try {
            nanos = timeout.toNanos();
        } catch (ArithmeticException tooLong) {
            return Long.MAX_VALUE;
        }
        long deadline = now + nanos;
        return (nanos > 0 && deadline < now) ? Long.MAX_VALUE : deadline;
    }
}
