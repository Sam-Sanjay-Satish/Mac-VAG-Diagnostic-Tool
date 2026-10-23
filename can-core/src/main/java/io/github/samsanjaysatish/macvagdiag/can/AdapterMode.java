package io.github.samsanjaysatish.macvagdiag.can;

/** How an adapter is opened. */
public enum AdapterMode {
    /** Receive and transmit. */
    NORMAL,
    /**
     * Receive only. {@link CanAdapter#send} throws {@link ListenOnlyViolationException} and nothing
     * reaches the wire; this is enforced in {@link AbstractCanAdapter}, not by each adapter.
     */
    LISTEN_ONLY
}
