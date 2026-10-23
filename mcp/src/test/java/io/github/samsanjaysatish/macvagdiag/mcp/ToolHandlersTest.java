package io.github.samsanjaysatish.macvagdiag.mcp;

import static io.github.samsanjaysatish.macvagdiag.mcp.ToolTesting.list;
import static io.github.samsanjaysatish.macvagdiag.mcp.ToolTesting.map;
import static io.github.samsanjaysatish.macvagdiag.mcp.ToolTesting.ok;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.scan.FakeScanService;
import io.github.samsanjaysatish.macvagdiag.scan.ModuleAddress;
import io.github.samsanjaysatish.macvagdiag.scan.SampleScanData;
import io.github.samsanjaysatish.macvagdiag.scan.SessionStatus;
import io.github.samsanjaysatish.macvagdiag.scan.ToolCallRecord;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Each tool against {@link FakeScanService} ({@link SampleScanData}): JSON shape, units, timestamps, raw IDs, VIN. */
class ToolHandlersTest {

    private static final String AT = SampleScanData.AT.toString();
    private static final String MASKED_VIN = "SAMPLEVIN00******";

    private final FakeScanService scan = new FakeScanService();
    private final McpTools tools = new McpTools(scan, McpOptions.defaults());

    @Test
    void getSessionStatusReportsAdapterIgnitionAndEngineAsValues() {
        Map<String, Object> out = ok(tools, "get_session_status", tools.call("get_session_status", Map.of()));

        Map<String, Object> adapter = map(out.get("adapter"));
        assertEquals("adapter", adapter.get("id"));
        assertEquals("CONNECTED", adapter.get("value"));
        assertTrue(adapter.containsKey("unit"));
        assertNull(adapter.get("unit"));
        assertEquals(AT, adapter.get("timestamp"));
        assertEquals("sample adapter", adapter.get("detail"));
        assertEquals("UNKNOWN", map(out.get("ignition")).get("value"));
        assertEquals("UNKNOWN", map(out.get("engine")).get("value"));
        assertEquals(false, out.get("demo"));
        assertEquals(List.of(new FakeScanService.Call("sessionStatus", "mcp:get_session_status", "")),
                scan.calls().stream().filter(c -> !c.method().equals("recordToolCall")).toList());
    }

    @Test
    void getSessionStatusWithoutAdapterSaysToPlugItIn() {
        scan.status(new SessionStatus(SessionStatus.AdapterState.DISCONNECTED, "no serial port",
                SessionStatus.VehicleCondition.unknown("x"), SessionStatus.VehicleCondition.unknown("y"), false,
                SampleScanData.AT));
        Map<String, Object> out = ok(tools, "get_session_status", tools.call("get_session_status", null));
        assertEquals("DISCONNECTED", map(out.get("adapter")).get("value"));
        assertTrue(((String) out.get("hint")).contains("plug the CANable"));
    }

    @Test
    void listModulesHasEveryKnownModuleWithReachabilityAndTimestamp() {
        Map<String, Object> out = ok(tools, "list_modules", tools.call("list_modules", Map.of()));

        List<Object> modules = list(out.get("modules"));
        assertEquals(ModuleAddress.known().size(), modules.size());
        Map<String, Object> engine = map(modules.getFirst());
        assertEquals("01", engine.get("address"));
        assertEquals("Engine", engine.get("name"));
        assertEquals("RESPONDING", engine.get("reachability"));
        assertEquals("UDS", engine.get("protocol"));
        assertEquals("UDS over ISO-TP (7E0/7E8)", engine.get("link"));
        assertEquals(AT, engine.get("timestamp"));
        Map<String, Object> hvac = modules.stream().map(ToolTesting::map).filter(m -> m.get("address").equals("08"))
                .findFirst().orElseThrow();
        assertEquals("NOT_RESPONDING", hvac.get("reachability"));
        assertNull(hvac.get("protocol"));
        Map<String, Object> counts = map(out.get("counts"));
        assertEquals(7, counts.get("responding"));
        assertEquals(6, counts.get("notResponding"));
        assertEquals(0, counts.get("notChecked"));
    }

    @Test
    void readFaultCodesUdsKeepsRawIdsStatusByteAndFreezeFrame() {
        Map<String, Object> out = ok(tools, "read_fault_codes", tools.call("read_fault_codes", Map.of("module", "01")));

        assertEquals(Map.of("address", "01", "name", "Engine"), out.get("module"));
        assertEquals("FAULTS", out.get("status"));
        assertEquals(1, out.get("faultCount"));
        assertEquals(AT, out.get("startedAt"));
        Map<String, Object> fault = map(list(out.get("faults")).getFirst());
        assertEquals("P0401-00", fault.get("id"));
        assertEquals("P0401", fault.get("code"));
        assertEquals("UDS", fault.get("protocol"));
        assertEquals("04 01 00 2F", fault.get("record"));
        Map<String, Object> status = map(fault.get("status"));
        assertEquals("status byte", status.get("id"));
        assertEquals("2F", status.get("raw"));
        assertEquals(0x2F, status.get("value"));
        assertNull(status.get("unit"));
        assertEquals(List.of("testFailed", "testFailedThisOperationCycle", "pendingDTC", "confirmedDTC",
                "testFailedSinceLastClear"), fault.get("statusFlags"));
        assertEquals("00", map(fault.get("failureType")).get("raw"));
        Map<String, Object> ff = map(fault.get("freezeFrame"));
        assertEquals("READ", ff.get("availability"));
        Map<String, Object> record = map(list(ff.get("records")).getFirst());
        assertEquals("19 04 04 01 00 FF", record.get("id"));
        assertEquals("01 01 F4 0D 2A", record.get("raw"));
        assertNull(record.get("text"));
        assertNull(record.get("unit"));
        assertEquals(List.of("59 04 04 01 00 2F 01 01 F4 0D 2A"), ff.get("responses"));
        assertEquals(List.of(new FakeScanService.Call("readFaultCodes", "mcp:read_fault_codes", "01")),
                scan.calls().stream().filter(c -> !c.method().equals("recordToolCall")).toList());
    }

