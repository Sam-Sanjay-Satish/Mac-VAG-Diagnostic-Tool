package io.github.samsanjaysatish.macvagdiag.can;

import java.time.Instant;
import java.util.Objects;

/**
 * One frame line of a capture file.
 *
 * @param timestamp wall-clock time of the frame (microsecond resolution in the file)
 * @param interfaceName the candump interface column, e.g. {@code can0}
 * @param frame the frame
 */
public record CapturedFrame(Instant timestamp, String interfaceName, CanFrame frame) {

    public CapturedFrame {
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(interfaceName, "interfaceName");
        Objects.requireNonNull(frame, "frame");
    }
}
