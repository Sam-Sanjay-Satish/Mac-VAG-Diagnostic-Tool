package io.github.samsanjaysatish.macvagdiag.scan;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link ScanService} refused an operation and <b>sent nothing to the car</b> for it. The message is written for
 * the end user or an AI agent: it says what happened and what to do next.
 *
 * <p>Results from the car (a module that didn't answer, a negative response) are not exceptions: they come back
 * in the result types. This exception is only for "not attempted".
 */
public final class ScanRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Reason {
        /** The CAN adapter isn't connected or couldn't be opened. */
        ADAPTER_NOT_CONNECTED,
        /** A rate limit or budget; see {@link #limit()} and {@link #retryAfter()}. */
        LIMIT_EXCEEDED,
        /** The module didn't respond moments ago and is in back-off; see {@link #retryAfter()}. */
        MODULE_NOT_RESPONDING,
        /** Another Auto-Scan is running. */
        SCAN_IN_PROGRESS,
        /** The service has been closed (app shutting down). */
        SERVICE_CLOSED
    }

    private final Reason reason;
    private final Limit limit; // nullable
    private final Duration retryAfter; // nullable
    private final String moduleHex; // nullable

    private ScanRefusedException(Reason reason, String message, Limit limit, Duration retryAfter, String moduleHex) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
        this.limit = limit;
        this.retryAfter = retryAfter;
        this.moduleHex = moduleHex;
    }

    public static ScanRefusedException adapterNotConnected(String detail) {
        return new ScanRefusedException(Reason.ADAPTER_NOT_CONNECTED,
                "adapter not connected: ask the user to plug in the CANable and switch the ignition on (" + detail + ")",
                null, null, null);
    }

    public static ScanRefusedException limitExceeded(LimitHit hit) {
        return new ScanRefusedException(Reason.LIMIT_EXCEEDED, hit.message(), hit.limit(), hit.retryAfter(), null);
    }

    public static ScanRefusedException moduleNotResponding(ModuleAddress module, Duration retryAfter, String lastFailure) {
        return new ScanRefusedException(Reason.MODULE_NOT_RESPONDING,
                "module " + module.hex() + " (" + module.name() + ") not responding: " + lastFailure
                        + ". Not asked again yet; retry after " + LimitHit.seconds(retryAfter),
                Limit.MODULE_NOT_RESPONDING_BACKOFF, retryAfter, module.hex());
    }

    public static ScanRefusedException scanInProgress(String runningScanId) {
        return new ScanRefusedException(Reason.SCAN_IN_PROGRESS, "an Auto-Scan is already running (" + runningScanId
                + "); wait for it to finish, then retry", Limit.ONE_AUTO_SCAN_AT_A_TIME, null, null);
    }

    public static ScanRefusedException serviceClosed() {
        return new ScanRefusedException(Reason.SERVICE_CLOSED, "the scan service is shut down", null, null, null);
    }

    public Reason reason() {
        return reason;
    }

    public Optional<Limit> limit() {
        return Optional.ofNullable(limit);
    }

    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }

    public Optional<ModuleAddress> module() {
        return moduleHex == null ? Optional.empty() : ModuleAddress.tryParse(moduleHex);
    }
}
