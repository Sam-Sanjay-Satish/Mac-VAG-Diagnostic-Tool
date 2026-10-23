package io.github.samsanjaysatish.macvagdiag.can;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/** The real clock: {@link System#nanoTime()} relative to class load, and {@link Instant#now()}. */
final class SystemTimeSource implements TimeSource {

    static final SystemTimeSource INSTANCE = new SystemTimeSource();

    private final long origin = System.nanoTime();

    private SystemTimeSource() {
    }

    @Override
    public long nanoTime() {
        return System.nanoTime() - origin;
    }

    @Override
    public Instant now() {
        return Instant.now();
    }

    @Override
    public void awaitUntil(Object monitor, long deadlineNanos) throws InterruptedException {
        long remaining = deadlineNanos - nanoTime();
        if (remaining > 0) {
            TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
        }
    }
}
