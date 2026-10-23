package io.github.samsanjaysatish.macvagdiag.can;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A CANable (or other slcan) adapter on a serial port. A reader thread splits the byte stream into
 * lines, decodes them and queues received frames, stamped on receipt with the {@link TimeSource}
 * (the CANable stock firmware has no {@code Z1} timestamps).
 *
 * <p><b>{@link AdapterMode#LISTEN_ONLY} is refused:</b> the stock CANable 2.0 firmware accepts its
 * silent-mode command ({@link SlcanCodec#SILENT_MODE_COMMAND}) but then opens the controller in normal
 * mode anyway, so the adapter would still ACK frames and send error frames on the bus. See the slcan
 * reference in {@code can-core/CLAUDE.md}. Opening in LISTEN_ONLY throws before the serial port is touched.
 *
 * <p>Create it with {@link #forPort}; this is the only class that constructs a real serial port.
 */
public final class SlcanAdapter extends AbstractCanAdapter {

    private static final Logger log = LoggerFactory.getLogger(SlcanAdapter.class);

    /** Received frames held while nobody calls {@code receive}; beyond this new frames are dropped. */
    public static final int RECEIVE_QUEUE_CAPACITY = 100_000;

    static final String LISTEN_ONLY_REFUSED = "SlcanAdapter refuses LISTEN_ONLY: the CANable 2.0 stock slcan "
            + "firmware (normaldotcom/canable2-fw b158aa7) accepts the silent-mode command M1 but can_enable() "
            + "resets the controller to normal mode on O, so the adapter would still ACK and send error frames "
            + "on the bus. Not opening the port until that is resolved (see can-core/CLAUDE.md).";

    private static final Duration READER_JOIN_TIMEOUT = Duration.ofSeconds(2);
    private static final int READ_BUFFER_SIZE = 512;

    private final SerialPortIo port;
    private final TimeSource time;

    private volatile FrameQueue queue;
    private volatile boolean running;
    private Thread reader;

    private final AtomicLong decodeErrors = new AtomicLong();
    private final AtomicLong errorReplies = new AtomicLong();
    private final AtomicLong remoteFrames = new AtomicLong();
    private final AtomicLong discardedLines = new AtomicLong();

    SlcanAdapter(SerialPortIo port, TimeSource time) {
        this.port = Objects.requireNonNull(port, "port");
        this.time = Objects.requireNonNull(time, "time");
        this.queue = new FrameQueue(time, RECEIVE_QUEUE_CAPACITY);
    }

    /** An adapter on the serial port at {@code path} (e.g. {@code /dev/cu.usbmodem14101}), on the real clock. */
    public static SlcanAdapter forPort(String path) {
        return forPort(path, TimeSource.system());
    }

    public static SlcanAdapter forPort(String path, TimeSource time) {
        return new SlcanAdapter(new JSerialCommPort(Objects.requireNonNull(path, "path")), time);
    }

    /** The serial port path. */
    public String portName() {
        return port.name();
    }

    @Override
    String name() {
        return "SlcanAdapter(" + port.name() + ")";
    }

    @Override
    void connect(Bitrate bitrate, AdapterMode mode) {
        if (mode == AdapterMode.LISTEN_ONLY) {
            log.error(LISTEN_ONLY_REFUSED);
            throw new CanAdapterException(LISTEN_ONLY_REFUSED);
        }
        try {
            port.open();
        } catch (IOException e) {
            throw new CanAdapterException("Could not open " + port.name(), e);
        }
        try {
            // Close first in case a previous run left the adapter on the bus: the firmware ignores
            // S (bitrate) while on the bus. The stock firmware sends no OK/error replies to commands,
            // so nothing waits for them.
            writeCommand(SlcanCodec.CLOSE_COMMAND);
            writeCommand(SlcanCodec.bitrateCommand(bitrate));
            queue = new FrameQueue(time, RECEIVE_QUEUE_CAPACITY);
            startReader();
            writeCommand(SlcanCodec.OPEN_COMMAND);
        } catch (IOException | RuntimeException e) {
            shutDown(false);
            throw new CanAdapterException("Could not start slcan on " + port.name(), e);
        }
    }

    @Override
    void transmit(CanFrame frame) {
        // Defence in depth: the base class already refuses sends in LISTEN_ONLY.
        if (mode().orElse(AdapterMode.LISTEN_ONLY) != AdapterMode.NORMAL) {
            throw new ListenOnlyViolationException(frame);
        }
        try {
            port.write(SlcanCodec.ascii(SlcanCodec.encodeFrame(frame)));
        } catch (IOException e) {
            throw new CanAdapterException("Could not send " + frame + " on " + port.name(), e);
        }
    }

    @Override
    Optional<TimestampedFrame> awaitFrame(Duration timeout) throws InterruptedException {
        return queue.poll(timeout);
    }

    @Override
    void disconnect() {
        shutDown(true);
    }

    /** Sends {@code C} (if asked), stops the reader, releases the port. Each step runs even if an earlier one fails. */
    private void shutDown(boolean sendClose) {
        running = false;
        if (sendClose) {
            try {
                writeCommand(SlcanCodec.CLOSE_COMMAND);
            } catch (IOException | RuntimeException e) {
                log.warn("{}: could not send close command", name(), e);
            }
        }
        try {
            port.close();
        } catch (RuntimeException e) {
            log.warn("{}: error releasing the serial port", name(), e);
        }
        Thread t = reader;
        reader = null;
        if (t != null && t != Thread.currentThread()) {
            try {
                if (!t.join(READER_JOIN_TIMEOUT)) {
                    log.warn("{}: reader thread did not stop within {}", name(), READER_JOIN_TIMEOUT);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        queue.close();
    }

    private void writeCommand(String command) throws IOException {
        port.write(SlcanCodec.ascii(command));
    }

    private void startReader() {
        running = true;
        FrameQueue target = queue;
        reader = Thread.ofPlatform()
                .name("slcan-reader " + port.name())
                .daemon(true)
                .start(() -> readLoop(target));
    }

    private void readLoop(FrameQueue target) {
        SlcanLineSplitter splitter = new SlcanLineSplitter();
        byte[] buffer = new byte[READ_BUFFER_SIZE];
        while (running) {
            int n;
            try {
                n = port.read(buffer);
            } catch (IOException e) {
                if (running) {
                    log.error("{}: read failed", name(), e);
                    target.fail(new CanAdapterException("Read failed on " + port.name(), e));
                }
                return;
            }
            if (n < 0) {
                if (running) {
                    log.error("{}: serial port closed unexpectedly", name());
                    target.fail(new CanAdapterException(port.name() + " closed unexpectedly (adapter unplugged?)"));
                }
                return;
            }
            long discardedBefore = splitter.discardedLines();
            List<String> lines = splitter.feed(buffer, n);
            discardedLines.addAndGet(splitter.discardedLines() - discardedBefore);
            for (String line : lines) {
                handleLine(line, target);
            }
        }
    }

    private void handleLine(String line, FrameQueue target) {
        SlcanMessage message;
        try {
            message = SlcanCodec.decode(line);
        } catch (SlcanFormatException e) {
            decodeErrors.incrementAndGet();
            log.debug("{}: {}", name(), e.getMessage());
            return;
        }
        switch (message) {
            case SlcanMessage.Received r -> target.offer(r.frame());
            case SlcanMessage.RemoteFrame rf -> {
                remoteFrames.incrementAndGet();
                log.debug("{}: skipped remote frame id 0x{}", name(), Integer.toHexString(rf.id()));
            }
            case SlcanMessage.Error e -> {
                errorReplies.incrementAndGet();
                log.warn("{}: adapter replied with an error (BEL)", name());
            }
            case SlcanMessage.Ack a -> {
                // nothing to do
            }
        }
    }

    /** Received lines that were not valid slcan. */
    public long decodeErrorCount() {
        return decodeErrors.get();
    }

    /** BEL error replies from the adapter. */
    public long errorReplyCount() {
        return errorReplies.get();
    }

    /** Remote frames seen and skipped ({@link CanFrame} does not model them). */
    public long remoteFrameCount() {
        return remoteFrames.get();
    }

    /** Over-long lines thrown away by the line splitter. */
    public long discardedLineCount() {
        return discardedLines.get();
    }

    /** Frames dropped because the receive queue was full ({@link #RECEIVE_QUEUE_CAPACITY}). */
    public long droppedFrameCount() {
        return queue.droppedCount();
    }
}
