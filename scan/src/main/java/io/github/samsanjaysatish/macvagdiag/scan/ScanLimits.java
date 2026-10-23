package io.github.samsanjaysatish.macvagdiag.scan;

import java.time.Duration;
import java.util.Objects;

/**
 * Pacing, keep-alive, retry and budget settings. {@code scan} enforces these whatever a caller asks; callers have
 * no way to change them per request. The app picks them once at startup ({@link ScanOptions}), and even that is
 * clamped: every value has a hard floor and ceiling below, so configuration can't switch pacing or budgets off.
 *
 * <p><b>Every default is UNCONFIRMED.</b> There is no capture of VCDS's pacing yet, so the defaults are
 * conservative choices from the standards (from memory), explained on each constant. Correct them from the
 * capture (root open question "VCDS pacing and Tester Present timing").
 *
 * @param minRequestGap minimum time from the end of one exchange with the car to the start of the next
 * @param testerPresentInterval how long a diagnostic session may be idle before scan sends Tester Present
 * @param channelKeepAliveInterval how long a TP 2.0 channel may be idle before scan sends a keep-alive ({@code A3})
 * @param maxRetries how many times one operation is repeated after a timeout or {@code 7F xx 21} (busy)
 * @param initialBackoff the wait before the first retry; doubled for each further retry
 * @param maxBackoff the longest single back-off wait
 * @param maxConsecutiveFailures failed operations (after their retries) in a row after which a module is given up
 *     for the rest of the scan and reported as not responding
 * @param notRespondingBackoff how long a module that didn't respond is not asked again
 * @param maxRequestsPerModulePerScan request budget per module in one scan (one top-level call)
 * @param maxRequestsPerMinute requests to the car per minute overall, sliding window
 * @param connectAttempts channel setups tried per link before moving on to the next link
 * @param moduleListCacheTtl how long {@link ScanService#listModules} answers from the last probe without asking
 *     the car again
 */
