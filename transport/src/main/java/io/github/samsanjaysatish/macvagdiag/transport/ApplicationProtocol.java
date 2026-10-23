package io.github.samsanjaysatish.macvagdiag.transport;

/**
 * The diagnostic application protocol a {@link DiagnosticChannel} carries. The {@link RequestGuard}
 * allowlist is keyed by it, so the same bytes can be allowed under one protocol and refused under the other
 * (e.g. {@code 10 89} is the VW KWP2000 diagnostic session but is not an allowed UDS session).
 */
public enum ApplicationProtocol {
    /** KWP2000 (ISO 14230-3) as VW uses it, normally over VW TP 2.0. */
    KWP2000,
    /** UDS (ISO 14229-1), normally over ISO-TP. */
    UDS
}
