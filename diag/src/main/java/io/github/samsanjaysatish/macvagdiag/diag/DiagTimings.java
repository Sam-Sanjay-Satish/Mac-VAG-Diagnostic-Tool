package io.github.samsanjaysatish.macvagdiag.diag;

import java.time.Duration;
import java.util.Objects;

/**
 * How long this layer waits for answers, and the cap on {@code 7F xx 78} (response pending), so an ECU that
 * answers 78 forever can't hang the scan. All waits run on the {@code TimeSource} given to the service, so tests
 * use a fake clock.
 *
 * <p>The defaults are <b>UNCONFIRMED</b> (from memory of ISO 14229-2 / ISO 14230-2 defaults plus margin for the
 * USB adapter); correct them from VCDS's timing in the capture.
 *
 * @param requestTimeout from request sent to whole response received
 * @param pendingTimeout how long to wait for the next message after each {@code 78} (UDS P2*server)
 * @param maxPendingResponses how many {@code 78}s are accepted for one request; one more gives
 *     {@link DiagResult.PendingLimitExceeded}
 * @param maxTotalPendingWait total time from the first {@code 78} after which this layer gives up
 */
public record DiagTimings(Duration requestTimeout, Duration pendingTimeout, int maxPendingResponses,
        Duration maxTotalPendingWait) {

    /** UNCONFIRMED: ISO P2 is 50 ms; 1 s leaves room for TP 2.0 / ISO-TP segmentation and the USB adapter. */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofMillis(1000);
    /** UNCONFIRMED: ISO 14229-2 default P2*server, 5000 ms. Used for every wait after a {@code 78}. */
    public static final Duration RESPONSE_PENDING_TIMEOUT = Duration.ofMillis(5000);
    /** UNCONFIRMED: how many {@code 78}s one request may get before giving up. */
    public static final int MAX_RESPONSE_PENDING = 20;
    /** UNCONFIRMED: total time after the first {@code 78} before giving up. */
    public static final Duration MAX_TOTAL_PENDING_WAIT = Duration.ofSeconds(30);

    /** Hard ceiling on {@link #maxPendingResponses()}: no caller can configure more. */
    public static final int MAX_RESPONSE_PENDING_CEILING = 100;
    /** Hard ceiling on every duration here: no caller can configure a longer wait. */
    public static final Duration MAX_WAIT_CEILING = Duration.ofMinutes(2);

    public static final DiagTimings DEFAULTS = new DiagTimings(DEFAULT_REQUEST_TIMEOUT, RESPONSE_PENDING_TIMEOUT,
            MAX_RESPONSE_PENDING, MAX_TOTAL_PENDING_WAIT);

    public DiagTimings {
        checkDuration(requestTimeout, "requestTimeout");
        checkDuration(pendingTimeout, "pendingTimeout");
        checkDuration(maxTotalPendingWait, "maxTotalPendingWait");
        if (maxPendingResponses < 0 || maxPendingResponses > MAX_RESPONSE_PENDING_CEILING) {
            throw new IllegalArgumentException("maxPendingResponses must be 0.." + MAX_RESPONSE_PENDING_CEILING
                    + ": " + maxPendingResponses);
        }
    }

    private static void checkDuration(Duration d, String what) {
        Objects.requireNonNull(d, what);
        if (d.isNegative() || d.isZero() || d.compareTo(MAX_WAIT_CEILING) > 0) {
            throw new IllegalArgumentException(what + " must be > 0 and <= " + MAX_WAIT_CEILING + ": " + d);
        }
    }
}
