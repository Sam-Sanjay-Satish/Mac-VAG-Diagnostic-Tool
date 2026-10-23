package io.github.samsanjaysatish.macvagdiag.scan;

import io.github.samsanjaysatish.macvagdiag.can.AdapterMode;
import io.github.samsanjaysatish.macvagdiag.can.Bitrate;
import io.github.samsanjaysatish.macvagdiag.can.CanAdapter;
import io.github.samsanjaysatish.macvagdiag.can.CanAdapterException;
import io.github.samsanjaysatish.macvagdiag.can.Hex;
import io.github.samsanjaysatish.macvagdiag.can.TimeSource;
import io.github.samsanjaysatish.macvagdiag.diag.DiagnosticService;
import io.github.samsanjaysatish.macvagdiag.transport.ApplicationProtocol;
import io.github.samsanjaysatish.macvagdiag.transport.ChannelFactory;
import io.github.samsanjaysatish.macvagdiag.transport.DiagnosticChannel;
import io.github.samsanjaysatish.macvagdiag.transport.ForbiddenRequestException;
import io.github.samsanjaysatish.macvagdiag.transport.ModuleRoute;
import io.github.samsanjaysatish.macvagdiag.transport.RequestAuditListener;
import io.github.samsanjaysatish.macvagdiag.transport.TransportException;
import java.util.Objects;
import java.util.Optional;

/**
 * <b>WIRING</b> (one of the three scan classes allowed to touch can-core and the transport's channel factory; see
 * {@code NoBypassSourceTest}). The real {@link ModuleConnector}: guarded transport channels from one
 * {@link ChannelFactory} on the one adapter, with {@link DiagnosticService#forChannel} on top.
 *
 * <p>Every request still passes transport's read-only {@code RequestGuard}; this class adds no way around it. It
 * never calls {@code request}, {@code awaitResponse} or {@code send}: only diag does, through the channel. Its
 * {@link RequestAuditListener} forwards every request (before it is sent) and every rejection to scan's
 * {@link RequestObserver}, which audits and budget-checks it; if that throws, the transport sends nothing.
 */
final class ChannelConnector implements ModuleConnector {

    private final CanAdapter adapter;
    private final TimeSource time;
    private final Bitrate bitrate;
    private final ChannelFactory factory;
    private final AuditForwarder forwarder = new AuditForwarder();

    ChannelConnector(CanAdapter adapter, TimeSource time, Bitrate bitrate) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.time = Objects.requireNonNull(time, "time");
        this.bitrate = Objects.requireNonNull(bitrate, "bitrate");
        this.factory = ChannelFactory.create(adapter, time, forwarder);
    }

    /** The listener registered on the factory (tests use it to prove rejections are audited). */
    RequestAuditListener auditListener() {
        return forwarder;
    }

    @Override
    public void attach(RequestObserver observer) {
        forwarder.observer = Objects.requireNonNull(observer, "observer");
    }

    @Override
    public AdapterCheck checkAdapter() {
        if (adapter.isOpen()) {
            Optional<AdapterMode> mode = adapter.mode();
            if (mode.equals(Optional.of(AdapterMode.NORMAL))) {
                return new AdapterCheck(true, "open at " + bitrate.bitsPerSecond() / 1000 + " kbit/s");
            }
            return new AdapterCheck(false, "the adapter is open in " + mode.map(Enum::name).orElse("?")
                    + " mode; diagnostics need NORMAL");
        }
        try {
            adapter.open(bitrate, AdapterMode.NORMAL);
            return new AdapterCheck(true, "opened at " + bitrate.bitsPerSecond() / 1000 + " kbit/s");
        } catch (CanAdapterException e) {
            return new AdapterCheck(false, "could not open the adapter: " + e.getMessage());
        }
    }

    @Override
    public ModuleConnection connect(ModuleLink link) throws InterruptedException, ConnectFailedException {
        ModuleRoute route = route(link);
        try {
            DiagnosticChannel channel = factory.open(route);
            return new Connection(link, channel, DiagnosticService.forChannel(channel, time));
        } catch (TransportException e) {
            throw new ConnectFailedException(switch (e.reason()) {
                case NO_RESPONSE -> ConnectFailedException.Kind.NO_RESPONSE;
                case REFUSED_BY_MODULE -> ConnectFailedException.Kind.REFUSED;
                case ADAPTER_NOT_READY, ADAPTER_ERROR -> ConnectFailedException.Kind.ADAPTER;
                case DISALLOWED_CAN_ID, CHANNEL_ALREADY_OPEN, PROTOCOL_ERROR -> ConnectFailedException.Kind.PROTOCOL;
            }, e.getMessage());
        } catch (CanAdapterException e) {
            throw new ConnectFailedException(ConnectFailedException.Kind.ADAPTER, e.getMessage());
        }
    }

    /** scan's link → transport's route. UNCONFIRMED mapping: see {@link ModuleAddress}. */
    static ModuleRoute route(ModuleLink link) {
        return switch (link) {
            case ModuleLink.Tp20 t -> new ModuleRoute.Tp20(t.logicalAddress());
            case ModuleLink.IsoTp i -> new ModuleRoute.IsoTp(i.requestId(), i.responseId(), switch (i.protocol()) {
                case KWP2000 -> ApplicationProtocol.KWP2000;
                case UDS -> ApplicationProtocol.UDS;
            });
        };
    }

    @Override
    public void adapterFailed(String detail) {
        adapter.close(); // the next checkAdapter() tries to reopen it (e.g. after the CANable is plugged back in)
    }

    @Override
    public void close() {
        adapter.close();
    }

    /** Forwards transport's audit hooks to scan's observer as plain strings. */
    private static final class AuditForwarder implements RequestAuditListener {
        private volatile RequestObserver observer;

        @Override
        public void sending(ModuleRoute route, ApplicationProtocol protocol, byte[] payload) {
            RequestObserver o = observer;
            if (o == null) {
                throw new IllegalStateException("No audit observer attached; not sending " + Hex.format(payload));
            }
            o.sending(route.toString(), protocol.name(), Hex.format(payload));
        }

        @Override
        public void rejected(ModuleRoute route, ForbiddenRequestException rejection) {
            RequestObserver o = observer;
            if (o != null) {
                o.rejected(route.toString(), rejection.protocol().map(Enum::name).orElse("none"),
                        Hex.format(rejection.payload()), rejection.reason());
            }
        }
    }

    private record Connection(ModuleLink link, DiagnosticChannel channel, DiagnosticService diagnostics)
            implements ModuleConnection {

        @Override
        public boolean hasChannelKeepAlive() {
            return link instanceof ModuleLink.Tp20;
        }

        @Override
        public boolean keepChannelAlive() throws InterruptedException {
            return channel.keepAlive();
        }

        @Override
        public boolean isOpen() {
            return channel.isOpen();
        }

        @Override
        public void close() {
            channel.close();
        }
    }
}