    @Test
    void readFaultCodesKwpHasNoFailureTypeAndSaysWhyNoFreezeFrame() {
        Map<String, Object> out = ok(tools, "read_fault_codes", tools.call("read_fault_codes", Map.of("module", "03")));
        Map<String, Object> fault = map(list(out.get("faults")).getFirst());
        assertEquals("01314", fault.get("id"));
        assertEquals("KWP2000", fault.get("protocol"));
        assertNull(fault.get("failureType"));
        assertEquals("NOT_AVAILABLE", map(fault.get("freezeFrame")).get("availability"));
        assertTrue(fault.containsKey("statusText"));
        assertFalse(fault.containsKey("description"), "no labels or descriptions (out of scope)");
    }

    @Test
    void readFaultCodesOnModuleThatDoesNotAnswerIsDataNotAnError() {
        Map<String, Object> out = ok(tools, "read_fault_codes", tools.call("read_fault_codes", Map.of("module", "08")));
        assertEquals("NOT_RESPONDING", out.get("status"));
        assertEquals(List.of(), out.get("faults"));
        assertNull(out.get("link"));
    }

    @Test
    void lowerCaseModuleAddressIsAccepted() {
        scan.faults(ModuleAddress.parse("17"), SampleScanData.moduleResult(ModuleAddress.parse("17")));
        ok(tools, "read_fault_codes", tools.call("read_fault_codes", Map.of("module", "17")));
        // No known module has a letter in it, so "1a"-style input is rejected as unknown, not as malformed.
        assertTrue(ToolTesting.error(tools.call("read_fault_codes", Map.of("module", "1a"))).contains("not a known module"));
    }

    @Test
    void vehicleInfoMasksTheVinEverywhereByDefault() {
        var r = tools.call("get_vehicle_info", Map.of());
        Map<String, Object> out = ok(tools, "get_vehicle_info", r);

        Map<String, Object> vin = map(out.get("vin"));
        assertEquals("vin", vin.get("id"));
        assertEquals(MASKED_VIN, vin.get("value"));
        assertEquals("01", vin.get("source"));
        assertNull(vin.get("unit"));
        assertEquals(AT, vin.get("timestamp"));
        assertEquals(true, out.get("vinMasked"));
        Map<String, Object> engine = map(list(out.get("modules")).getFirst());
        Map<String, Object> ident = map(engine.get("identification"));
        Map<String, Object> raw = map(list(ident.get("raw")).getFirst());
        assertEquals("22 F1 90", raw.get("id"));
        assertEquals("(masked: contains the VIN)", raw.get("raw"));
        Map<String, Object> partNumber = list(ident.get("values")).stream().map(ToolTesting::map)
                .filter(v -> v.get("id").equals("partNumber")).findFirst().orElseThrow();
        assertEquals("03L906018XX", partNumber.get("value"));
        assertNull(partNumber.get("unit"));
        assertEquals(AT, partNumber.get("timestamp"));
        assertFalse(ToolTesting.text(r).contains(SampleScanData.VIN), "unmasked VIN leaked");
        assertFalse(ToolTesting.toJson(r.structuredContent()).contains(SampleScanData.VIN), "unmasked VIN leaked");
        assertFalse(ToolTesting.toJson(r.structuredContent()).contains("53 41 4D 50 4C 45"), "VIN hex leaked");
    }

    @Test
    void vehicleInfoShowsTheVinWhenTheUserTurnedMaskingOff() {
        McpTools unmasked = new McpTools(scan, McpOptions.defaults().withMaskVin(false));
        Map<String, Object> out = ok(unmasked, "get_vehicle_info", unmasked.call("get_vehicle_info", Map.of()));
        assertEquals(SampleScanData.VIN, map(out.get("vin")).get("value"));
        assertEquals(false, out.get("vinMasked"));
    }

    @Test
    void readFaultCodesMasksTheVinInIdentificationToo() {
        var masked = tools.call("read_fault_codes", Map.of("module", "01"));
        assertFalse(ToolTesting.text(masked).contains(SampleScanData.VIN));
        assertTrue(ToolTesting.text(masked).contains(MASKED_VIN));
        McpTools unmasked = new McpTools(scan, new McpOptions(false));
        assertTrue(ToolTesting.text(unmasked.call("read_fault_codes", Map.of("module", "01"))).contains(SampleScanData.VIN));
    }

    @Test
    void everyCallIsAuditedWithToolArgumentsOutcomeAndSummary() {
        tools.call("get_session_status", Map.of());
        tools.call("list_modules", Map.of());
        tools.call("read_fault_codes", Map.of("module", "01"));
        tools.call("get_vehicle_info", Map.of());

        List<ToolCallRecord> audit = scan.toolCalls();
        assertEquals(List.of("get_session_status", "list_modules", "read_fault_codes", "get_vehicle_info"),
                audit.stream().map(ToolCallRecord::tool).toList());
        assertTrue(audit.stream().allMatch(c -> c.outcome().equals("ok")));
        assertEquals(Map.of("module", "01"), audit.get(2).arguments());
        assertEquals("module 01 Engine: FAULTS, 1 fault(s)", audit.get(2).summary());
        assertTrue(audit.get(3).summary().contains("(masked)"));
        assertTrue(audit.stream().noneMatch(c -> c.summary().contains(SampleScanData.VIN)), "no VIN in summaries");
    }
}
