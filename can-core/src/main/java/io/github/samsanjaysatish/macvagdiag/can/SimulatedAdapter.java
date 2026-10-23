package io.github.samsanjaysatish.macvagdiag.can;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A scripted fake bus with a fake ECU on it. A {@link Responder} maps each sent frame to zero or more
 * {@link Reply replies}, each with an optional delay on the {@link TimeSource}. Every frame that reaches
 * the simulated wire is recorded for assertions, and errors, delays and dropped replies can be injected.
 *
 * <p>Lives in {@code main} (not tests) so higher layers can use it in their tests and demos. Thread-safe.
 */
public final class SimulatedAdapter extends AbstractCanAdapter {

    /** The fake ECU: what comes back on the bus after {@code sent}. */
    @FunctionalInterface
    public interface Responder {
        List<Reply> respond(CanFrame sent);

        /** A responder that never answers. */
        static Responder silent() {
            return sent -> List.of();
        }
    }

    /** A frame the fake ECU puts on the bus {@code delay} after the request. */
    public record Reply(CanFrame frame, Duration delay) {

        public Reply {
            Objects.requireNonNull(frame, "frame");
            Objects.requireNonNull(delay, "delay");
            if (delay.isNegative()) {
                throw new IllegalArgumentException("Negative delay: " + delay);
            }
        }

        public static Reply now(CanFrame frame) {
            return new Reply(frame, Duration.ZERO);
        }

        public static Reply after(Duration delay, CanFrame frame) {
            return new Reply(frame, delay);
        }
    }

    /** One call to {@link #open}. */
    public record OpenEvent(Bitrate bitrate, AdapterMode mode) {
    }

    private final TimeSource time;
    private final Object lock = new Object();
    private Responder responder;
    private final List<CanFrame> sent = new ArrayList<>();
    private final List<OpenEvent> opens = new ArrayList<>();
    private volatile FrameQueue queue;
    private Supplier<? extends RuntimeException> sendFailure;
    private int sendFailuresLeft;
    private Supplier<? extends RuntimeException> openFailure;
    private int repliesToDrop;
    private Predicate<CanFrame> dropReplyIf = frame -> false;

    public SimulatedAdapter(TimeSource time, Responder responder) {
        this.time = Objects.requireNonNull(time, "time");
        this.responder = Objects.requireNonNull(responder, "responder");
        this.queue = new FrameQueue(time);
    }

    /** A simulated bus where nothing answers. */
    public SimulatedAdapter(TimeSource time) {
        this(time, Responder.silent());
    }

    @Override
    void connect(Bitrate bitrate, AdapterMode mode) {
        synchronized (lock) {
            if (openFailure != null) {
                RuntimeException e = openFailure.get();
                openFailure = null;
                throw e;
            }
            opens.add(new OpenEvent(bitrate, mode));
            queue = new FrameQueue(time);
        }
    }

    @Override
    void transmit(CanFrame frame) {
        List<Reply> replies;
        synchronized (lock) {
            if (sendFailuresLeft > 0) {
                sendFailuresLeft--;
                throw sendFailure.get();
            }
            sent.add(frame);
            replies = responder.respond(frame);
        }
        long now = time.nanoTime();
        for (Reply reply : replies) {
            if (shouldDrop(reply.frame())) {
                continue;
            }
            queue.schedule(reply.frame(), now + reply.delay().toNanos(), null);
        }
    }

    private boolean shouldDrop(CanFrame reply) {
        synchronized (lock) {
            if (repliesToDrop > 0) {
                repliesToDrop--;
                return true;
            }
            return dropReplyIf.test(reply);
        }
    }

    @Override
    Optional<TimestampedFrame> awaitFrame(Duration timeout) throws InterruptedException {
        return queue.poll(timeout);
    }

    @Override
    void disconnect() {
        queue.close();
    }

    /** Replaces the fake ECU's behaviour. */
    public void setResponder(Responder responder) {
        synchronized (lock) {
            this.responder = Objects.requireNonNull(responder, "responder");
        }
    }

    /** Puts a frame on the bus now, as if another node sent it. Requires the adapter to be open. */
    public void inject(CanFrame frame) {
        injectAfter(Duration.ZERO, frame);
    }

    /** Puts a frame on the bus {@code delay} from now. */
    public void injectAfter(Duration delay, CanFrame frame) {
        Objects.requireNonNull(frame, "frame");
        if (!isOpen()) {
            throw new CanAdapterException(name() + " is not open");
        }
        queue.schedule(frame, time.nanoTime() + delay.toNanos(), null);
    }

    /** The next {@code count} sends throw {@code error.get()} and reach neither the wire nor the responder. */
    public void failNextSends(int count, Supplier<? extends RuntimeException> error) {
        synchronized (lock) {
            this.sendFailuresLeft = count;
            this.sendFailure = Objects.requireNonNull(error, "error");
        }
    }

    /** The next {@link #open} throws {@code error.get()} and the adapter stays closed. */
    public void failNextOpen(Supplier<? extends RuntimeException> error) {
        synchronized (lock) {
            this.openFailure = Objects.requireNonNull(error, "error");
        }
    }

    /** The adapter breaks (e.g. unplugged): pending and future receives throw {@code error}. */
    public void failReceive(CanAdapterException error) {
        queue.fail(Objects.requireNonNull(error, "error"));
    }

    /** The next {@code count} reply frames from the responder are lost. */
    public void dropNextReplies(int count) {
        synchronized (lock) {
            this.repliesToDrop = count;
        }
    }

    /** Every reply frame matching {@code condition} is lost, until replaced. */
    public void dropRepliesWhere(Predicate<CanFrame> condition) {
        synchronized (lock) {
            this.dropReplyIf = Objects.requireNonNull(condition, "condition");
        }
    }

    /** Every frame that reached the simulated wire, in order. Frames refused by a guard or failure are not here. */
    public List<CanFrame> sentFrames() {
        synchronized (lock) {
            return List.copyOf(sent);
        }
    }

    public void clearSentFrames() {
        synchronized (lock) {
            sent.clear();
        }
    }

    /** Every successful {@link #open}, in order. */
    public List<OpenEvent> openEvents() {
        synchronized (lock) {
            return List.copyOf(opens);
        }
    }

    /** Frames waiting to be received, including delayed ones not yet due. */
    public int pendingFrames() {
        return queue.size();
    }
}
