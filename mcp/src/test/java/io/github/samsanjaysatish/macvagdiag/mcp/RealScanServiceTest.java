package io.github.samsanjaysatish.macvagdiag.mcp;

import static io.github.samsanjaysatish.macvagdiag.mcp.ToolTesting.error;
import static io.github.samsanjaysatish.macvagdiag.mcp.ToolTesting.list;
import static io.github.samsanjaysatish.macvagdiag.mcp.ToolTesting.map;
import static io.github.samsanjaysatish.macvagdiag.mcp.ToolTesting.ok;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.can.FakeTimeSource;
import io.github.samsanjaysatish.macvagdiag.scan.ScanOptions;
import io.github.samsanjaysatish.macvagdiag.scan.ScanService;
import io.github.samsanjaysatish.macvagdiag.scan.ScanServiceFactory;
import io.github.samsanjaysatish.macvagdiag.scan.TestScanServices;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The tools over a <b>real</b> {@link ScanService}: the whole demo stack (scan, diag, transport, simulated adapter,
 * made-up car) on an auto-advancing fake clock, and {@link TestScanServices} for scan's own audit log.
 */
class RealScanServiceTest {

    private static final String DEMO_VIN = "DEMOVIN0000000000";

    @TempDir
    Path dir;

    private ScanService demo() {
        return ScanServiceFactory.demo(FakeTimeSource.autoAdvancing(),
                ScanOptions.defaults().withAuditLog(dir.resolve("audit.jsonl")));
    }

    private List<String> auditLines() throws Exception {
        return Files.readAllLines(dir.resolve("audit.jsonl"), StandardCharsets.UTF_8);
    }

    @Test
    void demoCarThroughEveryTool() throws Exception {
        try (ScanService scan = demo()) {
            McpTools tools = new McpTools(scan, McpOptions.defaults());

            Map<String, Object> status = ok(tools, "get_session_status", tools.call("get_session_status", Map.of()));
            assertEquals("CONNECTED", map(status.get("adapter")).get("value"));
            assertEquals(true, status.get("demo"));

            Map<String, Object> modules = ok(tools, "list_modules", tools.call("list_modules", Map.of()));
            Map<String, String> reach = list(modules.get("modules")).stream().map(ToolTesting::map)
                    .collect(Collectors.toMap(m -> (String) m.get("address"), m -> (String) m.get("reachability")));
            assertEquals("RESPONDING", reach.get("01"));
            assertEquals("RESPONDING", reach.get("17"));
            assertEquals("NOT_RESPONDING", reach.get("16"));
            assertEquals(true, modules.get("demo"));

            Map<String, Object> engine = ok(tools, "read_fault_codes", tools.call("read_fault_codes", Map.of("module", "01")));
            assertEquals("FAULTS", engine.get("status"));
            assertEquals("UDS", engine.get("protocol"));
            assertEquals(List.of("P0401", "P2463"), list(engine.get("faults")).stream().map(ToolTesting::map)
                    .map(f -> f.get("code")).toList());
            Map<String, Object> ff = map(map(list(engine.get("faults")).getFirst()).get("freezeFrame"));
            assertEquals("READ", ff.get("availability"));
            assertFalse(list(ff.get("records")).isEmpty(), "raw freeze-frame records with their request IDs");

            Map<String, Object> abs = ok(tools, "read_fault_codes", tools.call("read_fault_codes", Map.of("module", "03")));
            assertEquals("KWP2000", abs.get("protocol"));
            assertEquals("01314", map(list(abs.get("faults")).getFirst()).get("code"));

            var infoResult = tools.call("get_vehicle_info", Map.of());
            Map<String, Object> info = ok(tools, "get_vehicle_info", infoResult);
            assertEquals("DEMOVIN0000******", map(info.get("vin")).get("value"));
            assertFalse(ToolTesting.text(infoResult).contains(DEMO_VIN));
        }
        List<String> audit = auditLines();
        for (String tool : ToolListTest.EXPECTED) {
            assertTrue(audit.stream().anyMatch(l -> l.contains("\"event\":\"tool-call\"") && l.contains("\"tool\":\"" + tool
                    + "\"") && l.contains("\"outcome\":\"ok\"")), "tool-call audit line for " + tool);
        }
        // scan's own request lines name the MCP tool as the caller.
        assertTrue(audit.stream().anyMatch(l -> l.contains("\"event\":\"request\"")
                && l.contains("\"caller\":\"mcp:read_fault_codes\"")));
        assertFalse(audit.stream().anyMatch(l -> l.contains("\"event\":\"rejected\"")), "nothing hit the guard");
    }

    @Test
    void demoVinUnmaskedWhenTheUserSaysSo() {
        try (ScanService scan = demo()) {
            McpTools tools = new McpTools(scan, new McpOptions(false));
            Map<String, Object> info = ok(tools, "get_vehicle_info", tools.call("get_vehicle_info", Map.of()));
            assertEquals(DEMO_VIN, map(info.get("vin")).get("value"));
            assertEquals("01", map(info.get("vin")).get("source"), "the first module asked that reports one");
        }
    }

    @Test
    void rejectedCallIsAuditedAndSendsNothing() throws Exception {
        try (ScanService scan = demo()) {
            McpTools tools = new McpTools(scan, McpOptions.defaults());
            error(tools.call("read_fault_codes", Map.of("module", "01", "request", "14 FF FF FF")));
            error(tools.call("read_fault_codes", Map.of("module", "5A")));
        }
        List<String> audit = auditLines();
        assertEquals(2, audit.stream().filter(l -> l.contains("\"event\":\"tool-call\"")
                && l.contains("\"outcome\":\"rejected\"")).count());
        assertTrue(audit.stream().anyMatch(l -> l.contains("14 FF FF FF")), "the attempted arguments are on record");
        assertFalse(audit.stream().anyMatch(l -> l.contains("\"event\":\"request\"")), "no request reached the car");
    }

    @Test
    void realServiceWithoutAdapterRefusesWithAMessageForTheModel() {
        try (TestScanServices t = TestScanServices.create()) {
            t.connector().adapterConnected(false, "unplugged");
            McpTools tools = new McpTools(t.service(), McpOptions.defaults());

            Map<String, Object> status = ok(tools, "get_session_status", tools.call("get_session_status", Map.of()));
            assertEquals("DISCONNECTED", map(status.get("adapter")).get("value"));

            String message = error(tools.call("read_fault_codes", Map.of("module", "17")));
            assertTrue(message.contains("adapter not connected: ask the user to plug in the CANable"), message);
            assertTrue(t.auditLines("tool-call").stream().anyMatch(l -> l.contains("\"outcome\":\"refused\"")));
            assertEquals(List.of(), t.auditLines("request"));
        }
    }

    @Test
    void realServiceModuleThatDoesNotAnswerIsData() {
        try (TestScanServices t = TestScanServices.create()) {
            McpTools tools = new McpTools(t.service(), McpOptions.defaults());
            Map<String, Object> out = ok(tools, "read_fault_codes", tools.call("read_fault_codes", Map.of("module", "46")));
            assertEquals("NOT_RESPONDING", out.get("status"));
            assertEquals(1, t.auditLines("tool-call").size());
        }
    }
}
