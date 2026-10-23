package io.github.samsanjaysatish.macvagdiag.transport;

import java.time.Duration;

/**
 * An unguarded transport channel (TP 2.0 or ISO-TP). Package-private on purpose: the only way to get one from
 * outside this package is wrapped in a {@link GuardedChannel} by {@link ChannelFactory}. {@code NoBypassTest}
 * checks this by reflection.
 */
interface RawChannel {

    /** Discards stale responses, sends {@code payload} (already checked by the guard), waits for the response. */
    Response exchange(byte[] payload, Duration timeout) throws InterruptedException;

    /** Waits for the next response message without sending. */
    Response awaitResponse(Duration timeout) throws InterruptedException;

    boolean keepAlive() throws InterruptedException;

    boolean isOpen();

    void close();
}
