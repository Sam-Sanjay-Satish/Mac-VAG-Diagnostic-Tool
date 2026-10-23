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
            "get_vehicle_info");

    /** Words no tool name or description may offer. */
    private static final List<String> FORBIDDEN = List.of("clear", "erase", "delete", "reset", "write", "send",
            "raw_request", "request_raw", "actuat", "output_test", "adapt", "coding", "routine", "flash", "security",
            "basic_setting");

    @Test
    void registeredToolsAreExactlyTheV1List() {
        try (McpEndpoint endpoint = McpEndpoint.create(new FakeScanService(), McpOptions.defaults())) {
            assertEquals(EXPECTED, endpoint.tools().stream().map(McpSchema.Tool::name).toList());
            assertEquals(EXPECTED, McpTools.NAMES);
            assertEquals(EXPECTED, endpoint.handlers().definitions().stream().map(McpSchema.Tool::name).toList());
        }
    }

    @Test
    void noToolOffersAnythingButReading() {
        for (McpSchema.Tool tool : new McpTools(new FakeScanService(), McpOptions.defaults()).definitions()) {
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
        for (McpSchema.Tool tool : new McpTools(new FakeScanService(), McpOptions.defaults()).definitions()) {
            assertEquals("object", tool.inputSchema().get("type"), tool.name());
            assertEquals(false, tool.inputSchema().get("additionalProperties"), tool.name());
            assertNotNull(tool.outputSchema(), tool.name());
            assertEquals(false, tool.outputSchema().get("additionalProperties"), tool.name());
            assertTrue(tool.description().length() > 40, tool.name());
        }
    }

    @Test
    void onlyReadFaultCodesTakesAnArgumentAndItIsTheModule() {
        for (McpSchema.Tool tool : new McpTools(new FakeScanService(), McpOptions.defaults()).definitions()) {
            Object properties = tool.inputSchema().get("properties");
            if (tool.name().equals("read_fault_codes")) {
                assertEquals(List.of("module"), List.copyOf(ToolTesting.map(properties).keySet()));
                assertEquals(List.of("module"), tool.inputSchema().get("required"));
            } else {
                assertTrue(ToolTesting.map(properties).isEmpty(), tool.name());
            }
        }
    }
}
