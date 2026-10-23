package io.github.samsanjaysatish.macvagdiag.diag;

import io.github.samsanjaysatish.macvagdiag.transport.ApplicationProtocol;

/**
 * The diagnostic sessions this layer can request, one per approved allowlist entry. There is deliberately no
 * programming session (UDS {@code 10 02}, KWP {@code 10 85}) or any other: they can't be expressed here, and the
 * transport's guard would refuse them anyway.
 *
 * <p>When to start or leave a session is {@code scan}'s decision, never this layer's. Which session VCDS uses
 * for each module is UNCONFIRMED (from the capture).
 */
public enum DiagnosticSession {
    /** KWP2000 {@code 10 89}: VW diagnostic session. A KWP session ends when the TP 2.0 channel is closed. */
    KWP_VW_DIAGNOSTIC(ApplicationProtocol.KWP2000),
    /** UDS {@code 10 01}: default session (also how a UDS session is left). */
    UDS_DEFAULT(ApplicationProtocol.UDS),
    /** UDS {@code 10 03}: extended diagnostic session. */
    UDS_EXTENDED(ApplicationProtocol.UDS);

    private final ApplicationProtocol protocol;

    DiagnosticSession(ApplicationProtocol protocol) {
        this.protocol = protocol;
    }

    /** The protocol this session belongs to. */
    public ApplicationProtocol protocol() {
        return protocol;
    }
}
