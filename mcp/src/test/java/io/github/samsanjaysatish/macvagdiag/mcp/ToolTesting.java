package io.github.samsanjaysatish.macvagdiag.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Helpers for tool tests: unwrap results, check output schemas and the output rules. */
final class ToolTesting {

    private ToolTesting() {
    }

    /** The structured content of a successful call, after checking it against the tool's output schema. */
    static Map<String, Object> ok(McpTools tools, String tool, CallToolResult r) {
        assertFalse(Boolean.TRUE.equals(r.isError()), () -> tool + " failed: " + text(r));
        Map<String, Object> structured = map(r.structuredContent());
        McpSchema.Tool def = tools.definitions().stream().filter(t -> t.name().equals(tool)).findFirst().orElseThrow();
        var v = McpJsonDefaults.getSchemaValidator().validate(def.outputSchema(), structured);
        assertTrue(v.valid(), () -> tool + " output doesn't match its schema: " + v.errorMessage());
        // The text content is the same JSON, for clients that only read text.
        assertEquals(structured, parse(text(r)));
        assertOutputRules(structured);
        return structured;
    }

    /** The message of a failed call. */
    static String error(CallToolResult r) {
        assertTrue(Boolean.TRUE.equals(r.isError()), () -> "expected an error, got " + text(r));
        return text(r);
    }

    static String text(CallToolResult r) {
        assertEquals(1, r.content().size());
        return assertInstanceOf(McpSchema.TextContent.class, r.content().getFirst()).text();
    }

    static Map<String, Object> parse(String json) {
        try {
            return McpJsonDefaults.getMapper().readValue(json, new TypeRef<Map<String, Object>>() {
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String toJson(Object value) {
        try {
            return McpJsonDefaults.getMapper().writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked") // JSON objects decode to String-keyed maps
    static Map<String, Object> map(Object o) {
        return (Map<String, Object>) assertInstanceOf(Map.class, o);
    }

    @SuppressWarnings("unchecked") // JSON arrays decode to lists
    static List<Object> list(Object o) {
        return (List<Object>) assertInstanceOf(List.class, o);
    }

    /**
     * Output rules: every object with an {@code id} (a value) also has a {@code unit} key (null allowed) and a
     * parseable {@code timestamp}; values with raw bytes keep them in {@code raw}.
     */
    static void assertOutputRules(Object node) {
        if (node instanceof Map<?, ?> m) {
            if (m.containsKey("id")) {
                assertTrue(m.containsKey("unit"), () -> "value without unit: " + m);
                Object ts = m.get("timestamp");
                assertNotNull(ts, () -> "value without timestamp: " + m);
                Instant.parse((String) ts);
            }
            m.values().forEach(ToolTesting::assertOutputRules);
        } else if (node instanceof List<?> l) {
            l.forEach(ToolTesting::assertOutputRules);
        }
    }
}
