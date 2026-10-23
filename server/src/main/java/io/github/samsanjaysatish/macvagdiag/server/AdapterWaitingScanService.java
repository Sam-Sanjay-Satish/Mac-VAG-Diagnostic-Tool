package io.github.samsanjaysatish.macvagdiag.server;

import io.github.samsanjaysatish.macvagdiag.can.SlcanPortDiscovery;
import io.github.samsanjaysatish.macvagdiag.can.TimeSource;
import io.github.samsanjaysatish.macvagdiag.scan.AutoScanJob;
import io.github.samsanjaysatish.macvagdiag.scan.Caller;
import io.github.samsanjaysatish.macvagdiag.scan.ModuleAddress;
import io.github.samsanjaysatish.macvagdiag.scan.ModuleAvailability;
import io.github.samsanjaysatish.macvagdiag.scan.ModuleResult;
import io.github.samsanjaysatish.macvagdiag.scan.ScanProgressListener;
import io.github.samsanjaysatish.macvagdiag.scan.ScanRefusedException;
import io.github.samsanjaysatish.macvagdiag.scan.ScanService;
import io.github.samsanjaysatish.macvagdiag.scan.SessionStatus;
import io.github.samsanjaysatish.macvagdiag.scan.ToolCallRecord;
import io.github.samsanjaysatish.macvagdiag.scan.VehicleInfo;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link ScanService} when no port is configured: it looks for a CANable (by USB ID) at startup and again on
 * later calls (at most every {@link #REDISCOVERY_INTERVAL}) until one is plugged in. Then it builds the real service
 * on that port, once, and passes every call straight through to it from then on.
 *
 * <p>Until then nothing can reach the car: {@link #sessionStatus} reports {@code DISCONNECTED} with what was found,
 * and every call that would talk to the car is refused with {@link ScanRefusedException#adapterNotConnected}.
 * MCP tool calls are still audit-logged: they go to an <i>audit-only</i> service whose adapter is never opened (no
 * car call is ever forwarded to it); it is closed before the real one is built, so one service writes the audit
 * log at a time.
 *
 * <p>Only ports that identify as a CANable are picked automatically. Any other {@code /dev/cu.usbmodem*} device
 * (an Arduino, a phone) is never written to unless the user names it in {@code macvagdiag.port}.
 */
final class AdapterWaitingScanService implements ScanService {

    private static final Logger log = LoggerFactory.getLogger(AdapterWaitingScanService.class);

    static final Duration REDISCOVERY_INTERVAL = Duration.ofSeconds(2);
    /** The port name of the audit-only service. It is never opened. */
    static final String AUDIT_ONLY_PORT = "(no adapter: audit only)";
    static final String NOT_KNOWN = "not known: no CAN adapter connected";

    private final PortDiscovery discovery;
    private final Function<String, ScanService> connect;
    private final TimeSource time;
    private final Object lock = new Object();

    private volatile ScanService bound;
    private volatile String detail = "no CANable found yet";
    private ScanService auditOnly; // guarded by lock
    private boolean searched; // guarded by lock
    private long nextSearchNanos; // guarded by lock
    private boolean closed; // guarded by lock

    /**
     * @param discovery lists candidate ports
     * @param connect builds the real service on a serial port path: the one place that creates an
     *     {@code SlcanAdapter} ({@link ScanServiceConfiguration})
     * @param time paces the re-discovery
     */
    AdapterWaitingScanService(PortDiscovery discovery, Function<String, ScanService> connect, TimeSource time) {
        this.discovery = Objects.requireNonNull(discovery, "discovery");
        this.connect = Objects.requireNonNull(connect, "connect");
        this.time = Objects.requireNonNull(time, "time");
        current(); // bind at startup if a CANable is already plugged in
    }

    /** The real service, once a CANable has been found; else null. Looks again if the last look is old enough. */
    private ScanService current() {
        ScanService b = bound;
        if (b != null) {
            return b;
        }
        synchronized (lock) {
            if (bound != null) {
                return bound;
            }
            if (closed) {
                throw ScanRefusedException.serviceClosed();
            }
            long now = time.nanoTime();
            if (searched && now - nextSearchNanos < 0) {
                return null;
            }
            searched = true;
            nextSearchNanos = now + REDISCOVERY_INTERVAL.toNanos();
            List<SlcanPortDiscovery.Candidate> found;
            try {
                found = discovery.candidates();
            } catch (RuntimeException e) {
                log.warn("Could not list serial ports", e);
                detail = "could not list serial ports: " + e.getMessage();
                return null;
            }
            Optional<SlcanPortDiscovery.Candidate> canable =
                    found.stream().filter(SlcanPortDiscovery.Candidate::isCanable).findFirst();
            if (canable.isEmpty()) {
                detail = describeNotFound(found);
                return null;
            }
            String path = canable.get().path();
            log.info("CANable found on {} ({})", path, canable.get().description());
            if (auditOnly != null) {
                auditOnly.close();
                auditOnly = null;
            }
            bound = connect.apply(path);
            return bound;
        }
    }

    static String describeNotFound(List<SlcanPortDiscovery.Candidate> found) {
        String looked = String.format("no CANable found (looked for %s* ports with USB ID %04X:%04X)",
                SlcanPortDiscovery.CANDIDATE_PREFIX, SlcanPortDiscovery.CANABLE_VENDOR_ID,
                SlcanPortDiscovery.CANABLE_PRODUCT_ID);
        if (found.isEmpty()) {
            return looked + "; plug it in, it is picked up automatically, or set macvagdiag.port";
        }
        return looked + "; other serial devices found: "
                + found.stream().map(SlcanPortDiscovery.Candidate::path).collect(Collectors.joining(", "))
                + ". To use one of them anyway, set macvagdiag.port to its path";
    }

    private ScanService require() {
        ScanService s = current();
        if (s == null) {
            throw ScanRefusedException.adapterNotConnected(detail);
        }
        return s;
    }

    /** True once a CANable has been found and the real service built. */
    boolean isBound() {
        return bound != null;
    }

    @Override
    public boolean isDemo() {
        return false;
    }

    @Override
    public SessionStatus sessionStatus(Caller caller) throws InterruptedException {
        Objects.requireNonNull(caller, "caller");
        ScanService s = current();
        if (s != null) {
            return s.sessionStatus(caller);
        }
        return new SessionStatus(SessionStatus.AdapterState.DISCONNECTED, detail,
                SessionStatus.VehicleCondition.unknown(NOT_KNOWN), SessionStatus.VehicleCondition.unknown(NOT_KNOWN),
                false, time.now());
    }

    @Override
    public List<ModuleAvailability> listModules(Caller caller) throws InterruptedException {
        return require().listModules(caller);
    }

    @Override
    public ModuleResult readFaultCodes(Caller caller, ModuleAddress module) throws InterruptedException {
        return require().readFaultCodes(caller, module);
    }

    @Override
    public VehicleInfo vehicleInfo(Caller caller) throws InterruptedException {
        return require().vehicleInfo(caller);
    }

    @Override
    public AutoScanJob startAutoScan(Caller caller, ScanProgressListener listener) {
        return require().startAutoScan(caller, listener);
    }

    @Override
    public Optional<AutoScanJob> findAutoScan(String scanId) {
        ScanService s = bound;
        return s == null ? Optional.empty() : s.findAutoScan(scanId);
    }

    @Override
    public void recordToolCall(ToolCallRecord call) {
        ScanService s = bound;
        if (s != null) {
            s.recordToolCall(call);
            return;
        }
        synchronized (lock) {
            if (bound != null) {
                bound.recordToolCall(call);
                return;
            }
            if (closed) {
                throw ScanRefusedException.serviceClosed();
            }
            if (auditOnly == null) {
                auditOnly = connect.apply(AUDIT_ONLY_PORT);
            }
            auditOnly.recordToolCall(call);
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            if (auditOnly != null) {
                auditOnly.close();
                auditOnly = null;
            }
            if (bound != null) {
                bound.close();
            }
        }
    }
}
