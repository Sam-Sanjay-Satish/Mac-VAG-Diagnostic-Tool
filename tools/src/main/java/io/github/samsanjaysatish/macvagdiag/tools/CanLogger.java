package io.github.samsanjaysatish.macvagdiag.tools;

import io.github.samsanjaysatish.macvagdiag.can.AdapterMode;
import io.github.samsanjaysatish.macvagdiag.can.Bitrate;
import io.github.samsanjaysatish.macvagdiag.can.CanAdapter;
import io.github.samsanjaysatish.macvagdiag.can.CanAdapterException;
import io.github.samsanjaysatish.macvagdiag.can.CanFrame;
import io.github.samsanjaysatish.macvagdiag.can.CaptureWriter;
import io.github.samsanjaysatish.macvagdiag.can.ReplayAdapter;
import io.github.samsanjaysatish.macvagdiag.can.SimulatedAdapter;
import io.github.samsanjaysatish.macvagdiag.can.SlcanAdapter;
import io.github.samsanjaysatish.macvagdiag.can.TimeSource;
import io.github.samsanjaysatish.macvagdiag.can.TimestampedFrame;
import java.io.IOException;
import java.io.PrintStream;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * The listen-only logger: receives every frame from an adapter and writes it to a candump log file.
 *
 * <p><b>Read-only by construction.</b> The adapter is opened in {@link #MODE} ({@code LISTEN_ONLY}), which
 * is a constant, not a parameter, and this class never sends a frame. A test scans the sources of this
 * project to prove there is no send call anywhere in it.
 *
 * <p>Lifecycle: {@link #start()} opens the adapter, then creates the capture file and writes the header.
 * {@link #loop()} receives until stopped, the duration is reached or the adapter fails, and always ends with
 * {@link #shutdown()}. {@link #shutdown()} is also the Ctrl-C hook: it closes the adapter (waking a blocked
 * receive), flushes and closes the file and prints the final stats. It is idempotent and thread-safe.
 *
 * <p>Timing (flush every {@link Timing#flushInterval()}, summary every {@link Timing#summaryInterval()},
 * optional maximum duration) is measured on the {@link TimeSource}, so tests run on a fake clock.
 */
final class CanLogger {

    /** The only mode this logger ever opens an adapter in. Deliberately not configurable. */
    static final AdapterMode MODE = AdapterMode.LISTEN_ONLY;

    static final Duration DEFAULT_FLUSH_INTERVAL = Duration.ofSeconds(1);
    static final Duration DEFAULT_SUMMARY_INTERVAL = Duration.ofSeconds(5);

    private static final String PREFIX = "can-log: ";

    /** Why the logger stopped. */
    enum End {
        STOPPED("stopped"),
        DURATION_REACHED("duration reached"),
        ADAPTER_FAILED("adapter failed"),
        WRITE_FAILED("could not write the capture file");

        private final String text;

        End(String text) {
            this.text = text;
        }

        boolean isFailure() {
            return this == ADAPTER_FAILED || this == WRITE_FAILED;
        }
    }

    /** Creates the capture file. Called only after the adapter has opened. */
    @FunctionalInterface
    interface CaptureOpener {
        CaptureWriter open() throws IOException;
    }

    /** Values for the capture header. */
    record Header(Bitrate bitrate, String port, String firmware, Optional<String> note) {
        Header {
            Objects.requireNonNull(bitrate, "bitrate");
            Objects.requireNonNull(port, "port");
            Objects.requireNonNull(firmware, "firmware");
            Objects.requireNonNull(note, "note");
        }
    }

    /** When to flush, print a summary, and stop. */
    record Timing(Duration flushInterval, Duration summaryInterval, Optional<Duration> maxDuration) {
        Timing {
            requirePositive(flushInterval, "flushInterval");
            requirePositive(summaryInterval, "summaryInterval");
            maxDuration.ifPresent(d -> requirePositive(d, "maxDuration"));
        }

        static Timing defaults(Optional<Duration> maxDuration) {
            return new Timing(DEFAULT_FLUSH_INTERVAL, DEFAULT_SUMMARY_INTERVAL, maxDuration);
        }

        private static void requirePositive(Duration d, String name) {
            if (d.isZero() || d.isNegative()) {
                throw new IllegalArgumentException(name + " must be positive: " + d);
            }
        }
    }

    private final CanAdapter adapter;
    private final CaptureOpener opener;
    private final Header header;
    private final TimeSource time;
    private final ZoneId zone;
    private final PrintStream console;
    private final LongSupplier adapterErrors;
    private final Timing timing;
    private final String outputName;

    private final Object writerLock = new Object();
    private CaptureWriter writer; // guarded by writerLock; null before start and after close

    private final Object shutdownLock = new Object();
    private boolean shutDown; // guarded by shutdownLock
    private volatile boolean started;
    private volatile boolean stopRequested;
    private final AtomicReference<End> end = new AtomicReference<>();

    private final AtomicLong frames = new AtomicLong();
    private final AtomicLong writeErrors = new AtomicLong();
    private final Set<Long> ids = ConcurrentHashMap.newKeySet();

    // Loop-thread state.
    private volatile long startNanos;
    private long nextFlushNanos;
    private long nextSummaryNanos;
    private long endNanos;
    private long lastSummaryNanos;
    private long lastSummaryFrames;

    /**
     * @param adapter opened by {@link #start()} in {@link #MODE}, closed by {@link #shutdown()}
     * @param opener creates the capture file once the adapter is open
     * @param console live summary, warnings and final stats (stderr in the real program)
     * @param adapterErrors running count of errors the adapter has seen; see {@link #adapterErrorCounter}
     * @param outputName how to name the capture file in messages
     */
    CanLogger(CanAdapter adapter, CaptureOpener opener, Header header, TimeSource time, ZoneId zone,
              PrintStream console, LongSupplier adapterErrors, Timing timing, String outputName) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.opener = Objects.requireNonNull(opener, "opener");
        this.header = Objects.requireNonNull(header, "header");
        this.time = Objects.requireNonNull(time, "time");
        this.zone = Objects.requireNonNull(zone, "zone");
        this.console = Objects.requireNonNull(console, "console");
        this.adapterErrors = Objects.requireNonNull(adapterErrors, "adapterErrors");
        this.timing = Objects.requireNonNull(timing, "timing");
        this.outputName = Objects.requireNonNull(outputName, "outputName");
    }

    /** Errors an adapter counts while running (bad slcan lines, error replies, queue overflow). */
    static LongSupplier adapterErrorCounter(CanAdapter adapter) {
        return switch (adapter) {
            case SlcanAdapter s -> () -> s.decodeErrorCount() + s.errorReplyCount() + s.discardedLineCount()
                    + s.droppedFrameCount();
            case SimulatedAdapter _ -> () -> 0;
            case ReplayAdapter _ -> () -> 0;
        };
    }

    /**
     * Opens the adapter in {@link #MODE}, then creates the capture file and writes and flushes the header.
     * If the adapter refuses to open, no file is created. If the file can't be created, the adapter is closed.
     *
     * @throws CanAdapterException if the adapter can't be opened in listen-only mode
     * @throws IOException if the capture file can't be created or written
     */
    void start() throws IOException {
        if (started) {
            throw new IllegalStateException("already started");
        }
        adapter.open(header.bitrate(), MODE);
        if (adapter.mode().orElse(null) != MODE) {
            adapter.close();
            throw new CanAdapterException("Adapter did not open in " + MODE + "; closed it");
        }
        CaptureWriter w = null;
        try {
            w = opener.open();
            writeHeader(w);
            w.flush();
        } catch (IOException | RuntimeException e) {
            adapter.close();
            if (w != null) {
                closeQuietly(w);
            }
            throw e;
        }
        synchronized (writerLock) {
            writer = w;
        }
        long now = time.nanoTime();
        startNanos = now;
        lastSummaryNanos = now;
        nextFlushNanos = now + timing.flushInterval().toNanos();
        nextSummaryNanos = now + timing.summaryInterval().toNanos();
        endNanos = timing.maxDuration().map(d -> time.deadlineAfter(d)).orElse(Long.MAX_VALUE);
        started = true;
    }

    private void writeHeader(CaptureWriter w) throws IOException {
        w.writeComment("macvagdiag can-log capture in candump log format (candump -L)");
        w.writeComment("Raw capture, may contain the VIN. Keep it in local/ and never commit it unsanitized");
        w.writeMetadata("date", DateTimeFormatter.ISO_OFFSET_DATE_TIME
                .format(time.now().atZone(zone).truncatedTo(ChronoUnit.SECONDS)));
        w.writeMetadata("bitrate", Integer.toString(header.bitrate().bitsPerSecond()));
        w.writeMetadata("mode", MODE.name());
        w.writeMetadata("port", header.port());
        w.writeMetadata("firmware", header.firmware());
        w.writeMetadata("tool", Main.toolNameAndVersion());
        if (header.note().isPresent()) {
            w.writeMetadata("note", header.note().get());
        }
    }

    /** {@link #start()} then {@link #loop()}. */
    End run() throws IOException {
        start();
        return loop();
    }

    /** Receives until stopped, the duration is reached or something fails; then {@link #shutdown()}. */
    End loop() {
        try {
            boolean running = true;
            while (running) {
                running = step();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            end.compareAndSet(null, End.STOPPED);
        } finally {
            shutdown();
        }
        return end.get();
    }

    /**
     * One iteration: waits for one frame (at most until the next flush, summary or end), writes it, then
     * flushes and prints the summary when due. Returns false once the logger should stop.
     */
    boolean step() throws InterruptedException {
        if (!started) {
            throw new IllegalStateException("not started");
        }
        if (stopRequested || end.get() != null) {
            end.compareAndSet(null, End.STOPPED);
            return false;
        }
        long now = time.nanoTime();
        if (now >= endNanos) {
            end.compareAndSet(null, End.DURATION_REACHED);
            return false;
        }
        long wakeAt = Math.min(Math.min(nextFlushNanos, nextSummaryNanos), endNanos);
        Optional<TimestampedFrame> received;
        try {
            received = adapter.receive(Duration.ofNanos(Math.max(0, wakeAt - now)));
        } catch (CanAdapterException e) {
            if (stopRequested) {
                end.compareAndSet(null, End.STOPPED); // shutdown closed the adapter under us
            } else if (end.compareAndSet(null, End.ADAPTER_FAILED)) {
                console.println(PREFIX + "adapter failed: " + e.getMessage());
            }
            return false;
        }
        if (received.isPresent() && !write(received.get())) {
            return false;
        }
        now = time.nanoTime();
        if (now >= nextFlushNanos) {
            if (!flush()) {
                return false;
            }
            nextFlushNanos = now + timing.flushInterval().toNanos();
        }
        if (now >= nextSummaryNanos) {
            printSummary(now);
            nextSummaryNanos = now + timing.summaryInterval().toNanos();
        }
        return true;
    }

    private boolean write(TimestampedFrame frame) {
        synchronized (writerLock) {
            if (writer == null) {
                end.compareAndSet(null, End.STOPPED); // already shut down
                return false;
            }
            try {
                writer.write(frame);
            } catch (IOException e) {
                return writeFailed(e);
            }
        }
        frames.incrementAndGet();
        ids.add(idKey(frame.frame()));
        return true;
    }

    private boolean flush() {
        synchronized (writerLock) {
            if (writer == null) {
                end.compareAndSet(null, End.STOPPED);
                return false;
            }
            try {
                writer.flush();
            } catch (IOException e) {
                return writeFailed(e);
            }
        }
        return true;
    }

    private boolean writeFailed(IOException e) {
        writeErrors.incrementAndGet();
        if (end.compareAndSet(null, End.WRITE_FAILED)) {
            console.println(PREFIX + "could not write " + outputName + ": " + e.getMessage());
        }
        return false;
    }

    private static long idKey(CanFrame frame) {
        return frame.isExtended() ? (1L << 32) | frame.id() : frame.id();
    }

    private void printSummary(long now) {
        long total = frames.get();
        double seconds = (now - lastSummaryNanos) / 1e9;
        double rate = seconds > 0 ? (total - lastSummaryFrames) / seconds : 0;
        console.println(String.format(Locale.ROOT, "%s%s  %.1f frames/s  %d frames  %d IDs  %d errors",
                PREFIX, elapsed(now - startNanos), rate, total, ids.size(), errors()));
        lastSummaryNanos = now;
        lastSummaryFrames = total;
    }

    /**
     * Stops the logger: closes the adapter (waking a receive blocked in {@link #loop()}), flushes and closes
     * the capture file, and prints the final stats. This is the Ctrl-C hook. Safe to call more than once and
     * from any thread; later calls wait for the first to finish and then do nothing.
     */
    void shutdown() {
        synchronized (shutdownLock) {
            if (shutDown) {
                return;
            }
            shutDown = true;
            stopRequested = true; // before closing, so the loop reads the close as a stop, not a failure
            adapter.close();
            if (!started) {
                return;
            }
            end.compareAndSet(null, End.STOPPED);
            synchronized (writerLock) {
                if (writer != null) {
                    try {
                        writer.flush();
                    } catch (IOException e) {
                        writeErrors.incrementAndGet();
                        console.println(PREFIX + "could not flush " + outputName + ": " + e.getMessage());
                    }
                    closeQuietly(writer);
                    writer = null;
                }
            }
            printFinalStats();
        }
    }

    private void printFinalStats() {
        long elapsedNanos = time.nanoTime() - startNanos;
        long total = frames.get();
        double seconds = elapsedNanos / 1e9;
        console.println(String.format(Locale.ROOT,
                "%s%s after %s: %d frames, %d distinct IDs, %d errors, %.1f frames/s average. Capture: %s",
                PREFIX, end.get().text, elapsed(elapsedNanos), total, ids.size(), errors(),
                seconds > 0 ? total / seconds : 0, outputName));
    }

    private void closeQuietly(CaptureWriter w) {
        try {
            w.close();
        } catch (IOException e) {
            writeErrors.incrementAndGet();
            console.println(PREFIX + "could not close " + outputName + ": " + e.getMessage());
        }
    }

    private static String elapsed(long nanos) {
        long s = Math.max(0, nanos) / 1_000_000_000L;
        return String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
    }

    /** Frames written so far. */
    long frameCount() {
        return frames.get();
    }

    /** Distinct CAN IDs seen so far (standard and extended counted separately). */
    int distinctIdCount() {
        return ids.size();
    }

    /** Adapter errors plus capture write errors. */
    long errors() {
        return adapterErrors.getAsLong() + writeErrors.get();
    }

    /** Why the logger stopped, or empty while running. */
    Optional<End> end() {
        return Optional.ofNullable(end.get());
    }
}
