package io.github.samsanjaysatish.macvagdiag.scan;

/** The outcome for one module. */
public enum ModuleStatus {
    /** Fault codes read: none stored. */
    OK("OK"),
    /** Fault codes read: at least one stored. */
    FAULTS("fault codes found"),
    /** No answer, or stopped answering after repeated failures. */
    NOT_RESPONDING("no response"),
    /** It answered, but the fault codes couldn't be read (negative response, timeout partway, ...). */
    ERROR("could not read fault codes"),
    /** Stopped partway by a limit or a cancel; see the notes and the limit. */
    INCOMPLETE("incomplete"),
    /** Not asked at all (Auto-Scan stopped by a limit, a cancel or a lost adapter). */
    SKIPPED("skipped"),
    /**
     * Not contacted because it has no diagnostic link of its own (04 Steering Angle on the target car): the only
     * status is the gateway's, in {@link ModuleResult#gatewayStatus()}, as in VCDS.
     */
    GATEWAY_STATUS_ONLY("status from the gateway only");

    private final String description;

    ModuleStatus(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
