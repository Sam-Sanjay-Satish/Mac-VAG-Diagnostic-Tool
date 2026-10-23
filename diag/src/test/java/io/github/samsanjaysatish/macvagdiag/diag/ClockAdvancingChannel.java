package io.github.samsanjaysatish.macvagdiag.diag;

import io.github.samsanjaysatish.macvagdiag.can.FakeTimeSource;
import io.github.samsanjaysatish.macvagdiag.transport.ApplicationProtocol;
import io.github.samsanjaysatish.macvagdiag.transport.DiagnosticChannel;
import io.github.samsanjaysatish.macvagdiag.transport.FakeDiagnosticChannel;
import io.github.samsanjaysatish.macvagdiag.transport.ModuleRoute;
import io.github.samsanjaysatish.macvagdiag.transport.Response;
import java.time.Duration;

/**
 * Wraps a {@link FakeDiagnosticChannel} so that each {@code awaitResponse} takes {@code step} of fake time: the
 * next scripted message "arrives" after {@code step}, or, if {@code step} is longer than the caller's timeout, the
 * wait runs out after the timeout. No sleeping: only the {@link FakeTimeSource} moves.
 */
final class ClockAdvancingChannel implements DiagnosticChannel {

    private final FakeDiagnosticChannel delegate;
    private final FakeTimeSource time;
    private final Duration step;

    ClockAdvancingChannel(FakeDiagnosticChannel delegate, FakeTimeSource time, Duration step) {
        this.delegate = delegate;
        this.time = time;
        this.step = step;
    }

    @Override
    public ModuleRoute route() {
        return delegate.route();
    }

    @Override
    public ApplicationProtocol protocol() {
        return delegate.protocol();
    }

    @Override
    public Response request(byte[] payload, Duration timeout) {
        return delegate.request(payload, timeout);
    }

    @Override
    public Response awaitResponse(Duration timeout) {
        if (step.compareTo(timeout) > 0) {
            time.advance(timeout);
            delegate.awaitResponse(timeout); // recorded; the scripted message would come too late
            return new Response.TimedOut(timeout);
        }
        time.advance(step);
        return delegate.awaitResponse(timeout);
    }

    @Override
    public boolean keepAlive() {
        return delegate.keepAlive();
    }

    @Override
    public boolean isOpen() {
        return delegate.isOpen();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
