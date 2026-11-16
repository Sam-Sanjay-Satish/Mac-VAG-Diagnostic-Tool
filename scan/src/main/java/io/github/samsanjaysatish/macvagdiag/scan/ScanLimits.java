package io.github.samsanjaysatish.macvagdiag.scan;

import java.time.Duration;
import java.util.Objects;

/**
 * Pacing, keep-alive, retry and budget settings. {@code scan} enforces these whatever a caller asks; callers have
 * no way to change them per request. The app picks them once at startup ({@link ScanOptions}), and even that is
 * clamped: every value has a hard floor and ceiling below, so configuration can't switch pacing or budgets off.
 *
 * <p><b>Defaults, against the 2026-11-13 capture of VCDS on the target car:</b> the pacing and keep-alive values are
 * set so that we are never faster than VCDS and never leave a channel or session idle for longer than VCDS did
 * (the only idle times known to be safe). Retries, back-off and budgets can't be compared (no timeouts or busy
 * answers happened on the capture) and stay UNCONFIRMED choices, explained on each constant.
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
     * 100 ms, at or above VCDS's pace on the capture: VCDS left at least 22 ms (TP 2.0, median 41 ms) and 58 ms
     * (UDS, median 160 ms) between a response and its next request. Slower than VCDS is fine; faster isn't proven.
     */
    public static final Duration DEFAULT_MIN_REQUEST_GAP = Duration.ofMillis(100);
    /**
     * 1.5 s. A UDS session ({@code 10 03}) ends after S3server = 5 s without a request. VCDS sent Tester Present
     * 0.25–1.6 s apart in idle gaps on the capture (as {@code 3E 80} on the functional ID {@code 700}, which we
     * don't use); 1.5 s stays inside what it did. On TP 2.0 we also send KWP {@code 3E} after this interval;
     * VCDS never did (its {@code A3} keep-alive was enough), so dropping it there is an open question.
     * UNCONFIRMED: how long the modules really wait.
     */
    public static final Duration DEFAULT_TESTER_PRESENT_INTERVAL = Duration.ofMillis(1500);
    /**
     * 500 ms. VCDS sent the TP 2.0 keep-alive {@code A3} after 0.5–0.6 s idle (median 541 ms, 97 samples) and never
     * left a channel idle longer, so how long a module keeps an idle channel open is unknown (UNCONFIRMED); 500 ms
     * stays just under what VCDS did. More often than needed is harmless (it carries no request).
     */
    public static final Duration DEFAULT_CHANNEL_KEEP_ALIVE_INTERVAL = Duration.ofMillis(500);
    /** UNCONFIRMED: 2 retries, so at most 3 tries of one operation (no timeout or busy answer on the capture). */
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
     * 60. Our request sets are much shorter than VCDS's (it sent 6–94 per module, mostly identification DIDs): a
     * module costs 1 session + up to 6 identification + 1 DTC read + 1 (KWP) or 2 (UDS) per freeze frame + Tester
     * Present + retries, and the gateway 2 more for the installation list. The engine on the capture (12 codes)
     * needs about 21. UNCONFIRMED as a limit; it only stops runaways.
     */
    public static final int DEFAULT_MAX_REQUESTS_PER_MODULE_PER_SCAN = 60;
    /**
     * 400. VCDS's busiest minute on the capture had 168 requests; one of our Auto-Scans of the target car sends
     * fewer than that in total. It stops a runaway caller (e.g. an AI looping over read_fault_codes).
     */
    public static final int DEFAULT_MAX_REQUESTS_PER_MINUTE = 400;
    /**
     * 2 channel setups per link, so one lost frame doesn't make a module look absent. VCDS tries 10, 77 ms apart,
     * and waits about 15 s on each module that never answers (2E, 56 on the target car); the modules that do
     * answer did so to the first setup within 14 ms, so more attempts would only make unreachable modules slower.
     */
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
