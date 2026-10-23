package io.github.samsanjaysatish.macvagdiag.scan;

/**
 * Told about every request the transport is about to send and every request its guard rejected. Plain strings, so
 * the seam carries no transport types. Implemented by {@link ScanEngine}: it writes the audit line and checks the
 * request was reserved; if either fails it throws, and the transport then sends nothing.
 */
interface RequestObserver {

    /**
     * A request passed the guard and is about to be sent.
     *
     * @throws RuntimeException to stop it being sent
     */
    void sending(String link, String protocol, String requestHex);

    /** The guard refused a request; nothing was sent. */
    void rejected(String link, String protocol, String requestHex, String reason);
}
