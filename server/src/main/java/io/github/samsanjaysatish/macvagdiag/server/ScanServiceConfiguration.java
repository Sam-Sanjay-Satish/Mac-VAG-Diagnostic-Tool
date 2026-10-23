package io.github.samsanjaysatish.macvagdiag.server;

import io.github.samsanjaysatish.macvagdiag.can.SlcanAdapter;
import io.github.samsanjaysatish.macvagdiag.can.TimeSource;
import io.github.samsanjaysatish.macvagdiag.scan.ScanOptions;
import io.github.samsanjaysatish.macvagdiag.scan.ScanService;
import io.github.samsanjaysatish.macvagdiag.scan.ScanServiceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * <b>WIRING.</b> Builds the one {@link ScanService} of this process, which the web API (and later MCP) share. This
 * is the only place in the app that creates a real {@link SlcanAdapter} (root rule: real hardware is created in
 * exactly one place); the service then owns it and closes it when Spring closes the bean on shutdown.
 *
 * <ul>
 *   <li>{@code --demo} / {@code macvagdiag.demo=true}: {@link ScanServiceFactory#demo}, the real scan / diag /
 *       transport stack on the simulated demo car. A {@code ReplayAdapter} demo on a bundled capture waits for a
 *       VIN-masked capture from the sniffing session.</li>
 *   <li>{@code macvagdiag.port} set: the CANable on that port. If it isn't there, the app still starts and the
 *       status says why; scan reopens it on the next call.</li>
 *   <li>Otherwise auto-detect: a CANable by USB ID, looked for again until one is plugged in
 *       ({@link AdapterWaitingScanService}).</li>
 * </ul>
 *
 * A {@link TimeSource} or {@link PortDiscovery} bean replaces the system one (tests only).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MacVagDiagProperties.class)
class ScanServiceConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ScanServiceConfiguration.class);

    @Bean(destroyMethod = "close")
    ScanService scanService(MacVagDiagProperties properties, ApplicationArguments arguments,
            ObjectProvider<TimeSource> timeOverride, ObjectProvider<PortDiscovery> discoveryOverride) {
        TimeSource time = timeOverride.getIfAvailable(TimeSource::system);
        ScanOptions options = properties.scanOptions();
        if (properties.demo() || arguments.containsOption("demo")) {
            log.info("DEMO mode: simulated car, not a real vehicle. Audit log: {}", options.auditLog());
            return ScanServiceFactory.demo(time, options);
        }
        String port = properties.configuredPort();
        if (port != null) {
            log.info("CAN adapter: slcan on {} (from macvagdiag.port) at {} bit/s. Audit log: {}", port,
                    options.bitsPerSecond(), options.auditLog());
            return onSerialPort(port, time, options);
        }
        log.info("CAN adapter: auto-detect (CANable by USB ID). Audit log: {}", options.auditLog());
        return new AdapterWaitingScanService(discoveryOverride.getIfAvailable(() -> PortDiscovery.SYSTEM),
                path -> onSerialPort(path, time, options), time);
    }

    /** The only {@code SlcanAdapter} construction in the app. The port isn't opened until the service needs it. */
    private static ScanService onSerialPort(String path, TimeSource time, ScanOptions options) {
        return ScanServiceFactory.create(SlcanAdapter.forPort(path, time), time, options);
    }
}
