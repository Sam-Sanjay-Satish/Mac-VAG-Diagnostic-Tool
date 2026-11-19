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
            McpTools tools = new McpTools(scan);

            Map<String, Object> status = ok(tools, "get_session_status", tools.call("get_session_status", Map.of()));
            assertEquals("CONNECTED", map(status.get("adapter")).get("value"));
            assertEquals(true, status.get("demo"));

            Map<String, Object> modules = ok(tools, "list_modules", tools.call("list_modules", Map.of()));
            Map<String, String> reach = list(modules.get("modules")).stream().map(ToolTesting::map)
                    .collect(Collectors.toMap(m -> (String) m.get("address"), m -> (String) m.get("reachability")));
            assertEquals("RESPONDING", reach.get("01"));
            assertEquals("RESPONDING", reach.get("17"));
            assertEquals("NOT_RESPONDING", reach.get("2E"), "listed by the gateway, but cannot be reached");
            assertEquals("NOT_CHECKED", reach.get("04"), "no link of its own: gateway status only");
            assertEquals(16, reach.size());
            assertEquals(true, modules.get("demo"));

            Map<String, Object> engine = ok(tools, "read_fault_codes", tools.call("read_fault_codes", Map.of("module", "01")));
            assertEquals("FAULTS", engine.get("status"));
            assertEquals("KWP2000", engine.get("protocol"));
            assertEquals(List.of("001025", "001025", "009315"), list(engine.get("faults")).stream().map(ToolTesting::map)
                    .map(f -> f.get("code")).toList());
            assertEquals(List.of("P0401", "P0401", "P2463"), list(engine.get("faults")).stream().map(ToolTesting::map)
                    .map(f -> f.get("obdCode")).toList());

            Map<String, Object> instruments = ok(tools, "read_fault_codes",
                    tools.call("read_fault_codes", Map.of("module", "17")));
            assertEquals("UDS", instruments.get("protocol"));
            assertEquals(List.of("B1A10-12", "U0115-00"), list(instruments.get("faults")).stream()
                    .map(ToolTesting::map).map(f -> f.get("id")).toList());
            Map<String, Object> ff = map(map(list(engine.get("faults")).getFirst()).get("freezeFrame"));
            assertEquals("READ", ff.get("availability"));
            assertFalse(list(ff.get("records")).isEmpty(), "raw freeze-frame records with their request IDs");

            Map<String, Object> abs = ok(tools, "read_fault_codes", tools.call("read_fault_codes", Map.of("module", "03")));
            assertEquals("KWP2000", abs.get("protocol"));
            assertEquals("01314", map(list(abs.get("faults")).getFirst()).get("code"));

            var infoResult = tools.call("get_vehicle_info", Map.of());
            Map<String, Object> info = ok(tools, "get_vehicle_info", infoResult);
            assertEquals(123450, ((Number) map(info.get("mileage")).get("value")).intValue(), "the demo car's odometer");
            assertEquals("17", map(info.get("mileage")).get("source"));
            assertFalse(ToolTesting.text(infoResult).contains("VIN"));

            // v2 live data on the demo engine, with the bundled made-up demo labels.
            Map<String, Object> catalog = ok(tools, "list_available_data",
                    tools.call("list_available_data", Map.of("module", "01")));
            assertEquals("LABELS", catalog.get("source"));
            assertEquals("03L-906-018.lbl", catalog.get("labelSource"));
            assertEquals(List.of(81), catalog.get("withheldGroups"));
            assertEquals(null, map(list(catalog.get("groups")).getFirst()).get("fields"), "titles only by default");
            Map<String, Object> boost = ok(tools, "list_available_data",
                    tools.call("list_available_data", Map.of("module", "01", "query", "boost")));
            List<Object> boostGroups = list(boost.get("groups"));
            assertEquals(1, boostGroups.size());
            Map<String, Object> actual = map(list(map(boostGroups.getFirst()).get("fields")).get(2));
            assertEquals("01:011.3", actual.get("id"));
            assertEquals("Boost Pressure (actual)", actual.get("name"));
            assertEquals(List.of("Range: 0.0...2600.0 mbar"), actual.get("specs"));

            Map<String, Object> live = ok(tools, "read_live_data",
                    tools.call("read_live_data", Map.of("values", List.of("01:001.1", "01:007.2", "01:011.3"))));
            assertEquals("OK", live.get("status"));
            List<Object> values = list(live.get("values"));
            assertEquals("/min", map(values.get(0)).get("unit"));
            assertEquals("Engine Speed (demo)", map(values.get(0)).get("name"));
            assertTrue(((Number) map(values.get(0)).get("value")).intValue() >= 828);
            assertEquals("EMPTY", map(values.get(1)).get("state"));
            assertEquals(null, map(values.get(1)).get("value"));
            assertEquals("mbar", map(values.get(2)).get("unit"));

            Map<String, Object> rec = ok(tools, "record_live_data",
                    tools.call("record_live_data", Map.of("values", List.of("01:001.1"), "seconds", 3)));
            assertEquals("OK", rec.get("status"));
            Map<String, Object> rpm = map(list(rec.get("series")).getFirst());
            assertTrue(((Number) rpm.get("max")).doubleValue() > ((Number) rpm.get("min")).doubleValue());
            assertTrue(java.nio.file.Files.exists(java.nio.file.Path.of((String) rec.get("file"))), rec.get("file").toString());

            String refused = ToolTesting.error(tools.call("read_live_data", Map.of("values", List.of("01:081.1"))));
            assertTrue(refused.contains("withheld for privacy") && refused.contains("Nothing was sent"), refused);
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
    void rejectedCallIsAuditedAndSendsNothing() throws Exception {
        try (ScanService scan = demo()) {
            McpTools tools = new McpTools(scan);
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
            McpTools tools = new McpTools(t.service());

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
            McpTools tools = new McpTools(t.service());
            Map<String, Object> out = ok(tools, "read_fault_codes", tools.call("read_fault_codes", Map.of("module", "2E")));
            assertEquals("NOT_RESPONDING", out.get("status"));
            assertEquals(1, t.auditLines("tool-call").size());
        }
    }
}
