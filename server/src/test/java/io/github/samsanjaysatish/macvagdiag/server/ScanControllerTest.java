package io.github.samsanjaysatish.macvagdiag.server;

import static io.github.samsanjaysatish.macvagdiag.server.LocalRequests.get;
import static io.github.samsanjaysatish.macvagdiag.server.LocalRequests.postJson;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.samsanjaysatish.macvagdiag.scan.AutoScanJob;
import io.github.samsanjaysatish.macvagdiag.scan.Caller;
import io.github.samsanjaysatish.macvagdiag.scan.FakeScanService;
import io.github.samsanjaysatish.macvagdiag.scan.Limit;
import io.github.samsanjaysatish.macvagdiag.scan.LimitHit;
import io.github.samsanjaysatish.macvagdiag.scan.ModuleAddress;
import io.github.samsanjaysatish.macvagdiag.scan.SampleScanData;
import io.github.samsanjaysatish.macvagdiag.scan.ScanRefusedException;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** The REST API against {@link FakeScanService}: JSON shapes and every error mapping. */
@WebMvcTest(ScanController.class)
@Import(SwappableScanService.Config.class)
class ScanControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    SwappableScanService service;

    FakeScanService fake;

    @BeforeEach
    void freshFake() {
        fake = service.reset();
    }

    // ---- JSON shapes --------------------------------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/status: adapter, ignition, engine, demo flag; called as web")
    void statusJson() throws Exception {
        mvc.perform(get("/api/status")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.adapter").value("CONNECTED"))
                .andExpect(jsonPath("$.adapterConnected").value(true))
                .andExpect(jsonPath("$.adapterDetail").value("sample adapter"))
                .andExpect(jsonPath("$.ignition.state").value("UNKNOWN"))
                .andExpect(jsonPath("$.ignition.reason").value(containsString("allowlist")))
                .andExpect(jsonPath("$.engine.state").value("UNKNOWN"))
                .andExpect(jsonPath("$.demo").value(false))
                .andExpect(jsonPath("$.checkedAt").value("2026-01-01T10:00:00Z"));
        assertEquals("web", fake.calls().getFirst().caller());
    }

    @Test
    @DisplayName("GET /api/modules: every known module with reachability and link")
    void modules() throws Exception {
        mvc.perform(get("/api/modules")).andExpect(status().isOk())
                .andExpect(jsonPath("$.modules", hasSize(ModuleAddress.known().size())))
                .andExpect(jsonPath("$.modules[0].address").value("01"))
                .andExpect(jsonPath("$.modules[0].name").value("Engine"))
                .andExpect(jsonPath("$.modules[0].reachability").value("RESPONDING"))
                .andExpect(jsonPath("$.modules[0].link.type").value("ISOTP"))
                .andExpect(jsonPath("$.modules[0].link.protocol").value("UDS"))
                .andExpect(jsonPath("$.modules[0].link.description").value("UDS over ISO-TP (7E0/7E8)"))
                .andExpect(jsonPath("$.modules[3].address").value("08"))
                .andExpect(jsonPath("$.modules[3].reachability").value("NOT_RESPONDING"))
                .andExpect(jsonPath("$.modules[3].link").value(nullValue()))
                .andExpect(jsonPath("$.modules[3].checkedAt").value("2026-01-01T10:00:00Z"));
    }

    @Test
    @DisplayName("GET /api/modules/01/faults: UDS code with status flags, freeze frame records and identification")
    void engineFaults() throws Exception {
        mvc.perform(get("/api/modules/01/faults")).andExpect(status().isOk())
                .andExpect(jsonPath("$.address").value("01"))
                .andExpect(jsonPath("$.status").value("FAULTS"))
                .andExpect(jsonPath("$.statusText").value("1 fault code"))
                .andExpect(jsonPath("$.responded").value(true))
                .andExpect(jsonPath("$.faultListComplete").value(true))
                .andExpect(jsonPath("$.requests").value(6))
                .andExpect(jsonPath("$.limitHit").value(nullValue()))
                .andExpect(jsonPath("$.faults", hasSize(1)))
                .andExpect(jsonPath("$.faults[0].code").value("P0401"))
                .andExpect(jsonPath("$.faults[0].displayCode").value("P0401-00"))
                .andExpect(jsonPath("$.faults[0].protocol").value("UDS"))
                .andExpect(jsonPath("$.faults[0].record").value("04 01 00 2F"))
                .andExpect(jsonPath("$.faults[0].status").value(0x2F))
                .andExpect(jsonPath("$.faults[0].statusHex").value("2F"))
                .andExpect(jsonPath("$.faults[0].statusFlags", hasSize(5)))
                .andExpect(jsonPath("$.faults[0].statusFlags[3]").value("confirmedDTC"))
                .andExpect(jsonPath("$.faults[0].failureType").value("00"))
                .andExpect(jsonPath("$.faults[0].obdCode").value(nullValue()))
                .andExpect(jsonPath("$.faults[0].freezeFrame.availability").value("READ"))
                .andExpect(jsonPath("$.faults[0].freezeFrame.records[0].id").value("19 04 04 01 00 FF"))
                .andExpect(jsonPath("$.faults[0].freezeFrame.records[0].hex").value("01 01 F4 0D 2A"))
                .andExpect(jsonPath("$.faults[0].freezeFrame.records[0].text").value(nullValue()))
                .andExpect(jsonPath("$.faults[0].freezeFrame.responses[0]").value("59 04 04 01 00 2F 01 01 F4 0D 2A"))
                .andExpect(jsonPath("$.identification.partNumber").value("03L906018XX"))
                .andExpect(jsonPath("$.identification.raw[0].id").value("22 F1 90"))
                .andExpect(jsonPath("$.identification.raw[0].text").value(SampleScanData.VIN));
        assertEquals(new FakeScanService.Call("readFaultCodes", "web", "01"), fake.calls().getFirst());
    }

    @Test
    @DisplayName("GET /api/modules/03/faults: KWP code, freeze frame not available, with the reason")
    void kwpFault() throws Exception {
        mvc.perform(get("/api/modules/03/faults")).andExpect(status().isOk())
                .andExpect(jsonPath("$.link.type").value("TP20"))
                .andExpect(jsonPath("$.link.protocol").value("KWP2000"))
                .andExpect(jsonPath("$.faults[0].code").value("01314"))
                .andExpect(jsonPath("$.faults[0].failureType").value(nullValue()))
                .andExpect(jsonPath("$.faults[0].freezeFrame.availability").value("NOT_AVAILABLE"))
                .andExpect(jsonPath("$.faults[0].freezeFrame.message").value(containsString("allowlist")));
    }

    @Test
    @DisplayName("module not responding is a result: 200 with status NOT_RESPONDING, not an error")
    void moduleNotRespondingIsData() throws Exception {
        mvc.perform(get("/api/modules/08/faults")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_RESPONDING"))
                .andExpect(jsonPath("$.responded").value(false))
                .andExpect(jsonPath("$.link").value(nullValue()))
                .andExpect(jsonPath("$.faults", hasSize(0)));
    }

    @Test
    @DisplayName("lower-case module address is accepted, like scan's parser")
    void lowerCaseAddress() throws Exception {
        mvc.perform(get("/api/modules/1a/faults")).andExpect(status().isBadRequest()); // 1A is not a known module
        mvc.perform(get("/api/modules/09/faults")).andExpect(status().isOk()).andExpect(jsonPath("$.address").value("09"));
    }

    @Test
    @DisplayName("POST /api/scans: 202 with the ID and Location; GET /api/scans/{id}: progress and results")
    void startAndReadScan() throws Exception {
        mvc.perform(postJson("/api/scans")).andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/scans/scan-sample"))
                .andExpect(jsonPath("$.id").value("scan-sample"))
                .andExpect(jsonPath("$.href").value("/api/scans/scan-sample"))
                .andExpect(jsonPath("$.reportHref").value("/api/scans/scan-sample/report.txt"));
        assertEquals(new FakeScanService.Call("startAutoScan", "web", ""), fake.calls().getFirst());

        int n = ModuleAddress.known().size();
        mvc.perform(get("/api/scans/scan-sample")).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("scan-sample"))
                .andExpect(jsonPath("$.state").value("FINISHED"))
                .andExpect(jsonPath("$.demo").value(false))
                .andExpect(jsonPath("$.caller").value("web"))
                .andExpect(jsonPath("$.startedAt").value("2026-01-01T10:00:00Z"))
                .andExpect(jsonPath("$.totalModules").value(n))
                .andExpect(jsonPath("$.completedModules").value(n))
                .andExpect(jsonPath("$.currentModule").value(nullValue()))
                .andExpect(jsonPath("$.lastMessage").value(containsString("Auto-Scan finished")))
                .andExpect(jsonPath("$.events", hasSize(2 + 2 * n)))
                .andExpect(jsonPath("$.events[0].type").value("Started"))
                .andExpect(jsonPath("$.events[1].message").value("scanning 01 / Engine... (1 of " + n + ")"))
                .andExpect(jsonPath("$.modules", hasSize(n)))
                .andExpect(jsonPath("$.modules[0].faults[0].code").value("P0401"))
                .andExpect(jsonPath("$.result.vin").value(SampleScanData.VIN))
                .andExpect(jsonPath("$.result.vinSource").value("01"))
                .andExpect(jsonPath("$.result.durationMs").value(6000))
                .andExpect(jsonPath("$.result.modulesChecked").value(n))
                .andExpect(jsonPath("$.result.modulesResponding").value(7))
                .andExpect(jsonPath("$.result.modulesWithFaults").value(2))
                .andExpect(jsonPath("$.result.faultCodes").value(2))
                .andExpect(jsonPath("$.result.cancelled").value(false))
                .andExpect(jsonPath("$.failure").value(nullValue()));
    }

    @Test
    @DisplayName("a failed scan: state FAILED with the refusal reason; its report is 409")
    void failedScan() throws Exception {
        service.addJob(AutoScanJob.failed("scan-9", Caller.web(), SampleScanData.AT,
                ScanRefusedException.adapterNotConnected("unplugged")));
        mvc.perform(get("/api/scans/scan-9")).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("FAILED"))
                .andExpect(jsonPath("$.result").value(nullValue()))
                .andExpect(jsonPath("$.failure.error").value("ADAPTER_NOT_CONNECTED"))
                .andExpect(jsonPath("$.failure.message").value(containsString("plug in the CANable")));
        mvc.perform(get("/api/scans/scan-9/report.txt")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SCAN_FAILED"));
    }

    @Test
    @DisplayName("unknown scan ID: 404, for progress and for the report")
    void unknownScan() throws Exception {
        mvc.perform(get("/api/scans/scan-404")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("SCAN_NOT_FOUND"));
        mvc.perform(get("/api/scans/scan-404/report.txt")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/scans with parameters is refused: 400, nothing started")
    void startScanTakesNoParameters() throws Exception {
        mvc.perform(MockMvcRequestBuilders.post("/api/scans").header("Host", LocalRequests.HOST)
                .contentType(MediaType.APPLICATION_JSON).content("{\"modules\":[\"01\"]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
        mvc.perform(MockMvcRequestBuilders.post("/api/scans").header("Host", LocalRequests.HOST)
                .contentType(MediaType.APPLICATION_JSON).content("")).andExpect(status().isAccepted());
        assertEquals(1, fake.calls().stream().filter(c -> c.method().equals("startAutoScan")).count());
    }

    // ---- errors -------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("adapter not connected → 503 with scan's readable message, on every car endpoint")
    void adapterNotConnected() throws Exception {
        fake.failWith(ScanRefusedException.adapterNotConnected("no CANable found"));
        for (String path : new String[] {"/api/modules", "/api/modules/17/faults", "/api/status"}) {
            mvc.perform(get(path)).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.error").value("ADAPTER_NOT_CONNECTED"))
                    .andExpect(jsonPath("$.message").value(
                            "adapter not connected: ask the user to plug in the CANable and switch the ignition on "
                                    + "(no CANable found)"))
                    .andExpect(header().doesNotExist("Retry-After"));
        }
        mvc.perform(postJson("/api/scans")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("ADAPTER_NOT_CONNECTED"));
    }

    @Test
    @DisplayName("service closed → 503")
    void serviceClosed() throws Exception {
        fake.failWith(ScanRefusedException.serviceClosed());
        mvc.perform(get("/api/modules")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("SERVICE_CLOSED"));
    }

    @Test
    @DisplayName("rate limit → 429 with Retry-After rounded up to whole seconds, and the limit named")
    void rateLimit() throws Exception {
        fake.failWith(ScanRefusedException.limitExceeded(new LimitHit(Limit.REQUESTS_PER_MINUTE,
                Duration.ofMillis(11_200), "rate limit hit: at most 400 requests per minute to the car; retry after 12 s")));
        mvc.perform(get("/api/modules/17/faults")).andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "12"))
                .andExpect(jsonPath("$.error").value("LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.limit").value("REQUESTS_PER_MINUTE"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(12))
                .andExpect(jsonPath("$.message").value(containsString("retry after 12 s")));
    }

    @Test
    @DisplayName("module in scan's not-responding back-off (nothing sent) → 429 with Retry-After and the module")
    void moduleBackOff() throws Exception {
        fake.failWith(ScanRefusedException.moduleNotResponding(ModuleAddress.parse("16"), Duration.ofSeconds(5),
                "no answer to the channel setup"));
        mvc.perform(get("/api/modules/16/faults")).andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.error").value("MODULE_NOT_RESPONDING"))
                .andExpect(jsonPath("$.limit").value("MODULE_NOT_RESPONDING_BACKOFF"))
                .andExpect(jsonPath("$.module").value("16"))
                .andExpect(jsonPath("$.message").value(containsString("module 16 (Steering Wheel) not responding")));
    }

    @Test
    @DisplayName("Auto-Scan already running → 409")
    void scanInProgress() throws Exception {
        fake.failWith(ScanRefusedException.scanInProgress("scan-3"));
        mvc.perform(postJson("/api/scans")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SCAN_IN_PROGRESS"))
                .andExpect(jsonPath("$.message").value(containsString("scan-3")));
    }

    @ParameterizedTest(name = "module \"{0}\" → 400")
    @ValueSource(strings = {"0x08", "8", "008", "FF", "1A", "zz", "-1", "+8", "%2008", "17%00"})
    @DisplayName("malformed or unknown module address → 400 before the service is called")
    void invalidModule(String address) throws Exception {
        mvc.perform(get("/api/modules/" + address + "/faults")).andExpect(status().isBadRequest());
        assertTrue(fake.calls().stream().noneMatch(c -> c.method().equals("readFaultCodes")), fake.calls()::toString);
    }

    @Test
    @DisplayName("400 body names the known modules")
    void invalidModuleMessage() throws Exception {
        mvc.perform(get("/api/modules/FF/faults")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_MODULE"))
                .andExpect(jsonPath("$.message").value(containsString("known modules: 01, 02, 03")));
    }

    @Test
    @DisplayName("maskVin must be a boolean → 400")
    void badMaskVin() throws Exception {
        mvc.perform(postJson("/api/scans"));
        mvc.perform(get("/api/scans/scan-sample/report.txt?maskVin=maybe")).andExpect(status().isBadRequest());
    }
}
