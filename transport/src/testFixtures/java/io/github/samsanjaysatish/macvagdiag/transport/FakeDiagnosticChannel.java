package io.github.samsanjaysatish.macvagdiag.transport;

import io.github.samsanjaysatish.macvagdiag.can.Hex;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A scripted {@link DiagnosticChannel} for {@code diag} tests: canned responses, no CAN frames.
 *
 * <p><b>It applies the real {@link RequestGuard}</b>, so a request the guard would refuse throws
 * {@link ForbiddenRequestException} here exactly as on a real channel. Every request a {@code diag} test makes is
 * therefore checked against the allowlist.
 *
 * <pre>{@code
 * FakeDiagnosticChannel ch = FakeDiagnosticChannel.uds()
 *         .on("19 02 FF", "7F 19 78", "59 02 FF 04 01 00 2F");  // first answer from request(), the rest from awaitResponse()
 * ch.onTimeout("22 F1 90");
 * }</pre>
 *
 * <p>Each {@code on...} call scripts one exchange for those request bytes; scripting the same request again
 * queues another exchange, used in order. An unscripted request throws {@link IllegalStateException}.
 * {@link #awaitResponse} returns the follow-up responses of the latest exchange, then {@link Response.TimedOut}.
 * Every call (with its timeout) is recorded in {@link #calls()}. Not thread-safe.
 */
public final class FakeDiagnosticChannel implements DiagnosticChannel {

    /** One recorded call. {@code payload} is empty for {@link Kind#AWAIT} and {@link Kind#KEEP_ALIVE}. */
    public record Call(Kind kind, byte[] payload, Duration timeout) {

        public enum Kind { REQUEST, AWAIT, KEEP_ALIVE }

        public Call {
            payload = payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }

        /** The request as bus-style hex, e.g. {@code 19 02 FF}. */
        public String hex() {
            return Hex.format(payload);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Call other && kind == other.kind && Arrays.equals(payload, other.payload)
                    && Objects.equals(timeout, other.timeout);
        }

        @Override
        public int hashCode() {
            return Objects.hash(kind, Arrays.hashCode(payload), timeout);
        }

        @Override
        public String toString() {
            return kind + (payload.length == 0 ? "" : " " + hex()) + (timeout == null ? "" : " " + timeout);
        }
    }

    private final ModuleRoute route;
    private final Map<String, Deque<List<Response>>> script = new HashMap<>();
    private final Deque<Response> followUps = new ArrayDeque<>();
    private final List<Call> calls = new ArrayList<>();
    private boolean open = true;
    private boolean keepAliveResult = true;

    public FakeDiagnosticChannel(ModuleRoute route) {
        this.route = Objects.requireNonNull(route, "route");
    }

    /** A KWP2000 channel, as if to TP 2.0 module {@code 01}. */
    public static FakeDiagnosticChannel kwp() {
        return new FakeDiagnosticChannel(new ModuleRoute.Tp20(0x01));
    }

    /** A UDS channel, as if to the engine on {@code 7E0 / 7E8}. */
    public static FakeDiagnosticChannel uds() {
        return new FakeDiagnosticChannel(new ModuleRoute.IsoTp(0x7E0, 0x7E8, ApplicationProtocol.UDS));
    }

    /** Scripts {@code requestHex} to be answered by {@code responsesHex} (each a whole message, bus-style hex). */
    public FakeDiagnosticChannel on(String requestHex, String... responsesHex) {
        Response[] responses = new Response[responsesHex.length];
        for (int i = 0; i < responsesHex.length; i++) {
            responses[i] = new Response.Received(Hex.parse(responsesHex[i]));
        }
        return on(requestHex, responses);
    }

    /** Scripts {@code requestHex} to be answered by arbitrary results (timeouts, failures, messages). */
    public FakeDiagnosticChannel on(String requestHex, Response... responses) {
        if (responses.length == 0) {
            throw new IllegalArgumentException("Script at least one response; use onTimeout for none");
        }
        script.computeIfAbsent(key(Hex.parse(requestHex)), k -> new ArrayDeque<>()).add(List.of(responses));
        return this;
    }

    /** Scripts {@code requestHex} to time out. */
    public FakeDiagnosticChannel onTimeout(String requestHex) {
        script.computeIfAbsent(key(Hex.parse(requestHex)), k -> new ArrayDeque<>()).add(List.of());
        return this;
    }

    /** What {@link #keepAlive()} returns from now on; false also closes the channel, as a real one would. */
    public FakeDiagnosticChannel keepAliveResult(boolean alive) {
        this.keepAliveResult = alive;
        return this;
    }

    @Override
    public ModuleRoute route() {
        return route;
    }

    @Override
    public ApplicationProtocol protocol() {
        return route.protocol();
    }

    @Override
    public Response request(byte[] payload, Duration timeout) {
        byte[] copy = payload == null ? null : payload.clone();
        RequestGuard.check(protocol(), copy); // same guard as a real channel; throws before anything is recorded
        Objects.requireNonNull(timeout, "timeout");
        calls.add(new Call(Call.Kind.REQUEST, copy, timeout));
        if (!open) {
            return closed();
        }
        Deque<List<Response>> queued = script.get(key(copy));
        if (queued == null || queued.isEmpty()) {
            throw new IllegalStateException("No scripted response for " + protocol() + " request " + Hex.format(copy));
        }
        List<Response> responses = queued.poll();
        followUps.clear();
        if (responses.isEmpty()) {
            return new Response.TimedOut(timeout);
        }
        followUps.addAll(responses.subList(1, responses.size()));
        return responses.getFirst();
    }

    @Override
    public Response awaitResponse(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        calls.add(new Call(Call.Kind.AWAIT, new byte[0], timeout));
        if (!open) {
            return closed();
        }
        Response next = followUps.poll();
        return next != null ? next : new Response.TimedOut(timeout);
    }

    @Override
    public boolean keepAlive() {
        calls.add(new Call(Call.Kind.KEEP_ALIVE, new byte[0], null));
        if (!keepAliveResult) {
            open = false;
        }
        return open;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() {
        open = false;
    }

    /** Every call so far, in order. */
    public List<Call> calls() {
        return List.copyOf(calls);
    }

    /** The request payloads sent so far, as bus-style hex. */
    public List<String> requestsHex() {
        return calls.stream().filter(c -> c.kind() == Call.Kind.REQUEST).map(Call::hex).toList();
    }

    /** True if every scripted exchange was used. */
    public boolean allScriptUsed() {
        return script.values().stream().allMatch(Deque::isEmpty);
    }

    private Response closed() {
        return new Response.Failed(Response.Failed.Kind.CHANNEL_CLOSED, "Fake channel to " + route + " is closed");
    }

    private static String key(byte[] payload) {
        return Hex.formatCompact(payload);
    }
}
