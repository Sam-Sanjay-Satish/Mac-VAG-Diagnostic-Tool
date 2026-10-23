package io.github.samsanjaysatish.macvagdiag.can;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.PriorityQueue;

/**
 * The receive queue shared by all adapters. Frames become available at a release time on the
 * {@link TimeSource} (now, or later for simulated delays and paced replays). Waiting uses
 * {@link TimeSource#awaitUntil}, so a frame being offered, a fake clock advance, {@link #close()} or
 * {@link #fail} each wake a blocked {@link #poll}.
 */
final class FrameQueue {

    private record Pending(CanFrame frame, long releaseNanos, Instant wallClock, long sequence) {
    }

    private static final Comparator<Pending> ORDER =
            Comparator.comparingLong(Pending::releaseNanos).thenComparingLong(Pending::sequence);

    private final TimeSource time;
    private final int capacity;
    private final Object lock = new Object();
    private final PriorityQueue<Pending> pending = new PriorityQueue<>(ORDER);
    private long nextSequence;
    private long dropped;
    private boolean closed;
    private RuntimeException failure;

    /** @param capacity frames held before new ones are dropped (counted in {@link #droppedCount()}) */
    FrameQueue(TimeSource time, int capacity) {
        this.time = time;
        this.capacity = capacity;
    }

    FrameQueue(TimeSource time) {
        this(time, Integer.MAX_VALUE);
    }

    /** Makes {@code frame} available now, stamped with the current time. */
    void offer(CanFrame frame) {
        schedule(frame, time.nanoTime(), null);
    }

    /**
     * Makes {@code frame} available once {@link TimeSource#nanoTime()} reaches {@code releaseNanos}.
     * It is stamped with {@code releaseNanos} and {@code wallClock}, or the time source's wall clock at
     * release when {@code wallClock} is null. Frames with equal release times come out in offer order.
     */
    void schedule(CanFrame frame, long releaseNanos, Instant wallClock) {
        synchronized (lock) {
            if (closed) {
                return;
            }
            if (pending.size() >= capacity) {
                dropped++;
                return;
            }
            pending.add(new Pending(frame, releaseNanos, wallClock, nextSequence++));
            lock.notifyAll();
        }
    }

    Optional<TimestampedFrame> poll(Duration timeout) throws InterruptedException {
        long deadline = time.deadlineAfter(timeout);
        synchronized (lock) {
            while (true) {
                if (closed) {
                    throw new CanAdapterException("Adapter closed while waiting for a frame");
                }
                long now = time.nanoTime();
                Pending head = pending.peek();
                if (head != null && head.releaseNanos() <= now) {
                    pending.remove();
                    Instant wall = head.wallClock() != null ? head.wallClock() : time.now();
                    return Optional.of(new TimestampedFrame(head.frame(), head.releaseNanos(), wall));
                }
                // Frames that arrived before a failure are still delivered first.
                if (failure != null) {
                    // A new exception per waiter, so each stack trace shows its own caller.
                    throw new CanAdapterException(failure.getMessage(), failure);
                }
                if (now >= deadline) {
                    return Optional.empty();
                }
                long wakeAt = head == null ? deadline : Math.min(deadline, head.releaseNanos());
                time.awaitUntil(lock, wakeAt);
            }
        }
    }

    /** Frames waiting, including ones not yet released. */
    int size() {
        synchronized (lock) {
            return pending.size();
        }
    }

    long droppedCount() {
        synchronized (lock) {
            return dropped;
        }
    }

    /** Wakes waiters, which then throw; later offers are ignored. */
    void close() {
        synchronized (lock) {
            closed = true;
            pending.clear();
            lock.notifyAll();
        }
    }

    /** Makes every current and future {@link #poll} throw {@code error}. */
    void fail(RuntimeException error) {
        synchronized (lock) {
            if (failure == null) {
                failure = error;
            }
            lock.notifyAll();
        }
    }
}
