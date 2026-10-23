package io.github.samsanjaysatish.macvagdiag.transport;

import java.util.Objects;

/**
 * A channel could not be opened, or the transport refused to put a frame on the bus. Request/response
 * failures on an open channel are not exceptions; they come back as {@link Response.Failed}.
 */
public final class TransportException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Reason {
        /** The module didn't answer the channel setup (usually: no module at that address). */
        NO_RESPONSE,
        /** The module answered the TP 2.0 setup negatively ({@code D6}..{@code D8}). */
        REFUSED_BY_MODULE,
        /**
         * A CAN ID outside the expected diagnostic set: a route the factory doesn't know, a TP 2.0 setup reply
         * negotiating an unexpected ID, or a frame about to be sent on such an ID. Nothing was sent on it.
         */
        DISALLOWED_CAN_ID,
        /** Another channel from the same factory is still open; only one at a time shares the adapter. */
        CHANNEL_ALREADY_OPEN,
        /** The adapter isn't open in {@code NORMAL} mode. */
        ADAPTER_NOT_READY,
        /** The module's setup or parameter reply was malformed. */
        PROTOCOL_ERROR,
        /** The adapter failed during setup. */
        ADAPTER_ERROR
    }

    private final Reason reason;

    TransportException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    TransportException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}
