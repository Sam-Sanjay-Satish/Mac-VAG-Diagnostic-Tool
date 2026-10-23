package io.github.samsanjaysatish.macvagdiag.scan;

import io.github.samsanjaysatish.macvagdiag.can.FakeTimeSource;
import io.github.samsanjaysatish.macvagdiag.diag.DiagResult;
import io.github.samsanjaysatish.macvagdiag.diag.DiagnosticService;
import io.github.samsanjaysatish.macvagdiag.diag.DiagnosticSession;
import io.github.samsanjaysatish.macvagdiag.diag.Dtc;
import io.github.samsanjaysatish.macvagdiag.diag.DtcList;
import io.github.samsanjaysatish.macvagdiag.diag.EcuIdentification;
import io.github.samsanjaysatish.macvagdiag.diag.FakeDiagnosticService;
import io.github.samsanjaysatish.macvagdiag.diag.FreezeFrame;
import io.github.samsanjaysatish.macvagdiag.diag.RawBytes;
import io.github.samsanjaysatish.macvagdiag.transport.ApplicationProtocol;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * A fake {@code ModuleConnector} for scan tests: modules are scripted {@link FakeDiagnosticService}s (a fresh one
 * per connection, from a supplier), everything else gets no answer. It records what happened ({@link #events()},
 * with fake-clock times and thread names) and checks the queue's promises: connections and diagnostic calls never
 * overlap ({@link #maxConcurrentConnections()}, {@link #maxConcurrentCalls()}).
 *
 * <pre>{@code
 * FakeModuleConnector car = t.connector();
 * car.kwp("17", () -> FakeDiagnosticService.kwp()
 *         .onStartSession(ok(RawBytes.ofHex("50 89")))
 *         .onReadDtcs(ok(dtcList())));
 * car.uds("01", () -> ...);   // engine on its ISO-TP link
 * }</pre>
 */
public final class FakeModuleConnector implements ModuleConnector {

    /** One thing that happened, e.g. module {@code 17} {@code READ_DTCS} at fake time {@code atNanos}. */
    public record Event(long atNanos, String module, String what, String thread) {
        @Override
        public String toString() {
            return Duration.ofNanos(atNanos).toMillis() + "ms " + module + " " + what;
        }
    }

    private final FakeTimeSource time;
    private final Map<ModuleLink, Supplier<FakeDiagnosticService>> scripted = new ConcurrentHashMap<>();
    private final Set<ModuleLink> keepAliveFails = ConcurrentHashMap.newKeySet();
    private final List<Event> events = Collections.synchronizedList(new ArrayList<>());
    private final List<FakeDiagnosticService> services = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger open = new AtomicInteger();
    private final AtomicInteger maxOpen = new AtomicInteger();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private volatile Duration callDuration = Duration.ZERO;
    private volatile boolean adapterConnected = true;
    private volatile String adapterDetail = "fake adapter";
    private volatile int adapterFailures;
    private volatile RequestObserver observer;

    public FakeModuleConnector(FakeTimeSource time) {
        this.time = time;
    }

    /** A KWP2000 module on its TP 2.0 link. */
    public FakeModuleConnector kwp(String address, Supplier<FakeDiagnosticService> perConnection) {
        ModuleLink link = ModuleAddress.parse(address).links().stream().filter(l -> l instanceof ModuleLink.Tp20)
                .findFirst().orElseThrow();
        return on(link, perConnection);
    }

    /** A UDS module on its ISO-TP link (01 and 02 have one). */
    public FakeModuleConnector uds(String address, Supplier<FakeDiagnosticService> perConnection) {
        ModuleLink link = ModuleAddress.parse(address).links().stream().filter(l -> l instanceof ModuleLink.IsoTp)
                .findFirst().orElseThrow(() -> new IllegalArgumentException(address + " has no ISO-TP link"));
        return on(link, perConnection);
    }

    public FakeModuleConnector on(ModuleLink link, Supplier<FakeDiagnosticService> perConnection) {
        scripted.put(link, perConnection);
        return this;
    }

    /** From now on, connections to this module's TP 2.0 link fail their keep-alive (and close). */
    public FakeModuleConnector keepAliveFails(String address) {
        ModuleAddress.parse(address).links().stream().filter(l -> l instanceof ModuleLink.Tp20).forEach(keepAliveFails::add);
        return this;
    }

    /** Each diagnostic call advances the fake clock by this much (a slow module). */
    public FakeModuleConnector callDuration(Duration d) {
        this.callDuration = d;
        return this;
    }

    public void adapterConnected(boolean connected, String detail) {
        this.adapterConnected = connected;
        this.adapterDetail = detail;
    }

    // ------------------------------------------------------------------ inspection

    public List<Event> events() {
        synchronized (events) {
            return List.copyOf(events);
        }
    }

    /** Events for one module, e.g. {@code "17"}. */
    public List<Event> events(String module) {
        return events().stream().filter(e -> e.module().equals(module)).toList();
    }

    /** The {@code what} of each event for one module, in order. */
    public List<String> whats(String module) {
        return events(module).stream().map(Event::what).toList();
    }

    public long count(String module, String what) {
        return events(module).stream().filter(e -> e.what().equals(what)).count();
    }

    /** Every {@link FakeDiagnosticService} handed out so far. */
    public List<FakeDiagnosticService> services() {
        synchronized (services) {
            return List.copyOf(services);
        }
    }

    public int openConnections() {
        return open.get();
    }

    public int maxConcurrentConnections() {
        return maxOpen.get();
    }

    public int maxConcurrentCalls() {
        return maxInFlight.get();
    }

    public int adapterFailures() {
        return adapterFailures;
    }

    /** Thread names that made diagnostic calls. */
    public Set<String> callThreads() {
        Set<String> names = ConcurrentHashMap.newKeySet();
        events().stream().filter(e -> !e.what().startsWith("connect")).forEach(e -> names.add(e.thread()));
        return names;
    }

    private void record(ModuleLink link, String what) {
        events.add(new Event(time.nanoTime(), moduleOf(link), what, Thread.currentThread().getName()));
    }

    private static String moduleOf(ModuleLink link) {
        for (ModuleAddress m : ModuleAddress.known()) {
            if (m.links().contains(link)) {
                return m.hex();
            }
        }
        return "??";
    }

    // ------------------------------------------------------------------ ModuleConnector

    @Override
    public AdapterCheck checkAdapter() {
        return new AdapterCheck(adapterConnected, adapterDetail);
    }

    @Override
    public void attach(RequestObserver observer) {
        this.observer = observer;
    }

    /** The observer scan registered (tests can feed it fake transport callbacks). */
    public RequestObserver observer() {
        return observer;
    }

    @Override
    public ModuleConnection connect(ModuleLink link) throws ConnectFailedException {
        Supplier<FakeDiagnosticService> s = scripted.get(link);
        if (s == null) {
            record(link, "connect-failed");
            throw new ConnectFailedException(ConnectFailedException.Kind.NO_RESPONSE,
                    "no fake module on " + link.describe());
        }
        record(link, "connect");
        FakeDiagnosticService service = s.get();
        services.add(service);
        int now = open.incrementAndGet();
        maxOpen.accumulateAndGet(now, Math::max);
        return new FakeConnection(link, service);
    }

    @Override
    public void adapterFailed(String detail) {
        adapterFailures++;
    }

    @Override
    public void close() {
        adapterConnected = false;
        adapterDetail = "closed";
    }

    private final class FakeConnection implements ModuleConnection, DiagnosticService {
        private final ModuleLink link;
        private final FakeDiagnosticService fake;
        private boolean isOpen = true;

        FakeConnection(ModuleLink link, FakeDiagnosticService fake) {
            this.link = link;
            this.fake = fake;
        }

        @Override
        public ModuleLink link() {
            return link;
        }

        @Override
        public DiagnosticService diagnostics() {
            return this;
        }

        @Override
        public boolean hasChannelKeepAlive() {
            return link instanceof ModuleLink.Tp20;
        }

        @Override
        public boolean keepChannelAlive() {
            record(link, "keep-alive");
            if (keepAliveFails.contains(link)) {
                close();
                return false;
            }
            return isOpen;
        }

        @Override
        public boolean isOpen() {
            return isOpen;
        }

        @Override
        public void close() {
            if (isOpen) {
                isOpen = false;
                open.decrementAndGet();
                record(link, "close");
            }
        }

        private <T> DiagResult<T> call(String what, Supplier<DiagResult<T>> body) {
            int n = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(n, Math::max);
            try {
                record(link, what);
                if (!isOpen) {
                    throw new IllegalStateException("call on a closed fake connection: " + what);
                }
                if (!callDuration.isZero()) {
                    time.advance(callDuration);
                }
                Thread.yield(); // give an (impossible) overlapping call a chance to show up
                return body.get();
            } finally {
                inFlight.decrementAndGet();
            }
        }

        @Override
        public ApplicationProtocol protocol() {
            return fake.protocol();
        }

        @Override
        public DiagResult<RawBytes> startSession(DiagnosticSession session) {
            return call("START_SESSION", () -> fake.startSession(session));
        }

        @Override
        public DiagResult<RawBytes> testerPresent() {
            return call("TESTER_PRESENT", fake::testerPresent);
        }

        @Override
        public DiagResult<EcuIdentification> readIdentification() {
            return call("READ_IDENTIFICATION", fake::readIdentification);
        }

        @Override
        public DiagResult<DtcList> readDtcs() {
            return call("READ_DTCS", fake::readDtcs);
        }

        @Override
        public DiagResult<FreezeFrame> readFreezeFrame(Dtc dtc) {
            return call("READ_FREEZE_FRAME", () -> fake.readFreezeFrame(dtc));
        }
    }
}
