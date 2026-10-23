package io.github.samsanjaysatish.macvagdiag.scan;

import io.github.samsanjaysatish.macvagdiag.can.TimeSource;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Optional;

/**
 * The request budgets: per module per scan, and a sliding one-minute window over everything sent to the car.
 * Requests are reserved before each operation (its worst-case count, {@link DiagOp#cost}); if the reservation
 * doesn't fit, nothing is sent. All on the {@link TimeSource}.
 */
final class RequestBudget {

    static final Duration WINDOW = Duration.ofMinutes(1);

    private final TimeSource time;
    private final ScanLimits limits;
    /** Monotonic times of reserved requests within the window, oldest first. */
    private final ArrayDeque<Long> window = new ArrayDeque<>();

    RequestBudget(TimeSource time, ScanLimits limits) {
        this.time = time;
        this.limits = limits;
    }

    /** Reserves {@code cost} requests to {@code module}, or throws naming the limit. Nothing is reserved on failure. */
    synchronized void reserve(ScanContext ctx, ModuleAddress module, int cost) throws LimitReachedException {
        if (cost == 0) {
            return;
        }
        int used = ctx.requests(module);
        if (used + cost > limits.maxRequestsPerModulePerScan()) {
            throw new LimitReachedException(new LimitHit(Limit.REQUESTS_PER_MODULE_PER_SCAN, Duration.ZERO,
                    String.format("request budget used up: at most %d requests to module %s in one scan "
                            + "(%d used); the rest of this module was not read. A new scan can read it",
                            limits.maxRequestsPerModulePerScan(), module.hex(), used)));
        }
        Optional<LimitHit> hit = checkWindow(cost);
        if (hit.isPresent()) {
            throw new LimitReachedException(hit.get());
        }
        long now = time.nanoTime();
        for (int i = 0; i < cost; i++) {
            window.addLast(now);
        }
        ctx.charge(module, cost);
    }

    /** Whether {@code cost} more requests fit into the one-minute window now. */
    synchronized Optional<LimitHit> checkWindow(int cost) {
        long now = time.nanoTime();
        prune(now);
        int max = limits.maxRequestsPerMinute();
        int over = window.size() + cost - max;
        if (over <= 0) {
            return Optional.empty();
        }
        // The over-th oldest reservation has to leave the window first.
        long freesAt = Long.MAX_VALUE;
        Iterator<Long> it = window.iterator();
        for (int i = 0; i < over && it.hasNext(); i++) {
            freesAt = it.next() + WINDOW.toNanos();
        }
        Duration retry = freesAt == Long.MAX_VALUE ? WINDOW : Duration.ofNanos(Math.max(0, freesAt - now));
        return Optional.of(new LimitHit(Limit.REQUESTS_PER_MINUTE, retry, String.format(
                "rate limit hit: at most %d requests per minute to the car; retry after %s", max,
                LimitHit.seconds(retry))));
    }

    synchronized int usedInWindow() {
        prune(time.nanoTime());
        return window.size();
    }

    private void prune(long now) {
        long cutoff = now - WINDOW.toNanos();
        while (!window.isEmpty() && window.peekFirst() <= cutoff) {
            window.removeFirst();
        }
    }
}
