package io.github.samsanjaysatish.macvagdiag.transport;

/**
 * Hook for the audit log kept by {@code scan} (root CLAUDE.md, rule 8). Registered once on the
 * {@link ChannelFactory}; every guarded channel it opens reports to it.
 */
public interface RequestAuditListener {

    /** A listener that records nothing. */
    RequestAuditListener NONE = new RequestAuditListener() {
    };

    /**
     * Called after a request passed the guard and right before it is handed to the transport. If this throws,
     * the request is <b>not</b> sent and the exception propagates to the caller: nothing reaches the car
     * without an audit record.
     *
     * @param payload a copy of the request bytes
     */
    default void sending(ModuleRoute route, ApplicationProtocol protocol, byte[] payload) {
    }

    /**
     * Called when the guard refused a request; nothing was sent. An exception thrown here is logged and
     * attached as suppressed to {@code rejection}, which is still thrown to the caller.
     */
    default void rejected(ModuleRoute route, ForbiddenRequestException rejection) {
    }
}
