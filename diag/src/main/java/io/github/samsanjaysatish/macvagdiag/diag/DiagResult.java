package io.github.samsanjaysatish.macvagdiag.diag;

import io.github.samsanjaysatish.macvagdiag.transport.Response;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * The outcome of one {@link DiagnosticService} operation. Every operation returns one of these; none of them
 * throws for anything the module or the transport does.
 *
 * <p>What is <b>not</b> a result: a {@code ForbiddenRequestException} from the channel. It can't happen by
 * construction (every request this layer builds is checked against the allowlist when it is built), and if it
 * ever does it is a bug, so it propagates to the caller unchanged and is never caught or turned into a result.
 *
 * <p>Retry policy belongs to {@code scan}: this layer never repeats a request. {@link #retryable()} is true only
 * where the module itself asked for a repeat ({@code 7F xx 21}).
 */
public sealed interface DiagResult<T> {

    /** The module answered positively; {@code value} is the decoded answer. */
    record Success<T>(T value) implements DiagResult<T> {

        public Success {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public String message() {
            return "OK";
        }
    }

    /**
     * The module refused with a negative response: {@code 7F [SID] [NRC]}, e.g. {@code 11} not supported,
     * {@code 12} sub-function not supported, {@code 22} conditions not correct, {@code 31} out of range. Not
     * {@code 21} (that is {@link Busy}) and not {@code 78} (handled inside this layer).
     */
    record Negative<T>(NegativeResponse response) implements DiagResult<T> {

        public Negative {
            Objects.requireNonNull(response, "response");
            if (response.code() == Nrc.BUSY_REPEAT_REQUEST.code() || response.code() == Nrc.RESPONSE_PENDING.code()) {
                throw new IllegalArgumentException("NRC " + response.raw().hex() + " is not a plain negative response");
            }
        }

        @Override
        public String message() {
            return "Negative response " + response.message();
        }
    }

    /**
     * The module answered {@code 7F [SID] 21} (busy, repeat request). Retryable: {@code scan}'s back-off decides
     * whether and when; this layer did not repeat it.
     */
    record Busy<T>(NegativeResponse response) implements DiagResult<T> {

        public Busy {
            Objects.requireNonNull(response, "response");
            if (response.code() != Nrc.BUSY_REPEAT_REQUEST.code()) {
                throw new IllegalArgumentException("Busy needs NRC 21, got " + response.raw().hex());
            }
        }

        @Override
        public String message() {
            return "Module busy, retryable: " + response.message();
        }
    }

    /**
     * No answer arrived in time.
     *
     * @param waited the wait that ran out (the request timeout, or the response-pending timeout after a {@code 78})
     * @param pendingResponses how many {@code 7F xx 78} came before the silence
     */
    record TimedOut<T>(Duration waited, int pendingResponses) implements DiagResult<T> {

        public TimedOut {
            Objects.requireNonNull(waited, "waited");
        }

        @Override
        public String message() {
            return "No response within " + waited.toMillis() + " ms"
                    + (pendingResponses > 0 ? " after " + pendingResponses + " 'response pending' (78)" : "");
        }
    }

    /**
     * The module kept answering {@code 7F [SID] 78} (response pending) past this layer's cap, either too many
     * times or for too long in total (see {@link DiagTimings}). Stopped so a module can't hang the scan.
     *
     * @param pendingResponses how many {@code 78}s were received
     * @param limit which cap was hit, readable
     */
    record PendingLimitExceeded<T>(int pendingResponses, String limit) implements DiagResult<T> {

        public PendingLimitExceeded {
            Objects.requireNonNull(limit, "limit");
        }

        @Override
        public String message() {
            return "Gave up after " + pendingResponses + " 'response pending' (78) answers: " + limit;
        }
    }

    /** The transport failed (channel closed, module disconnected, protocol error, adapter error). */
    record TransportFailure<T>(Response.Failed.Kind kind, String detail) implements DiagResult<T> {

        public TransportFailure {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(detail, "detail");
        }

        @Override
        public String message() {
            return "Transport failure " + kind + ": " + detail;
        }
    }

    /**
     * The module answered, but not in a shape this layer can use at all (wrong service echo, header too short).
     * The whole message is kept in {@code response}.
     */
    record Malformed<T>(String reason, RawBytes response) implements DiagResult<T> {

        public Malformed {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(response, "response");
        }

        @Override
        public String message() {
            return "Unexpected response " + (response.isEmpty() ? "(empty)" : response.hex()) + ": " + reason;
        }
    }

    /**
     * Nothing was sent: this operation can't be done with the requests on the approved read-only allowlist (or
     * isn't known yet). {@code reason} says why.
     */
    record NotAvailable<T>(String reason) implements DiagResult<T> {

        public NotAvailable {
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public String message() {
            return "Not available: " + reason;
        }
    }

    /** A readable one-line summary, for logs, reports and MCP output. */
    String message();

    default boolean isSuccess() {
        return this instanceof Success<T>;
    }

    /** The value if this is a {@link Success}. */
    default Optional<T> valueIfSuccess() {
        return this instanceof Success<T> s ? Optional.of(s.value()) : Optional.empty();
    }

    /** True only for {@link Busy}: the module asked for the request to be repeated. */
    default boolean retryable() {
        return this instanceof Busy<T>;
    }

    /** Converts a success's value; any other result is passed on unchanged (retyped). */
    default <U> DiagResult<U> map(Function<? super T, ? extends U> f) {
        if (this instanceof Success<T> s) {
            return new Success<>(f.apply(s.value()));
        }
        return failure();
    }

    /**
     * This non-success result, retyped, e.g. to pass a {@code DiagResult<RawBytes>} timeout on as a
     * {@code DiagResult<DtcList>}.
     *
     * @throws IllegalStateException if this is a {@link Success}
     */
    default <U> DiagResult<U> failure() {
        return switch (this) {
            case Success<T> s -> throw new IllegalStateException("Not a failure: " + s);
            case Negative<T> n -> new Negative<>(n.response());
            case Busy<T> b -> new Busy<>(b.response());
            case TimedOut<T> t -> new TimedOut<>(t.waited(), t.pendingResponses());
            case PendingLimitExceeded<T> p -> new PendingLimitExceeded<>(p.pendingResponses(), p.limit());
            case TransportFailure<T> f -> new TransportFailure<>(f.kind(), f.detail());
            case Malformed<T> m -> new Malformed<>(m.reason(), m.response());
            case NotAvailable<T> n -> new NotAvailable<>(n.reason());
        };
    }
}
