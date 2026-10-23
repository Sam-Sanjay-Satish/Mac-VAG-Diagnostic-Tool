package io.github.samsanjaysatish.macvagdiag.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.scan.AutoScanJob;
import io.github.samsanjaysatish.macvagdiag.scan.AutoScanResult;
import io.github.samsanjaysatish.macvagdiag.scan.Caller;
import io.github.samsanjaysatish.macvagdiag.scan.ScanService;
import io.github.samsanjaysatish.macvagdiag.scan.SessionStatus;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * <b>Manual only</b> ({@code ./gradlew :server:hardwareTest}, CANable plugged into the car, ignition on). Checks
 * that the real wiring finds the CANable and an Auto-Scan gets answers. Never runs in {@code build} or CI.
 * Afterwards, open the web page ({@code ./gradlew :server:bootRun}) and run a scan from the browser.
 */
@Tag("hardware")
@SpringBootTest(properties = "macvagdiag.demo=false")
class HardwareSmokeTest {

    static final Path AUDIT = TestAuditLogs.newFile("hardware");

    @DynamicPropertySource
    static void auditLog(DynamicPropertyRegistry registry) {
        registry.add("macvagdiag.audit-log", AUDIT::toString);
    }

    @Autowired
    ScanService scan;

    @Test
    @Timeout(120)
    @DisplayName("real CANable: adapter connected, Auto-Scan finishes with at least one module responding")
    void realCar() throws Exception {
        SessionStatus status = scan.sessionStatus(Caller.web());
        assertEquals(SessionStatus.AdapterState.CONNECTED, status.adapter(), status.adapterDetail());
        AutoScanJob job = scan.startAutoScan(Caller.web(), null);
        AutoScanResult result = job.await(Duration.ofSeconds(110));
        assertTrue(!result.respondingModules().isEmpty(), "no module answered");
    }
}
