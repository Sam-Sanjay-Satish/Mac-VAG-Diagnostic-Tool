package io.github.samsanjaysatish.macvagdiag.transport;

import java.time.Duration;

/**
 * The request/response link to one module, as {@code diag} sees it: whole diagnostic messages in and out,
 * no CAN frames.
 *
 * <p>Channels from {@link ChannelFactory} are always wrapped by the {@link RequestGuard}: every payload passed
 * to {@link #request} is checked against the read-only allowlist before anything is segmented or sent, and a
 * request that isn't allowed throws {@link ForbiddenRequestException} with nothing put on the bus.
 *
 * <p>Implementations are not required to be thread-safe beyond serialising calls; {@code scan} owns the
 * single request queue. Tests in {@code diag} use {@code FakeDiagnosticChannel} from this project's test
 * fixtures, which applies the same guard.
 */
public interface DiagnosticChannel extends AutoCloseable {

    /** The module this channel is connected to. */
    ModuleRoute route();

    /** The application protocol this channel carries; the guard checks requests against it. */
    ApplicationProtocol protocol();

    /**
     * Sends one diagnostic request and waits for the response message.
     *
     * <p>Any response that arrived since the last call (e.g. a late answer to a request that timed out) is
     * discarded first, so the result belongs to this request. {@code timeout} is measured on the factory's
     * {@code TimeSource} from the moment the request has been sent until the whole response has arrived.
     *
     * @param payload the request, starting with the service ID, e.g. {@code 19 02 FF}. Copied before checking.
     * @return the response, a timeout, or a transport failure; never null
     * @throws ForbiddenRequestException if the request is not on the read-only allowlist. Nothing is sent.
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    Response request(byte[] payload, Duration timeout) throws InterruptedException;

    /**
     * Waits for a further response message without sending anything. For {@code diag}'s "response pending"
     * handling (negative response {@code 7F [SID] 78}): the module answers again later, and the request must
     * not be repeated.
     *
     * @return the next response message, a timeout, or a transport failure; never null
     */
    Response awaitResponse(Duration timeout) throws InterruptedException;

    /**
     * Transport-level keep-alive, separate from the application's Tester Present: a TP 2.0 channel test
     * ({@code A3} answered by {@code A1}); nothing on ISO-TP. Sends no diagnostic request. The session layer in
     * {@code scan} calls it while the channel is idle.
     *
     * @return true if the channel is alive; false if the module didn't answer, in which case the channel has
     *     been closed
     */
    boolean keepAlive() throws InterruptedException;

    /** False once closed by either side or after a fatal transport failure. */
    boolean isOpen();

    /** Ends the channel (TP 2.0 sends {@code A8}). Safe to call more than once; never throws. */
    @Override
    void close();
}
