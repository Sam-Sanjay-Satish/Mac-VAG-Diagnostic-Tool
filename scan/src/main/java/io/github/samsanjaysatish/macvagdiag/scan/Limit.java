package io.github.samsanjaysatish.macvagdiag.scan;

/** The limits {@code scan} enforces, named in every refusal so a caller (or an AI) knows which one it hit. */
public enum Limit {
    /** {@link ScanLimits#maxRequestsPerMinute()}, a sliding one-minute window over all callers. */
    REQUESTS_PER_MINUTE("requests per minute to the car"),
    /** {@link ScanLimits#maxRequestsPerModulePerScan()}; a scan is one top-level call. */
    REQUESTS_PER_MODULE_PER_SCAN("requests to one module in one scan"),
    /** {@link ScanLimits#notRespondingBackoff()}: a module that just didn't respond isn't asked again yet. */
    MODULE_NOT_RESPONDING_BACKOFF("wait before asking a module that did not respond again"),
    /** Only one Auto-Scan runs at a time. */
    ONE_AUTO_SCAN_AT_A_TIME("one Auto-Scan at a time");

    private final String description;

    Limit(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
