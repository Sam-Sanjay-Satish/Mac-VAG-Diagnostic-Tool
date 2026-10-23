package io.github.samsanjaysatish.macvagdiag.can;

import java.time.Instant;
import java.util.Objects;

/**
 * A received frame with its receive time.
 *
 * @param frame the frame
 * @param monotonicNanos {@link TimeSource#nanoTime()} at receipt: use this for intervals and timeouts
 * @param wallClock wall-clock time at receipt: use this for capture files and reports
 */
public record TimestampedFrame(CanFrame frame, long monotonicNanos, Instant wallClock) {

    public TimestampedFrame {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(wallClock, "wallClock");
    }
}
