package io.github.samsanjaysatish.macvagdiag.can;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared base for every adapter. It owns the open/closed state and the mode, and it is the only caller
 * of {@link #transmit(CanFrame)}: in {@link AdapterMode#LISTEN_ONLY} {@link #send(CanFrame)} throws
 * before the implementation is reached, so nothing gets onto the wire.
 *
 * <p>The class is sealed and its public methods are {@code final}, and the hooks are package-private,
 * so no adapter can bypass these checks and no code outside this package can reach {@code transmit}.
 */
public abstract sealed class AbstractCanAdapter implements CanAdapter
        permits SlcanAdapter, SimulatedAdapter, ReplayAdapter {

    private static final Logger log = LoggerFactory.getLogger(AbstractCanAdapter.class);

    /** Guards open/send/close so a send never races with a mode change. */
    private final Object stateLock = new Object();
    private volatile AdapterMode mode; // null while closed

    AbstractCanAdapter() {
    }

    @Override
    public final void open(Bitrate bitrate, AdapterMode mode) {
        Objects.requireNonNull(bitrate, "bitrate");
        Objects.requireNonNull(mode, "mode");
        synchronized (stateLock) {
            if (this.mode != null) {
                throw new CanAdapterException(name() + " is already open");
            }
            connect(bitrate, mode);
            this.mode = mode;
            log.info("{} open at {} bit/s in {}", name(), bitrate.bitsPerSecond(), mode);
        }
    }

    @Override
    public final boolean isOpen() {
        return mode != null;
    }

    @Override
    public final Optional<AdapterMode> mode() {
        return Optional.ofNullable(mode);
    }

    @Override
    public final void send(CanFrame frame) {
        Objects.requireNonNull(frame, "frame");
        synchronized (stateLock) {
            AdapterMode current = mode;
            if (current == null) {
                throw new CanAdapterException(name() + " is not open");
            }
            if (current == AdapterMode.LISTEN_ONLY) {
                log.error("{}: blocked send of {} in LISTEN_ONLY mode", name(), frame);
                throw new ListenOnlyViolationException(frame);
            }
            transmit(frame);
        }
    }

    @Override
    public final Optional<TimestampedFrame> receive(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("Negative timeout: " + timeout);
        }
        if (mode == null) {
            throw new CanAdapterException(name() + " is not open");
        }
        return awaitFrame(timeout);
    }

    @Override
    public final void close() {
        synchronized (stateLock) {
            if (mode == null) {
                return;
            }
            mode = null;
            try {
                disconnect();
            } catch (RuntimeException e) {
                log.warn("{}: error while closing", name(), e);
            }
            log.info("{} closed", name());
        }
    }

    /** Short name for logs and error messages. */
    String name() {
        return getClass().getSimpleName();
    }

    /**
     * Goes on the bus. Called with the state lock held, only while closed. Must not transmit frames.
     * On failure, release anything acquired and throw; the adapter stays closed.
     */
    abstract void connect(Bitrate bitrate, AdapterMode mode);

    /**
     * Puts one frame on the wire. Only ever called by {@link #send(CanFrame)}, with the state lock held,
     * while open in {@link AdapterMode#NORMAL}.
     */
    abstract void transmit(CanFrame frame);

    /** Implements {@link #receive(Duration)}; called only after the open check, timeout validated. */
    abstract Optional<TimestampedFrame> awaitFrame(Duration timeout) throws InterruptedException;

    /**
     * Goes off the bus and releases resources. Called once per successful open, with the state lock held
     * and the adapter already marked closed. Must wake threads blocked in {@link #awaitFrame}.
     */
    abstract void disconnect();
}
