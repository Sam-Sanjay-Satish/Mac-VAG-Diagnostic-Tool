package io.github.samsanjaysatish.macvagdiag.mcp;

import static io.github.samsanjaysatish.macvagdiag.mcp.ToolTesting.error;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.scan.FakeScanService;
import io.github.samsanjaysatish.macvagdiag.scan.ToolCallRecord;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/** Untrusted arguments are refused with a clear message before {@code ScanService} is called, and audited. */
class ArgumentValidationTest {

    private final FakeScanService scan = new FakeScanService();
    private final McpTools tools = new McpTools(scan, McpOptions.defaults());

    static Stream<Arguments> badFaultCodeArguments() {
        Map<String, Object> nullModule = new HashMap<>();
        nullModule.put("module", null);
        return Stream.of(
                Arguments.of("missing module", Map.of(), "missing \"module\""),
                Arguments.of("null module", nullModule, "missing \"module\""),
                Arguments.of("one digit", Map.of("module", "1"), "not two hex digits"),
                Arguments.of("three digits", Map.of("module", "001"), "not two hex digits"),
                Arguments.of("0x prefix", Map.of("module", "0x01"), "not two hex digits"),
                Arguments.of("leading space", Map.of("module", " 01"), "not two hex digits"),
                Arguments.of("empty", Map.of("module", ""), "not two hex digits"),
                Arguments.of("not hex", Map.of("module", "ZZ"), "not two hex digits"),
                Arguments.of("unknown module", Map.of("module", "99"), "not a known module"),
                Arguments.of("unknown module 7F", Map.of("module", "7F"), "not a known module"),
                Arguments.of("number", Map.of("module", 1), "must be a string"),
                Arguments.of("array", Map.of("module", List.of("01")), "must be a string"),
                Arguments.of("object", Map.of("module", Map.of("address", "01")), "must be a string"),
                Arguments.of("boolean", Map.of("module", true), "must be a string"),
                Arguments.of("extra field", ordered("module", "01", "clear", true), "unknown argument(s) \"clear\""),
                Arguments.of("extra raw request", ordered("module", "01", "request", "14 FF FF FF"),
                        "unknown argument(s) \"request\""),
                Arguments.of("_meta-like field", ordered("module", "01", "_meta", Map.of()), "unknown argument(s)"),
                Arguments.of("wrong name", Map.of("address", "01"), "unknown argument(s) \"address\""));
    }

    @ParameterizedTest(name = "read_fault_codes {0}")
    @MethodSource("badFaultCodeArguments")
    void badFaultCodeArgumentsAreRefusedBeforeScanIsCalled(String what, Map<String, Object> args, String expected) {
        String message = error(tools.call("read_fault_codes", args));
        assertTrue(message.startsWith("invalid arguments: "), message);
        assertTrue(message.contains(expected), message);
        assertTrue(message.contains("Nothing was sent to the car."), message);
        assertNothingAskedOfScan();
        ToolCallRecord audit = onlyAudit();
        assertEquals("read_fault_codes", audit.tool());
        assertEquals("rejected", audit.outcome());
    }

    @ParameterizedTest(name = "{0} with an argument")
    @ValueSource(strings = {"get_session_status", "list_modules", "get_vehicle_info"})
    void toolsWithoutArgumentsRefuseAnyArgument(String tool) {
        String message = error(tools.call(tool, Map.of("module", "01")));
        assertTrue(message.contains("unknown argument(s) \"module\""), message);
        assertTrue(message.contains(tool + " takes no arguments"), message);
        assertNothingAskedOfScan();
        assertEquals("rejected", onlyAudit().outcome());
    }

    @Test
    void unknownToolIsRefusedAndAudited() {
        String message = error(tools.call("clear_fault_codes", Map.of("module", "01")));
        assertTrue(message.contains("There is no tool to clear codes"), message);
        assertNothingAskedOfScan();
        ToolCallRecord audit = onlyAudit();
        assertEquals(McpTools.UNKNOWN_TOOL, audit.tool());
        assertEquals("clear_fault_codes", audit.arguments().get("(tool)"));
        assertEquals("01", audit.arguments().get("module"));
    }

    @Test
    void hostileArgumentNamesAreQuotedSafelyInTheMessage() {
        String key = "x\n\"}],\"isError\":false‮" + "A".repeat(100);
        String message = error(tools.call("list_modules", Map.of(key, "v")));
        assertFalse(message.contains("\n"), message);
        assertFalse(message.contains("‮"), message);
        assertFalse(message.contains("A".repeat(41)), "long names are cut: " + message);
        assertNothingAskedOfScan();
    }

    @Test
    void manyUnknownArgumentsAreSummarised() {
        Map<String, Object> args = new LinkedHashMap<>();
        for (int i = 0; i < 50; i++) {
            args.put("k" + i, i);
        }
        String message = error(tools.call("get_vehicle_info", args));
        assertTrue(message.contains("and 45 more"), message);
        // ToolCallRecord bounds the audited arguments (20 + a "(more)" note).
        assertEquals(ToolCallRecord.MAX_ARGUMENTS + 1, onlyAudit().arguments().size());
        assertNothingAskedOfScan();
    }

    @Test
    void nonStringArgumentsAreAuditedWithTheirType() {
        tools.call("read_fault_codes", Map.of("module", 8));
        assertEquals("(number) 8", onlyAudit().arguments().get("module"));
    }

    private void assertNothingAskedOfScan() {
        assertEquals(List.of(), scan.calls().stream().filter(c -> !c.method().equals("recordToolCall")).toList());
    }

    private ToolCallRecord onlyAudit() {
        assertEquals(1, scan.toolCalls().size(), () -> "audit: " + scan.toolCalls());
        return scan.toolCalls().getFirst();
    }

    private static Map<String, Object> ordered(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
