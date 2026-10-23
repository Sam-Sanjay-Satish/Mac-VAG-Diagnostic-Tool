package io.github.samsanjaysatish.macvagdiag.transport;

import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link DiagnosticChannel} every caller gets: a raw channel behind the {@link RequestGuard}. The payload is
 * copied once, the copy is checked, and that same copy is what gets sent, so a caller changing its array after
 * the check cannot change what reaches the bus.
 */
final class GuardedChannel implements DiagnosticChannel {

    private static final Logger log = LoggerFactory.getLogger(GuardedChannel.class);

    private final RawChannel raw;
    private final ModuleRoute route;
    private final RequestAuditListener audit;

    GuardedChannel(RawChannel raw, ModuleRoute route, RequestAuditListener audit) {
        this.raw = Objects.requireNonNull(raw, "raw");
        this.route = Objects.requireNonNull(route, "route");
        this.audit = Objects.requireNonNull(audit, "audit");
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
    public Response request(byte[] payload, Duration timeout) throws InterruptedException {
        byte[] copy = payload == null ? null : payload.clone();
        try {
            RequestGuard.check(protocol(), copy);
        } catch (ForbiddenRequestException rejection) {
            try {
                audit.rejected(route, rejection);
            } catch (RuntimeException listenerFailure) {
                log.error("Audit listener failed while recording a rejected request", listenerFailure);
                rejection.addSuppressed(listenerFailure);
            }
            throw rejection;
        }
        checkTimeout(timeout);
        if (!raw.isOpen()) {
            return closed();
        }
        // If the audit record can't be written, this throws and nothing is sent.
        audit.sending(route, protocol(), copy.clone());
        return raw.exchange(copy, timeout);
    }

    @Override
    public Response awaitResponse(Duration timeout) throws InterruptedException {
        checkTimeout(timeout);
        if (!raw.isOpen()) {
            return closed();
        }
        return raw.awaitResponse(timeout);
    }

    @Override
    public boolean keepAlive() throws InterruptedException {
        return raw.isOpen() && raw.keepAlive();
    }

    @Override
    public boolean isOpen() {
        return raw.isOpen();
    }

    @Override
    public void close() {
        raw.close();
    }

    private static void checkTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("Negative timeout: " + timeout);
        }
    }

    private Response closed() {
        return new Response.Failed(Response.Failed.Kind.CHANNEL_CLOSED, "Channel to " + route + " is closed");
    }

    @Override
    public String toString() {
        return "GuardedChannel[" + route + "]";
    }
}
