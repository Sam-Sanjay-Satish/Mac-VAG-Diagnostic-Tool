package io.github.samsanjaysatish.macvagdiag.scan;

import java.time.Duration;
import java.util.Objects;

/**
 * A limit stopped something; nothing was sent for it.
 *
 * @param limit which limit
 * @param retryAfter when trying again can succeed ({@link Duration#ZERO}: a new scan can go ahead now)
 * @param message readable and actionable, e.g. {@code rate limit hit: at most 400 requests per minute to the car;
 *     retry after 12 s}
 */
public record LimitHit(Limit limit, Duration retryAfter, String message) {

    public LimitHit {
        Objects.requireNonNull(limit, "limit");
        Objects.requireNonNull(retryAfter, "retryAfter");
        Objects.requireNonNull(message, "message");
        if (retryAfter.isNegative()) {
            throw new IllegalArgumentException("negative retryAfter: " + retryAfter);
        }
    }

    /** Whole seconds, rounded up: {@code 12 s}. */
    static String seconds(Duration d) {
        long s = d.toSeconds() + (d.toNanosPart() > 0 ? 1 : 0);
        return s + " s";
    }
}
