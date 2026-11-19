package io.github.samsanjaysatish.macvagdiag.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.scan.FakeScanService;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * <b>The registered tool set is exactly the v1 list.</b> Adding a tool means changing {@link #EXPECTED} here on
 * purpose, so a raw-send, clear, write or actuator tool can't slip in (root {@code CLAUDE.md}, safety rule 5).
 */
class ToolListTest {

    static final List<String> EXPECTED = List.of("get_session_status", "list_modules", "read_fault_codes",
            "get_vehicle_info",
            // v2 live data (2026-11-19, user's plan): typed reads of catalogued measuring-block values only.
            "list_available_data", "read_live_data", "record_live_data");

    /** Words no tool name or description may offer. */
    private static final List<String> FORBIDDEN = List.of("clear", "erase", "delete", "reset", "write", "send",
            "raw_request", "request_raw", "actuat", "output_test", "adapt", "coding", "routine", "flash", "security",
            "basic_setting");

    @Test
    void registeredToolsAreExactlyThePinnedList() {
        try (McpEndpoint endpoint = McpEndpoint.create(new FakeScanService())) {
            assertEquals(EXPECTED, endpoint.tools().stream().map(McpSchema.Tool::name).toList());
            assertEquals(EXPECTED, McpTools.NAMES);
            assertEquals(EXPECTED, endpoint.handlers().definitions().stream().map(McpSchema.Tool::name).toList());
        }
    }

    @Test
    void noToolOffersAnythingButReading() {
        for (McpSchema.Tool tool : new McpTools(new FakeScanService()).definitions()) {
            String name = tool.name().toLowerCase(Locale.ROOT);
            for (String word : FORBIDDEN) {
                assertFalse(name.contains(word), tool.name() + " contains " + word);
            }
            assertEquals(Boolean.TRUE, tool.annotations().readOnlyHint(), tool.name());
            assertEquals(Boolean.FALSE, tool.annotations().destructiveHint(), tool.name());
        }
    }

    @Test
    void everyToolHasAStrictInputSchemaAndAnOutputSchema() {
        for (McpSchema.Tool tool : new McpTools(new FakeScanService()).definitions()) {
            assertEquals("object", tool.inputSchema().get("type"), tool.name());
            assertEquals(false, tool.inputSchema().get("additionalProperties"), tool.name());
            assertNotNull(tool.outputSchema(), tool.name());
            assertEquals(false, tool.outputSchema().get("additionalProperties"), tool.name());
            assertTrue(tool.description().length() > 40, tool.name());
        }
    }

    @Test
    void onlyTheseArgumentsExist() {
        java.util.Map<String, List<String>> expected = java.util.Map.of(
                "read_fault_codes", List.of("module"),
                "list_available_data", List.of("module", "groups", "query"),
                "read_live_data", List.of("values"),
                "record_live_data", List.of("values", "seconds"));
        java.util.Map<String, List<String>> required = java.util.Map.of(
                "read_fault_codes", List.of("module"),
                "list_available_data", List.of("module"),
                "read_live_data", List.of("values"),
                "record_live_data", List.of("values", "seconds"));
        for (McpSchema.Tool tool : new McpTools(new FakeScanService()).definitions()) {
            Object properties = tool.inputSchema().get("properties");
            if (expected.containsKey(tool.name())) {
                assertEquals(new java.util.TreeSet<>(expected.get(tool.name())),
                        new java.util.TreeSet<>(ToolTesting.map(properties).keySet()), tool.name());
                assertEquals(required.get(tool.name()), tool.inputSchema().get("required"), tool.name());
            } else {
                assertTrue(ToolTesting.map(properties).isEmpty(), tool.name());
            }
            for (Object p : ToolTesting.map(properties).keySet()) {
                String arg = String.valueOf(p).toLowerCase(Locale.ROOT);
                assertFalse(arg.contains("request") || arg.contains("raw") || arg.contains("bytes")
                        || arg.contains("hex") || arg.contains("service"), tool.name() + " takes " + p);
            }
        }
    }
}
