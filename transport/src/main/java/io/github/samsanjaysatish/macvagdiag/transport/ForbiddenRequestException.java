package io.github.samsanjaysatish.macvagdiag.transport;

import io.github.samsanjaysatish.macvagdiag.can.Hex;
import java.util.Optional;

/**
 * The {@link RequestGuard} refused a request because it is not on the read-only allowlist. Nothing was sent.
 *
 * <p>This is never an expected outcome for code in this repo: it means a bug, or a caller trying something it
 * must not. It is logged at WARN by the guard and passed to {@link RequestAuditListener#rejected}.
 */
public final class ForbiddenRequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ApplicationProtocol protocol; // null when the protocol itself was missing
    private final byte[] payload; // null when the payload itself was missing
    private final String reason;

    ForbiddenRequestException(ApplicationProtocol protocol, byte[] payload, String reason) {
        super("Rejected " + (protocol == null ? "request with no protocol" : protocol + " request")
                + " " + describe(payload) + ": " + reason);
        this.protocol = protocol;
        this.payload = payload == null ? null : payload.clone();
        this.reason = reason;
    }

    private static String describe(byte[] payload) {
        if (payload == null) {
            return "(null)";
        }
        return payload.length == 0 ? "(empty)" : Hex.format(payload);
    }

    /** The channel's protocol, or empty if none was given. */
    public Optional<ApplicationProtocol> protocol() {
        return Optional.ofNullable(protocol);
    }

    /** A copy of the refused request bytes (empty array if the payload was null). */
    public byte[] payload() {
        return payload == null ? new byte[0] : payload.clone();
    }

    /** Why it was refused, e.g. {@code service 27 is not on the read-only allowlist}. */
    public String reason() {
        return reason;
    }
}
