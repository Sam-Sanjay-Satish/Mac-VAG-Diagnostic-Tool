package io.github.samsanjaysatish.macvagdiag.can;

import java.time.Duration;
import java.util.Optional;

/**
 * Moves CAN frames between the program and an adapter (real, simulated or replayed).
 *
 * <p>Sealed: every implementation extends {@link AbstractCanAdapter}, which enforces the open/closed
 * state and the {@link AdapterMode#LISTEN_ONLY} guarantee. The implementations are {@link SlcanAdapter}
 * (real hardware), {@link SimulatedAdapter} (scripted fake ECU) and {@link ReplayAdapter} (capture files).
 *
 * <p>The only way to receive is the blocking {@link #receive(Duration)}; there are no listeners.
 */
public sealed interface CanAdapter extends AutoCloseable permits AbstractCanAdapter {

    /**
     * Opens the adapter on the bus.
     *
     * @throws CanAdapterException if it is already open or cannot be opened
     */
    void open(Bitrate bitrate, AdapterMode mode);

    boolean isOpen();

    /** The mode the adapter is open in, or empty when closed. */
    Optional<AdapterMode> mode();

    /**
     * Transmits one frame.
     *
     * @throws ListenOnlyViolationException if open in {@link AdapterMode#LISTEN_ONLY}; nothing is transmitted
     * @throws CanAdapterException if not open, or the frame could not be sent
     */
    void send(CanFrame frame);

    /**
     * Waits up to {@code timeout} (measured on the adapter's {@link TimeSource}) for the next frame.
     * A zero timeout returns a frame only if one is already waiting.
     *
     * @return the next frame, or empty if the timeout passed first
     * @throws CanAdapterException if not open, closed while waiting, or the adapter failed
     * @throws InterruptedException if the waiting thread is interrupted
     */
    Optional<TimestampedFrame> receive(Duration timeout) throws InterruptedException;

    /** Goes off the bus and releases the adapter. Safe to call more than once; never throws. */
    @Override
    void close();
}
