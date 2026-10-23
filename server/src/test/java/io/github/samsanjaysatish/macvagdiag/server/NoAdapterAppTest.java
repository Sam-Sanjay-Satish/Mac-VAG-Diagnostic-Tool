package io.github.samsanjaysatish.macvagdiag.server;

import static io.github.samsanjaysatish.macvagdiag.server.LocalRequests.get;
import static io.github.samsanjaysatish.macvagdiag.server.LocalRequests.postJson;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.samsanjaysatish.macvagdiag.can.FakeTimeSource;
import io.github.samsanjaysatish.macvagdiag.can.TimeSource;
import io.github.samsanjaysatish.macvagdiag.scan.ScanService;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The real (non-demo) wiring with no CANable plugged in: the app starts, {@code /api/status} says not connected,
 * and every car endpoint is 503. Port discovery is faked (no serial ports are listed), so no OS port is touched.
 */
@SpringBootTest(properties = "macvagdiag.demo=false")
@AutoConfigureMockMvc
@Import(NoAdapterAppTest.NoPorts.class)
class NoAdapterAppTest {

    static final Path AUDIT = TestAuditLogs.newFile("no-adapter");

    @DynamicPropertySource
    static void auditLog(DynamicPropertyRegistry registry) {
        registry.add("macvagdiag.audit-log", AUDIT::toString);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class NoPorts {
        @Bean
        PortDiscovery portDiscovery() {
            return List::of;
        }

        @Bean
        TimeSource timeSource() {
            return new FakeTimeSource();
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ScanService scan;

    @Test
    @DisplayName("no adapter: the app starts, /api/status is 200 DISCONNECTED with why, car endpoints 503")
    void notConnected() throws Exception {
        AdapterWaitingScanService waiting = assertInstanceOf(AdapterWaitingScanService.class, scan);
        mvc.perform(get("/api/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.adapter").value("DISCONNECTED"))
                .andExpect(jsonPath("$.adapterConnected").value(false))
                .andExpect(jsonPath("$.adapterDetail").value(containsString("no CANable found")))
                .andExpect(jsonPath("$.demo").value(false));
        mvc.perform(get("/api/modules")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("ADAPTER_NOT_CONNECTED"))
                .andExpect(jsonPath("$.message").value(containsString("plug in the CANable")));
        mvc.perform(get("/api/modules/01/faults")).andExpect(status().isServiceUnavailable());
        mvc.perform(postJson("/api/scans")).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/scans/scan-1")).andExpect(status().isNotFound());
        assertFalse(waiting.isBound());
    }
}
