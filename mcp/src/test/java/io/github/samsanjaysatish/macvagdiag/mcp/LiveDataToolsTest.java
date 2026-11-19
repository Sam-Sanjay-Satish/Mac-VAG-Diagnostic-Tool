package io.github.samsanjaysatish.macvagdiag.mcp;

import static io.github.samsanjaysatish.macvagdiag.mcp.ToolTesting.error;
import static io.github.samsanjaysatish.macvagdiag.mcp.ToolTesting.list;
import static io.github.samsanjaysatish.macvagdiag.mcp.ToolTesting.map;
import static io.github.samsanjaysatish.macvagdiag.mcp.ToolTesting.ok;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.scan.FakeScanService;
import io.github.samsanjaysatish.macvagdiag.scan.ScanRefusedException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The v2 live-data tools against {@link FakeScanService}: argument checks, output shapes, errors, audit. */
class LiveDataToolsTest {

    private final FakeScanService scan = new FakeScanService();
    private final McpTools tools = new McpTools(scan);

    private List<String> scanCalls() {
        return scan.calls().stream().map(FakeScanService.Call::method).filter(m -> !m.equals("recordToolCall")).toList();
    }

    static Stream<Arguments> badArguments() {
        List<Object> seventeen = new ArrayList<>();
        for (int i = 0; i < 17; i++) {
            seventeen.add("01:001.1");
        }
        Map<String, Object> nullValues = new HashMap<>();
        nullValues.put("values", null);
        return Stream.of(
                Arguments.of("list_available_data", Map.of(), "missing module"),
                Arguments.of("list_available_data", Map.of("module", "99"), "unknown module"),
                Arguments.of("list_available_data", Map.of("module", "01", "query", ""), "empty query"),
                Arguments.of("list_available_data", Map.of("module", "01", "query", "x".repeat(41)), "long query"),
                Arguments.of("list_available_data", Map.of("module", "01", "query", 5), "query not a string"),
                Arguments.of("list_available_data", Map.of("module", "01", "groups", List.of()), "empty groups"),
                Arguments.of("list_available_data", Map.of("module", "01", "groups", List.of(0)), "group 0"),
                Arguments.of("list_available_data", Map.of("module", "01", "groups", List.of(256)), "group 256"),
                Arguments.of("list_available_data", Map.of("module", "01", "groups", List.of("1")), "group as string"),
                Arguments.of("list_available_data", Map.of("module", "01", "groups", List.of(1.5)), "group 1.5"),
                Arguments.of("list_available_data", Map.of("module", "01", "groups", 1), "groups not a list"),
                Arguments.of("list_available_data", Map.of("module", "01", "request", "21 00"), "raw request"),
                Arguments.of("read_live_data", Map.of(), "missing values"),
                Arguments.of("read_live_data", nullValues, "null values"),
                Arguments.of("read_live_data", Map.of("values", List.of()), "no values"),
                Arguments.of("read_live_data", Map.of("values", seventeen), "17 values"),
                Arguments.of("read_live_data", Map.of("values", "01:001.1"), "values not a list"),
                Arguments.of("read_live_data", Map.of("values", List.of("01:1.1")), "short group"),
                Arguments.of("read_live_data", Map.of("values", List.of("01:000.1")), "group 000"),
                Arguments.of("read_live_data", Map.of("values", List.of("01:001.5")), "field 5"),
                Arguments.of("read_live_data", Map.of("values", List.of("21 01")), "raw bytes"),
                Arguments.of("read_live_data", Map.of("values", List.of(1)), "number"),
                Arguments.of("read_live_data", Map.of("values", List.of("01:001.1"), "seconds", 5), "extra seconds"),
                Arguments.of("record_live_data", Map.of("values", List.of("01:001.1")), "missing seconds"),
                Arguments.of("record_live_data", Map.of("values", List.of("01:001.1"), "seconds", 0), "0 s"),
                Arguments.of("record_live_data", Map.of("values", List.of("01:001.1"), "seconds", 121), "121 s"),
                Arguments.of("record_live_data", Map.of("values", List.of("01:001.1"), "seconds", 2.5), "2.5 s"),
                Arguments.of("record_live_data", Map.of("values", List.of("01:001.1"), "seconds", "5"), "string s"),
                Arguments.of("record_live_data", Map.of("values", List.of("01:001.1"), "seconds", 5, "rate", 100),
                        "extra rate"));
    }

    @ParameterizedTest(name = "{0}: {2}")
    @MethodSource("badArguments")
    void badArgumentsAreRefusedBeforeScan(String tool, Map<String, Object> args, String why) {
        String message = error(tools.call(tool, args));
        assertTrue(message.startsWith("invalid arguments:") && message.endsWith("Nothing was sent to the car."), message);
        assertEquals(List.of(), scanCalls(), "scan was never called");
        assertEquals("rejected", scan.toolCalls().getLast().outcome());
        assertEquals(tool, scan.toolCalls().getLast().tool());
    }

