package io.github.samsanjaysatish.macvagdiag.can;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Plays back a {@link Capture} as if it were a live bus.
 *
 * <ul>
 *   <li><b>Passive</b> ({@link #passive}): every frame is received in order. Sending is refused. For the
 *       logger, decoders and demo mode.</li>
 *   <li><b>Interactive</b> ({@link #interactive}): frames recorded from the tester (chosen by a predicate,
 *       typically on CAN ID) are not received; instead each {@link #send} is checked against the next
 *       recorded tester frame with a {@link FrameMatcher}, and on a match the recorded frames up to the
 *       following tester frame (the ECU's replies) are released. Frames before the first tester frame are
 *       released on open. A mismatch throws {@link ReplayMismatchException} and releases nothing.
 *       <b>Minimal for now:</b> only exact matching exists; tolerant matching is designed after the
 *       sniffing session (see {@code can-core/CLAUDE.md}).</li>
 * </ul>
 *
 * Received frames carry the recorded wall-clock timestamp, and a monotonic timestamp on this adapter's
 * {@link TimeSource}. Each {@link #open} starts the replay from the beginning.
 */
public final class ReplayAdapter extends AbstractCanAdapter {

    /** When replayed frames become available. */
    public enum Pacing {
        /** All at once (passive), or right after the matching send (interactive). */
        IMMEDIATE,
        /** With the recorded gaps, measured on the {@link TimeSource}. */
        RECORDED
    }

    private final List<CapturedFrame> frames;
    private final Pacing pacing;
    private final TimeSource time;
    private final Predicate<CanFrame> isTesterFrame; // null for passive
    private final FrameMatcher matcher; // null for passive

    private final Object lock = new Object();
    private volatile FrameQueue queue;
    private int cursor; // interactive: index of the next recorded tester frame (or frames.size())
    private final List<CanFrame> sent = new ArrayList<>();

    private ReplayAdapter(Capture capture, Pacing pacing, TimeSource time,
                          Predicate<CanFrame> isTesterFrame, FrameMatcher matcher) {
        this.frames = Objects.requireNonNull(capture, "capture").frames();
        this.pacing = Objects.requireNonNull(pacing, "pacing");
        this.time = Objects.requireNonNull(time, "time");
        this.isTesterFrame = isTesterFrame;
        this.matcher = matcher;
        this.queue = new FrameQueue(time);
    }

    /** Plays every frame of {@code capture} back in order. */
    public static ReplayAdapter passive(Capture capture, Pacing pacing, TimeSource time) {
        return new ReplayAdapter(capture, pacing, time, null, null);
    }

    /**
     * Answers sends with the recorded replies.
     *
     * @param isTesterFrame which recorded frames came from the tester (VCDS), e.g. by CAN ID
     * @param matcher how a sent frame is compared with the recorded tester frame
     */
    public static ReplayAdapter interactive(Capture capture, Predicate<CanFrame> isTesterFrame,
                                            FrameMatcher matcher, Pacing pacing, TimeSource time) {
        return new ReplayAdapter(capture, pacing, time,
                Objects.requireNonNull(isTesterFrame, "isTesterFrame"), Objects.requireNonNull(matcher, "matcher"));
    }

    /** Interactive with {@link FrameMatcher#exact()} and {@link Pacing#IMMEDIATE}. */
    public static ReplayAdapter interactive(Capture capture, Predicate<CanFrame> isTesterFrame, TimeSource time) {
        return interactive(capture, isTesterFrame, FrameMatcher.exact(), Pacing.IMMEDIATE, time);
    }

    public boolean isInteractive() {
        return matcher != null;
    }

    @Override
    void connect(Bitrate bitrate, AdapterMode mode) {
        synchronized (lock) {
            FrameQueue q = new FrameQueue(time);
            sent.clear();
            long start = time.nanoTime();
            if (isInteractive()) {
                cursor = releaseUntilTesterFrame(q, 0, start, firstTimestamp());
            } else {
                cursor = frames.size();
                Instant first = firstTimestamp();
                for (CapturedFrame f : frames) {
                    q.schedule(f.frame(), releaseAt(start, first, f.timestamp()), f.timestamp());
                }
            }
            queue = q;
        }
    }

    @Override
    void transmit(CanFrame frame) {
        if (!isInteractive()) {
            throw new CanAdapterException("A passive replay does not accept sent frames: " + frame);
        }
        synchronized (lock) {
            if (cursor >= frames.size()) {
                throw new ReplayMismatchException(null, frame, cursor);
            }
            CapturedFrame expected = frames.get(cursor);
            if (!matcher.matches(expected.frame(), frame)) {
                throw new ReplayMismatchException(expected.frame(), frame, cursor);
            }
            sent.add(frame);
            cursor = releaseUntilTesterFrame(queue, cursor + 1, time.nanoTime(), expected.timestamp());
        }
    }

    /** Schedules recorded frames from {@code from} up to the next tester frame; returns that frame's index. */
    private int releaseUntilTesterFrame(FrameQueue q, int from, long baseNanos, Instant baseTimestamp) {
        int i = from;
        while (i < frames.size() && !isTesterFrame.test(frames.get(i).frame())) {
            CapturedFrame f = frames.get(i);
            q.schedule(f.frame(), releaseAt(baseNanos, baseTimestamp, f.timestamp()), f.timestamp());
            i++;
        }
        return i;
    }

    private long releaseAt(long baseNanos, Instant baseTimestamp, Instant timestamp) {
        if (pacing == Pacing.IMMEDIATE || baseTimestamp == null) {
            return baseNanos;
        }
        long gap = Duration.between(baseTimestamp, timestamp).toNanos();
        return baseNanos + Math.max(0, gap); // captures can contain small backwards steps; never schedule in the past
    }

    private Instant firstTimestamp() {
        return frames.isEmpty() ? null : frames.getFirst().timestamp();
    }

    @Override
    Optional<TimestampedFrame> awaitFrame(Duration timeout) throws InterruptedException {
        return queue.poll(timeout);
    }

    @Override
    void disconnect() {
        queue.close();
    }

    /** Frames accepted by an interactive replay, in order. */
    public List<CanFrame> sentFrames() {
        synchronized (lock) {
            return List.copyOf(sent);
        }
    }

    /**
     * Frames not yet received: queued ones plus, in interactive mode, recorded frames after the next
     * tester frame. Zero once the whole capture has been played.
     */
    public int remainingFrames() {
        synchronized (lock) {
            return queue.size() + (frames.size() - cursor);
        }
    }

    /** In interactive mode, the next recorded tester frame a send must match, if any. */
    public Optional<CanFrame> nextExpectedFrame() {
        synchronized (lock) {
            return isInteractive() && cursor < frames.size() ? Optional.of(frames.get(cursor).frame()) : Optional.empty();
        }
    }
}
