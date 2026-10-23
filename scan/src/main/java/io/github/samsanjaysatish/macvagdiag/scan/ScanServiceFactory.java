package io.github.samsanjaysatish.macvagdiag.scan;

import io.github.samsanjaysatish.macvagdiag.can.Bitrate;
import io.github.samsanjaysatish.macvagdiag.can.CanAdapter;
import io.github.samsanjaysatish.macvagdiag.can.SimulatedAdapter;
import io.github.samsanjaysatish.macvagdiag.can.TimeSource;
import java.util.Objects;

/**
 * <b>WIRING.</b> Creates the {@link ScanService} at app startup. Together with {@link ChannelConnector} and
 * {@link DemoCar} this is the only scan code that touches can-core adapters or transport's {@code ChannelFactory}
 * ({@code NoBypassSourceTest} enforces it).
 *
 * <p>Real hardware is created in exactly one place, the app's startup code in {@code server} / {@code mcp}, which
 * builds the {@code SlcanAdapter} and passes it here. From then on the service owns the adapter: it opens it
 * (NORMAL mode, {@link ScanOptions#bitsPerSecond()}) when first needed, reopens it after a failure, and closes it in
 * {@link ScanService#close()}. Only one process can own the serial port, so create one service per process and
 * share it between the web UI and MCP.
 */
public final class ScanServiceFactory {

    private ScanServiceFactory() {
    }

    /** The real stack on {@code adapter}, on the system clock. */
    public static ScanService create(CanAdapter adapter, ScanOptions options) {
        return create(adapter, TimeSource.system(), options);
    }

    /**
     * The real stack on {@code adapter}.
     *
     * @param time the clock for every timeout, interval and rate limit; use the one the adapter uses
     * @throws java.io.UncheckedIOException if the audit log can't be opened (nothing may be sent without it)
     */
    public static ScanService create(CanAdapter adapter, TimeSource time, ScanOptions options) {
        Objects.requireNonNull(adapter, "adapter");
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(options, "options");
        Bitrate bitrate = Bitrate.ofBitsPerSecond(options.bitsPerSecond());
        AuditLog audit = AuditLog.append(options.auditLog(), time, false);
        return new DefaultScanService(new ChannelConnector(adapter, time, bitrate), time, options.limits(), audit, false);
    }

    /**
     * <b>Demo mode:</b> the real scan / diag / transport stack on a {@link SimulatedAdapter} answering as a made-up
     * car ({@link DemoCar}). Every result says {@code demo = true}, the report carries a DEMO banner and audit lines
     * carry {@code "demo":true}. On the system clock, so a demo Auto-Scan takes a few seconds like a real one.
     */
    public static ScanService demo(ScanOptions options) {
        return demo(TimeSource.system(), options);
    }

    /** {@link #demo(ScanOptions)} on {@code time} (tests: {@code FakeTimeSource.autoAdvancing()}). */
    public static ScanService demo(TimeSource time, ScanOptions options) {
        Objects.requireNonNull(options, "options");
        return demoStack(time, options.limits(), AuditLog.append(options.auditLog(), time, true)).service();
    }

    /** The demo stack with its parts visible, for tests. */
    record DemoStack(DefaultScanService service, SimulatedAdapter adapter, ChannelConnector connector) {
    }

    static DemoStack demoStack(TimeSource time, ScanLimits limits, AuditLog audit) {
        Objects.requireNonNull(time, "time");
        DemoCar car = new DemoCar();
        SimulatedAdapter adapter = new SimulatedAdapter(time, car);
        car.attach(adapter);
        ChannelConnector connector = new ChannelConnector(adapter, time,
                Bitrate.ofBitsPerSecond(ScanOptions.DEFAULT_BITS_PER_SECOND));
        return new DemoStack(new DefaultScanService(connector, time, limits, audit, true), adapter, connector);
    }

    /** The real stack on any adapter with an injected audit log, for full-stack tests. */
    static DemoStack stack(SimulatedAdapter adapter, TimeSource time, ScanLimits limits, AuditLog audit) {
        ChannelConnector connector = new ChannelConnector(adapter, time,
                Bitrate.ofBitsPerSecond(ScanOptions.DEFAULT_BITS_PER_SECOND));
        return new DemoStack(new DefaultScanService(connector, time, limits, audit, false), adapter, connector);
    }
}