public record ScanLimits(Duration minRequestGap, Duration testerPresentInterval, Duration channelKeepAliveInterval,
        int maxRetries, Duration initialBackoff, Duration maxBackoff, int maxConsecutiveFailures,
        Duration notRespondingBackoff, int maxRequestsPerModulePerScan, int maxRequestsPerMinute,
        int connectAttempts, Duration moduleListCacheTtl) {

    /**
     * UNCONFIRMED: 100 ms. KWP2000's default P3min (ISO 14230, gap between a response and the next request) is
     * 55 ms; 100 ms leaves margin for the USB adapter and slow modules. VCDS is probably faster.
     */
    public static final Duration DEFAULT_MIN_REQUEST_GAP = Duration.ofMillis(100);
    /**
     * UNCONFIRMED: 2 s. A UDS non-default session ends after S3server = 5 s without a request, and KWP's default
     * P3max is 5 s; ISO 14229-2 has the tester send Tester Present at about 2 s. 2 s gives 2.5x margin.
     */
    public static final Duration DEFAULT_TESTER_PRESENT_INTERVAL = Duration.ofSeconds(2);
    /**
     * UNCONFIRMED: 1 s. How long a TP 2.0 channel stays up without traffic isn't known; from memory VCDS sends
     * {@code A3} about once a second when idle. More often than needed is harmless (it carries no request).
     */
    public static final Duration DEFAULT_CHANNEL_KEEP_ALIVE_INTERVAL = Duration.ofSeconds(1);
    /** UNCONFIRMED: 2 retries, so at most 3 tries of one operation. */
    public static final int DEFAULT_MAX_RETRIES = 2;
    /** UNCONFIRMED: 500 ms, then 1 s: a busy module gets time to finish what it is doing. */
    public static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofMillis(500);
    /** UNCONFIRMED: 4 s. */
    public static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(4);
    /** UNCONFIRMED: 2 failed operations in a row (each already retried). */
    public static final int DEFAULT_MAX_CONSECUTIVE_FAILURES = 2;
    /** UNCONFIRMED: 5 s; long enough to stop a caller hammering an absent module, short enough to retry soon. */
    public static final Duration DEFAULT_NOT_RESPONDING_BACKOFF = Duration.ofSeconds(5);
    /**
     * UNCONFIRMED: 60. One module costs about 1 session + 2..6 identification + 1 DTC read + 2 per UDS freeze frame
     * + Tester Present + retries; 60 covers about 20 UDS faults with freeze frames.
     */
    public static final int DEFAULT_MAX_REQUESTS_PER_MODULE_PER_SCAN = 60;
    /**
     * UNCONFIRMED: 400. Above one full Auto-Scan of the known list (roughly 100-250 requests expected), so a scan
     * isn't cut short, but it stops a runaway caller (e.g. an AI looping over read_fault_codes).
     */
    public static final int DEFAULT_MAX_REQUESTS_PER_MINUTE = 400;
    /** UNCONFIRMED: 2 setups per link, so one lost frame doesn't make a module look absent. */
    public static final int DEFAULT_CONNECT_ATTEMPTS = 2;
    /** 60 s: module presence doesn't change while the car is on. */
    public static final Duration DEFAULT_MODULE_LIST_CACHE_TTL = Duration.ofSeconds(60);

    // Hard bounds: no configuration can go outside these.
    public static final Duration MIN_REQUEST_GAP_FLOOR = Duration.ofMillis(20);
    public static final Duration MIN_REQUEST_GAP_CEILING = Duration.ofSeconds(5);
    public static final Duration TESTER_PRESENT_INTERVAL_FLOOR = Duration.ofMillis(500);
    /** Below the 5 s S3server / P3max session timeout. */
    public static final Duration TESTER_PRESENT_INTERVAL_CEILING = Duration.ofSeconds(4);
    public static final Duration CHANNEL_KEEP_ALIVE_INTERVAL_FLOOR = Duration.ofMillis(200);
    public static final Duration CHANNEL_KEEP_ALIVE_INTERVAL_CEILING = Duration.ofSeconds(4);
    public static final int MAX_RETRIES_CEILING = 3;
    public static final Duration BACKOFF_FLOOR = Duration.ofMillis(50);
    public static final Duration BACKOFF_CEILING = Duration.ofSeconds(30);
    public static final int MAX_CONSECUTIVE_FAILURES_CEILING = 5;
    public static final Duration NOT_RESPONDING_BACKOFF_FLOOR = Duration.ofSeconds(1);
    public static final Duration NOT_RESPONDING_BACKOFF_CEILING = Duration.ofMinutes(5);
    public static final int REQUESTS_PER_MODULE_FLOOR = 5;
    public static final int REQUESTS_PER_MODULE_CEILING = 200;
    public static final int REQUESTS_PER_MINUTE_FLOOR = 10;
    public static final int REQUESTS_PER_MINUTE_CEILING = 1000;
    public static final int CONNECT_ATTEMPTS_CEILING = 3;
    public static final Duration MODULE_LIST_CACHE_TTL_CEILING = Duration.ofMinutes(10);

    public static final ScanLimits DEFAULTS = builder().build();

    public ScanLimits {
        between(minRequestGap, MIN_REQUEST_GAP_FLOOR, MIN_REQUEST_GAP_CEILING, "minRequestGap");
        between(testerPresentInterval, TESTER_PRESENT_INTERVAL_FLOOR, TESTER_PRESENT_INTERVAL_CEILING,
                "testerPresentInterval");
        between(channelKeepAliveInterval, CHANNEL_KEEP_ALIVE_INTERVAL_FLOOR, CHANNEL_KEEP_ALIVE_INTERVAL_CEILING,
                "channelKeepAliveInterval");
        between(maxRetries, 0, MAX_RETRIES_CEILING, "maxRetries");
        between(initialBackoff, BACKOFF_FLOOR, BACKOFF_CEILING, "initialBackoff");
        between(maxBackoff, initialBackoff, BACKOFF_CEILING, "maxBackoff");
        between(maxConsecutiveFailures, 1, MAX_CONSECUTIVE_FAILURES_CEILING, "maxConsecutiveFailures");
        between(notRespondingBackoff, NOT_RESPONDING_BACKOFF_FLOOR, NOT_RESPONDING_BACKOFF_CEILING,
                "notRespondingBackoff");
        between(maxRequestsPerModulePerScan, REQUESTS_PER_MODULE_FLOOR, REQUESTS_PER_MODULE_CEILING,
                "maxRequestsPerModulePerScan");
        between(maxRequestsPerMinute, REQUESTS_PER_MINUTE_FLOOR, REQUESTS_PER_MINUTE_CEILING, "maxRequestsPerMinute");
        between(connectAttempts, 1, CONNECT_ATTEMPTS_CEILING, "connectAttempts");
        between(moduleListCacheTtl, Duration.ZERO, MODULE_LIST_CACHE_TTL_CEILING, "moduleListCacheTtl");
    }

    private static void between(Duration d, Duration min, Duration max, String what) {
        Objects.requireNonNull(d, what);
        if (d.compareTo(min) < 0 || d.compareTo(max) > 0) {
            throw new IllegalArgumentException(what + " must be between " + min + " and " + max + ": " + d);
        }
    }

    private static void between(int v, int min, int max, String what) {
        if (v < min || v > max) {
            throw new IllegalArgumentException(what + " must be between " + min + " and " + max + ": " + v);
        }
    }

    /** The back-off before retry number {@code retry} (1-based): initial, doubled each time, capped. */
    public Duration backoff(int retry) {
        if (retry < 1) {
            throw new IllegalArgumentException("retry must be >= 1: " + retry);
        }
        Duration d = initialBackoff;
        for (int i = 1; i < retry && d.compareTo(maxBackoff) < 0; i++) {
            d = d.multipliedBy(2);
        }
        return d.compareTo(maxBackoff) > 0 ? maxBackoff : d;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    /** Starts from the defaults; {@link #build()} checks the bounds. */
    public static final class Builder {
        private Duration minRequestGap = DEFAULT_MIN_REQUEST_GAP;
        private Duration testerPresentInterval = DEFAULT_TESTER_PRESENT_INTERVAL;
        private Duration channelKeepAliveInterval = DEFAULT_CHANNEL_KEEP_ALIVE_INTERVAL;
        private int maxRetries = DEFAULT_MAX_RETRIES;
        private Duration initialBackoff = DEFAULT_INITIAL_BACKOFF;
        private Duration maxBackoff = DEFAULT_MAX_BACKOFF;
        private int maxConsecutiveFailures = DEFAULT_MAX_CONSECUTIVE_FAILURES;
        private Duration notRespondingBackoff = DEFAULT_NOT_RESPONDING_BACKOFF;
        private int maxRequestsPerModulePerScan = DEFAULT_MAX_REQUESTS_PER_MODULE_PER_SCAN;
        private int maxRequestsPerMinute = DEFAULT_MAX_REQUESTS_PER_MINUTE;
        private int connectAttempts = DEFAULT_CONNECT_ATTEMPTS;
        private Duration moduleListCacheTtl = DEFAULT_MODULE_LIST_CACHE_TTL;

        private Builder() {
        }

        private Builder(ScanLimits l) {
            minRequestGap = l.minRequestGap;
            testerPresentInterval = l.testerPresentInterval;
            channelKeepAliveInterval = l.channelKeepAliveInterval;
            maxRetries = l.maxRetries;
            initialBackoff = l.initialBackoff;
            maxBackoff = l.maxBackoff;
            maxConsecutiveFailures = l.maxConsecutiveFailures;
            notRespondingBackoff = l.notRespondingBackoff;
            maxRequestsPerModulePerScan = l.maxRequestsPerModulePerScan;
            maxRequestsPerMinute = l.maxRequestsPerMinute;
            connectAttempts = l.connectAttempts;
            moduleListCacheTtl = l.moduleListCacheTtl;
        }

        public Builder minRequestGap(Duration v) {
            minRequestGap = v;
            return this;
        }

        public Builder testerPresentInterval(Duration v) {
            testerPresentInterval = v;
            return this;
        }

        public Builder channelKeepAliveInterval(Duration v) {
            channelKeepAliveInterval = v;
            return this;
        }

        public Builder maxRetries(int v) {
            maxRetries = v;
            return this;
        }

        public Builder initialBackoff(Duration v) {
            initialBackoff = v;
            return this;
        }

        public Builder maxBackoff(Duration v) {
            maxBackoff = v;
            return this;
        }

        public Builder maxConsecutiveFailures(int v) {
            maxConsecutiveFailures = v;
            return this;
        }

        public Builder notRespondingBackoff(Duration v) {
            notRespondingBackoff = v;
            return this;
        }

        public Builder maxRequestsPerModulePerScan(int v) {
            maxRequestsPerModulePerScan = v;
            return this;
        }

        public Builder maxRequestsPerMinute(int v) {
            maxRequestsPerMinute = v;
            return this;
        }

        public Builder connectAttempts(int v) {
            connectAttempts = v;
            return this;
        }

        public Builder moduleListCacheTtl(Duration v) {
            moduleListCacheTtl = v;
            return this;
        }

        public ScanLimits build() {
            return new ScanLimits(minRequestGap, testerPresentInterval, channelKeepAliveInterval, maxRetries,
                    initialBackoff, maxBackoff, maxConsecutiveFailures, notRespondingBackoff,
                    maxRequestsPerModulePerScan, maxRequestsPerMinute, connectAttempts, moduleListCacheTtl);
        }
    }
}
