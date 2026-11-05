package io.github.samsanjaysatish.macvagdiag.can;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A CANable (or other slcan) adapter on a serial port. A reader thread splits the byte stream into
 * lines, decodes them and queues received frames, stamped on receipt with the {@link TimeSource}
 * (the CANable stock firmware has no {@code Z1} timestamps).
 *
 * <p><b>{@link AdapterMode#LISTEN_ONLY} only on a pinned firmware build.</b> The stock CANable 2.0
 * firmware accepts the silent-mode command ({@link SlcanCodec#SILENT_MODE_COMMAND}) but then opens the
 * controller in normal mode anyway, so the adapter would still ACK frames and could send error frames on
 * the bus. Our patched firmware ({@code firmware/canable2}) fixes that. So a LISTEN_ONLY open first takes
 * the adapter off the bus ({@code C}) and asks for its version ({@code V}); neither touches the bus. Only an
 * exact match with {@link #SILENT_FIRMWARE_VERSIONS} continues with {@code S<n>}, {@code M1}, {@code O}.
 * Anything else (stock firmware, a dirty or unknown build, no reply) closes the port and throws, having
 * sent nothing but {@code C} and {@code V}. See the slcan reference in {@code can-core/CLAUDE.md}.
 *
 * <p>Create it with {@link #forPort}; this is the only class that constructs a real serial port.
 */
public final class SlcanAdapter extends AbstractCanAdapter {

    private static final Logger log = LoggerFactory.getLogger(SlcanAdapter.class);

    /** Received frames held while nobody calls {@code receive}; beyond this new frames are dropped. */
    public static final int RECEIVE_QUEUE_CAPACITY = 100_000;

    /**
     * Firmware builds whose silent mode ({@code M1} then {@code O}) really opens the controller in bus
     * monitoring mode, as reported by {@code V}. Exact matches only. Add a build here only after reading
     * its source (see {@code firmware/canable2/README.md}).
     */
    static final Set<String> SILENT_FIRMWARE_VERSIONS = Set.of("macvagdiag-silent-1");

    /**
     * Reads to wait for the reply to {@code V}: at most about 100 ms each on the real port (about 2 s in
     * all). Counted rather than timed so tests on a fake clock stay deterministic.
     */
    static final int VERSION_REPLY_MAX_READS = 20;

    private static final String NEEDS_SILENT_FIRMWARE = "LISTEN_ONLY needs CANable firmware "
            + String.join(" or ", new TreeSet<>(SILENT_FIRMWARE_VERSIONS))
            + " (see firmware/canable2/README.md). The stock CANable 2.0 firmware accepts M1 but opens the "
            + "controller in normal mode anyway, so it would ACK frames and could send error frames on the bus. "
            + "The adapter was taken off the bus (C) and nothing else was sent.";

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
        try {
            port.open();
        } catch (IOException e) {
            throw new CanAdapterException("Could not open " + port.name(), e);
        }
        try {
            // Close first in case a previous run left the adapter on the bus: the firmware ignores
            // S (bitrate) and M (mode) while on the bus. The CANable firmware sends no OK/error replies
            // to commands, so nothing waits for them.
            writeCommand(SlcanCodec.CLOSE_COMMAND);
            if (mode == AdapterMode.LISTEN_ONLY) {
                requireSilentFirmware();
            }
            writeCommand(SlcanCodec.bitrateCommand(bitrate));
            writeCommand(mode == AdapterMode.LISTEN_ONLY
                    ? SlcanCodec.SILENT_MODE_COMMAND
                    : SlcanCodec.NORMAL_MODE_COMMAND);
            queue = new FrameQueue(time, RECEIVE_QUEUE_CAPACITY);
            startReader();
            writeCommand(SlcanCodec.OPEN_COMMAND);
        } catch (CanAdapterException e) {
            shutDown(false);
            throw e;
        } catch (IOException | RuntimeException e) {
            shutDown(false);
            throw new CanAdapterException("Could not start slcan on " + port.name(), e);
        }
    }

    /** Asks the adapter for its firmware version and throws unless it is a pinned silent-capable build. */
    private void requireSilentFirmware() throws IOException {
        Optional<String> version = queryFirmwareVersion();
        if (version.isEmpty()) {
            String message = "SlcanAdapter refuses LISTEN_ONLY: no reply to V from " + port.name()
                    + ", so its firmware can't be confirmed. " + NEEDS_SILENT_FIRMWARE;
            log.error(message);
            throw new CanAdapterException(message);
        }
        if (!SILENT_FIRMWARE_VERSIONS.contains(version.get())) {
            String message = "SlcanAdapter refuses LISTEN_ONLY: " + port.name() + " runs firmware '"
                    + SlcanCodec.printable(version.get()) + "', which has no verified silent mode. "
                    + NEEDS_SILENT_FIRMWARE;
            log.error(message);
            throw new CanAdapterException(message);
        }
        log.info("{}: firmware {} has a working silent mode", name(), version.get());
    }

    /**
     * Sends {@code V} and reads lines until the version reply, for at most {@link #VERSION_REPLY_MAX_READS}
     * reads. Runs before the reader thread starts. Frame lines still arriving from before {@code C} are
     * skipped (they predate the open).
     */
    private Optional<String> queryFirmwareVersion() throws IOException {
        writeCommand(SlcanCodec.VERSION_COMMAND);
        SlcanLineSplitter splitter = new SlcanLineSplitter();
        byte[] buffer = new byte[READ_BUFFER_SIZE];
        for (int i = 0; i < VERSION_REPLY_MAX_READS; i++) {
            int n = port.read(buffer);
            if (n < 0) {
                throw new IOException(port.name() + " closed while waiting for the firmware version");
            }
            for (String line : splitter.feed(buffer, n)) {
                Optional<String> version = SlcanCodec.versionOf(line);
                if (version.isPresent()) {
                    return version;
                }
            }
        }
        return Optional.empty();
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
