package io.github.samsanjaysatish.macvagdiag.diag;

import io.github.samsanjaysatish.macvagdiag.can.TimeSource;
import io.github.samsanjaysatish.macvagdiag.transport.DiagnosticChannel;
import io.github.samsanjaysatish.macvagdiag.transport.Response;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One request/response exchange on a channel, with negative-response handling. <b>The only place in
 * {@code diag} that calls the channel's {@code request} or {@code awaitResponse}</b> ({@code NoBypassSourceTest}
 * checks this).
 *
 * <ul>
 *   <li>{@code 7F [SID] 78} (response pending): waits for the real answer with {@code awaitResponse}, never
 *       resending. Each wait is {@link DiagTimings#pendingTimeout()}, clipped to what is left of
 *       {@link DiagTimings#maxTotalPendingWait()} (measured on the {@link TimeSource}). More than
 *       {@link DiagTimings#maxPendingResponses()} {@code 78}s, or running out of total time, gives
 *       {@link DiagResult.PendingLimitExceeded}.</li>
 *   <li>{@code 7F [SID] 21}: {@link DiagResult.Busy}. Not retried here.</li>
 *   <li>Any other {@code 7F [SID] [NRC]}: {@link DiagResult.Negative}.</li>
 *   <li>A {@code ForbiddenRequestException} from the channel is never caught: it propagates unchanged.</li>
 * </ul>
 */
final class Exchange {

    private static final Logger log = LoggerFactory.getLogger(Exchange.class);

    private final DiagnosticChannel channel;
    private final TimeSource time;
    private final DiagTimings timings;

    Exchange(DiagnosticChannel channel, TimeSource time, DiagTimings timings) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.time = Objects.requireNonNull(time, "time");
        this.timings = Objects.requireNonNull(timings, "timings");
    }

    DiagnosticChannel channel() {
        return channel;
    }

    /**
     * Sends {@code request} and returns the positive response (the whole message, starting with the positive
     * response SID), or why there isn't one.
     */
    DiagResult<RawBytes> run(DiagRequest request) throws InterruptedException {
        if (request.protocol() != channel.protocol()) {
            throw new IllegalStateException("Request " + request + " on a " + channel.protocol() + " channel");
        }
        // No try/catch: a ForbiddenRequestException must reach the caller unchanged.
        Response response = channel.request(request.bytes(), timings.requestTimeout());
        int pending = 0;
        long pendingStart = 0;
        while (true) {
            switch (response) {
                case Response.TimedOut t -> {
                    if (pending > 0 && elapsedSince(pendingStart).compareTo(timings.maxTotalPendingWait()) >= 0) {
                        return pendingLimit(request, pending, "total wait reached " + timings.maxTotalPendingWait().toMillis() + " ms");
                    }
                    return new DiagResult.TimedOut<>(t.waited(), pending);
                }
                case Response.Failed f -> {
                    return new DiagResult.TransportFailure<>(f.kind(), f.detail());
                }
                case Response.Received r -> {
                    byte[] msg = r.payload();
                    if (!isResponsePending(msg, request)) {
                        return classify(request, msg);
                    }
                    if (pending == 0) {
                        pendingStart = time.nanoTime();
                    }
                    pending++;
                    if (pending > timings.maxPendingResponses()) {
                        return pendingLimit(request, pending, "more than " + timings.maxPendingResponses() + " allowed");
                    }
                    Duration remaining = timings.maxTotalPendingWait().minus(elapsedSince(pendingStart));
                    if (remaining.isNegative() || remaining.isZero()) {
                        return pendingLimit(request, pending, "total wait reached " + timings.maxTotalPendingWait().toMillis() + " ms");
                    }
                    Duration wait = remaining.compareTo(timings.pendingTimeout()) < 0 ? remaining : timings.pendingTimeout();
                    log.debug("{}: response pending (78) #{}, waiting {} ms without resending", request, pending, wait.toMillis());
                    response = channel.awaitResponse(wait);
                }
            }
        }
    }

    private Duration elapsedSince(long startNanos) {
        return Duration.ofNanos(time.nanoTime() - startNanos);
    }

    private static <T> DiagResult<T> pendingLimit(DiagRequest request, int pending, String limit) {
        log.warn("{}: giving up after {} 'response pending' (78) answers: {}", request, pending, limit);
        return new DiagResult.PendingLimitExceeded<>(pending, limit);
    }

    private static boolean isResponsePending(byte[] msg, DiagRequest request) {
        return msg.length >= 3
                && Byte.toUnsignedInt(msg[0]) == DiagRequest.NEGATIVE_RESPONSE_SID
                && Byte.toUnsignedInt(msg[1]) == request.serviceId()
                && Byte.toUnsignedInt(msg[2]) == Nrc.RESPONSE_PENDING.code();
    }

    /** A final (non-78) message: positive, negative, or not usable. */
    static DiagResult<RawBytes> classify(DiagRequest request, byte[] msg) {
        RawBytes raw = new RawBytes(msg);
        if (msg.length == 0) {
            return new DiagResult.Malformed<>("empty response to " + request.hex(), raw);
        }
        int first = Byte.toUnsignedInt(msg[0]);
        if (first == DiagRequest.NEGATIVE_RESPONSE_SID) {
            if (msg.length < 3) {
                return new DiagResult.Malformed<>("negative response too short (need 7F [SID] [NRC]) to " + request.hex(), raw);
            }
            int sid = Byte.toUnsignedInt(msg[1]);
            if (sid != request.serviceId()) {
                return new DiagResult.Malformed<>(String.format("negative response for service %02X, but the request was %s",
                        sid, request.hex()), raw);
            }
            NegativeResponse nr = new NegativeResponse(sid, Byte.toUnsignedInt(msg[2]), raw);
            if (nr.code() == Nrc.BUSY_REPEAT_REQUEST.code()) {
                return new DiagResult.Busy<>(nr);
            }
            return new DiagResult.Negative<>(nr);
        }
        if (first != request.positiveResponseSid()) {
            return new DiagResult.Malformed<>(String.format("expected a response starting %02X to %s",
                    request.positiveResponseSid(), request.hex()), raw);
        }
        return new DiagResult.Success<>(raw);
    }
}
