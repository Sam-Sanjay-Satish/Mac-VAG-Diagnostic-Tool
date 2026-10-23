package io.github.samsanjaysatish.macvagdiag.scan;

import io.github.samsanjaysatish.macvagdiag.diag.DiagnosticService;

/**
 * The seam between scan and the layers below: opens a {@link DiagnosticService} plus a transport keep-alive handle
 * for one module link. The real implementation is {@link ChannelConnector} (guarded transport channels on the one
 * adapter); tests use {@code FakeModuleConnector} over {@code FakeDiagnosticService}.
 *
 * <p>Called only on the worker thread, one connection at a time.
 */
interface ModuleConnector extends AutoCloseable {

    /** Whether the adapter can be used. */
    record AdapterCheck(boolean connected, String detail) {
    }

    /** Checks the adapter, opening it if it is closed. Never throws for adapter problems. */
    AdapterCheck checkAdapter();

    /** Registers the observer that audits and budgets every request (called once, at startup). */
    void attach(RequestObserver observer);

    /**
     * Opens a channel to the module on {@code link}.
     *
     * @throws ConnectFailedException if the module didn't answer or refused, or the adapter failed
     */
    ModuleConnection connect(ModuleLink link) throws InterruptedException, ConnectFailedException;

    /** The adapter failed (e.g. unplugged): close it so the next {@link #checkAdapter} tries to reopen it. */
    void adapterFailed(String detail);

    /** Closes the adapter. */
    @Override
    void close();

    /** One open channel to one module. */
    interface ModuleConnection extends AutoCloseable {

        ModuleLink link();

        DiagnosticService diagnostics();

        /** True if the transport needs its own keep-alive (TP 2.0 {@code A3}); ISO-TP doesn't. */
        boolean hasChannelKeepAlive();

        /** Sends the transport keep-alive; false (and the channel is closed) if the module didn't answer. */
        boolean keepChannelAlive() throws InterruptedException;

        boolean isOpen();

        /** Closes the channel (TP 2.0 sends {@code A8}). Never throws. */
        @Override
        void close();
    }

    /** Opening a channel failed. */
    final class ConnectFailedException extends Exception {

        private static final long serialVersionUID = 1L;

        enum Kind {
            /** No answer to the channel setup: usually no module at this address on this link. */
            NO_RESPONSE,
            /** The module refused the channel. */
            REFUSED,
            /** The module answered strangely (malformed reply, unexpected CAN ID). */
            PROTOCOL,
            /** The adapter isn't usable. */
            ADAPTER
        }

        private final Kind kind;

        ConnectFailedException(Kind kind, String message) {
            super(message);
            this.kind = kind;
        }

        Kind kind() {
            return kind;
        }
    }
}