    @Test
    @DisplayName("list_available_data: titles by default; fields with IDs, names, specs for groups or a query")
    void availableData() {
        Map<String, Object> all = ok(tools, "list_available_data", tools.call("list_available_data", Map.of("module", "01")));
        assertEquals("LABELS", all.get("source"));
        assertEquals("03L-906-018.lbl", all.get("labelSource"));
        assertEquals(2, all.get("groupCount"));
        assertEquals(4, all.get("fieldCount"));
        assertEquals(List.of(81), all.get("withheldGroups"));
        for (Object g : list(all.get("groups"))) {
            assertEquals(null, map(g).get("fields"));
        }
        assertTrue(((String) all.get("hint")).contains("\"groups\""));

        Map<String, Object> g1 = ok(tools, "list_available_data",
                tools.call("list_available_data", Map.of("module", "01", "groups", List.of(1))));
        List<Object> groups = list(g1.get("groups"));
        assertEquals(1, groups.size());
        Map<String, Object> rpm = map(list(map(groups.getFirst()).get("fields")).getFirst());
        assertEquals("01:001.1", rpm.get("id"));
        assertEquals("Engine Speed (sample)", rpm.get("name"));
        assertEquals(List.of("Range: 0...5000 /min", "Specification (Idle): 780...920 /min"), rpm.get("specs"));
        assertEquals(null, rpm.get("unit"));

        Map<String, Object> q = ok(tools, "list_available_data",
                tools.call("list_available_data", Map.of("module", "01", "query", "OIL")));
        List<Object> qg = list(q.get("groups"));
        assertEquals(1, qg.size());
        assertEquals(List.of("01:007.2"), list(map(qg.getFirst()).get("fields")).stream().map(f -> map(f).get("id")).toList());
        assertEquals("query \"OIL\"", q.get("filter"));

        Map<String, Object> uds = ok(tools, "list_available_data", tools.call("list_available_data", Map.of("module", "17")));
        assertEquals("NOT_SUPPORTED", uds.get("source"));
        assertEquals("ok", scan.toolCalls().getLast().outcome());
    }

    @Test
    @DisplayName("read_live_data: number, unit, name, specs, state; IDs not from the catalog are refused by scan")
    void readLiveData() {
        String noCatalog = error(tools.call("read_live_data", Map.of("values", List.of("01:001.1"))));
        assertTrue(noCatalog.contains("list its available data first") && noCatalog.contains("Nothing was sent"), noCatalog);
        tools.call("list_available_data", Map.of("module", "01"));
        Map<String, Object> r = ok(tools, "read_live_data",
                tools.call("read_live_data", Map.of("values", List.of("01:001.1", "01:001.4", "01:007.2"))));
        List<Object> values = list(r.get("values"));
        Map<String, Object> rpm = map(values.get(0));
        assertEquals(828, rpm.get("value"));
        assertEquals("/min", rpm.get("unit"));
        assertEquals("OK", rpm.get("state"));
        assertEquals("01 73 24", rpm.get("raw"));
        assertEquals(87.3, map(values.get(1)).get("value"));
        assertEquals("EMPTY", map(values.get(2)).get("state"));
        assertEquals(null, map(values.get(2)).get("value"));
        String unknown = error(tools.call("read_live_data", Map.of("values", List.of("01:099.1"))));
        assertTrue(unknown.contains("not in module 01's catalog"), unknown);
        assertEquals("rejected", scan.toolCalls().getLast().outcome());
    }

    @Test
    @DisplayName("record_live_data: summary per value and the CSV path; seconds passed through")
    void recordLiveData() {
        tools.call("list_available_data", Map.of("module", "01"));
        Map<String, Object> r = ok(tools, "record_live_data",
                tools.call("record_live_data", Map.of("values", List.of("01:001.1", "01:007.2"), "seconds", 30)));
        assertEquals(30, r.get("requestedSeconds"));
        assertTrue(((String) r.get("file")).endsWith(".csv"));
        Map<String, Object> rpm = map(list(r.get("series")).getFirst());
        assertEquals(820, rpm.get("min"));
        assertEquals(900, rpm.get("max"));
        assertEquals(851.6, rpm.get("average"));
        Map<String, Object> oil = map(list(r.get("series")).get(1));
        assertEquals(null, oil.get("min"));
        assertFalse(list(oil.get("events")).isEmpty());
        assertTrue(scan.calls().stream().anyMatch(c -> c.method().equals("recordLiveData") && c.argument().endsWith("PT30S")));
    }

    @Test
    @DisplayName("scan refusals map to the usual messages")
    void refusals() {
        scan.failWith(ScanRefusedException.adapterNotConnected("unplugged"));
        String m = error(tools.call("list_available_data", Map.of("module", "01")));
        assertTrue(m.startsWith("refused, nothing was sent to the car") && m.contains("plug in the CANable"), m);
        assertEquals("refused", scan.toolCalls().getLast().outcome());
    }
}
