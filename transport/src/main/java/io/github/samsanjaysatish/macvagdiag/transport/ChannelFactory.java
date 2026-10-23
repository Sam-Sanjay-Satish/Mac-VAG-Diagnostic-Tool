package io.github.samsanjaysatish.macvagdiag.transport;

import io.github.samsanjaysatish.macvagdiag.can.AdapterMode;
import io.github.samsanjaysatish.macvagdiag.can.CanAdapter;
import io.github.samsanjaysatish.macvagdiag.can.TimeSource;
import java.util.Objects;
import java.util.Optional;

/**
 * Opens {@link DiagnosticChannel}s to modules. <b>Every channel it returns is wrapped by the
 * {@link RequestGuard}</b>; there is no way to get an unguarded one.
 *
 * <p>Created once at app startup ({@code server} / {@code mcp}) with the one real adapter, already open in
 * {@link AdapterMode#NORMAL}, and shared through {@code scan}. Only one channel is open at a time: channels read
 * the adapter directly and would steal each other's frames, and TP 2.0 channels would share receive IDs.
 */
public final class ChannelFactory {

    private final CanAdapter adapter;
    private final TimeSource time;
    private final RequestAuditListener audit;
    private final Object lock = new Object();
    private DiagnosticChannel current;

    private ChannelFactory(CanAdapter adapter, TimeSource time, RequestAuditListener audit) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.time = Objects.requireNonNull(time, "time");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /**
     * @param adapter the CAN adapter; must be open in {@code NORMAL} mode when channels are opened
     * @param time the clock for every transport timeout; use the same one as the adapter
     * @param audit receives every request sent and every request the guard rejects
     */
    public static ChannelFactory create(CanAdapter adapter, TimeSource time, RequestAuditListener audit) {
        return new ChannelFactory(adapter, time, audit);
    }

    /** A factory with no audit listener. */
    public static ChannelFactory create(CanAdapter adapter, TimeSource time) {
        return new ChannelFactory(adapter, time, RequestAuditListener.NONE);
    }

    /**
     * Opens a guarded channel to one module. For TP 2.0 this runs the channel setup ({@code C0}/{@code D0}) and
     * timing exchange ({@code A0}/{@code A1}); ISO-TP sends nothing until the first request.
     *
     * @throws TransportException if the module doesn't answer or refuses, the route's CAN IDs are outside the
     *     expected diagnostic set, the adapter isn't ready, or another channel is still open
     */
    public DiagnosticChannel open(ModuleRoute route) throws InterruptedException {
        Objects.requireNonNull(route, "route");
        synchronized (lock) {
            if (current != null && current.isOpen()) {
                throw new TransportException(TransportException.Reason.CHANNEL_ALREADY_OPEN,
                        "Close the channel to " + current.route() + " before opening one to " + route);
            }
            if (!adapter.isOpen() || !adapter.mode().equals(Optional.of(AdapterMode.NORMAL))) {
                throw new TransportException(TransportException.Reason.ADAPTER_NOT_READY,
                        "The CAN adapter must be open in NORMAL mode, but is " + adapter.mode().map(Enum::name).orElse("closed"));
            }
            BusGate bus = new BusGate(adapter, time);
            RawChannel raw = switch (route) {
                case ModuleRoute.Tp20 tp20 -> Tp20Channel.connect(bus, tp20.moduleAddress());
                case ModuleRoute.IsoTp isoTp -> IsoTpChannel.open(bus, isoTp.requestId(), isoTp.responseId(),
                        IsoTpChannel.Parameters.DEFAULTS);
            };
            DiagnosticChannel channel = new GuardedChannel(raw, route, audit);
            current = channel;
            return channel;
        }
    }
}
