package io.github.samsanjaysatish.macvagdiag.mcp;

import static io.github.samsanjaysatish.macvagdiag.mcp.ToolTesting.list;
import static io.github.samsanjaysatish.macvagdiag.mcp.ToolTesting.map;
import static io.github.samsanjaysatish.macvagdiag.mcp.ToolTesting.parse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.scan.FakeScanService;
import io.github.samsanjaysatish.macvagdiag.scan.SampleScanData;
import io.github.samsanjaysatish.macvagdiag.scan.ToolCallRecord;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The real servlet ({@link McpEndpoint#servlet()}: our checks + the SDK's Streamable HTTP transport + the SDK server)
 * driven through {@link FakeHttp}, a minimal in-test servlet harness: initialize, {@code tools/list},
 * {@code tools/call}, unknown tools, and the Host / Origin refusals. {@code server} tests the same over real HTTP.
 */
class ServletTransportTest {

    private static final String SESSION = "Mcp-Session-Id";

    private final FakeScanService scan = new FakeScanService();
    private final McpEndpoint endpoint = McpEndpoint.create(scan, McpOptions.defaults());

    @AfterEach
    void close() {
        endpoint.close();
    }

    private static Map<String, String> headers(String... extra) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Host", "localhost:8080");
        h.put("Accept", "application/json, text/event-stream");
        h.put("Content-Type", "application/json");
        for (int i = 0; i < extra.length; i += 2) {
            h.put(extra[i], extra[i + 1]);
        }
        return h;
    }

    private static String initialize() {
        return """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
                 "capabilities":{},"clientInfo":{"name":"test-client","version":"1"}}}""";
    }

    /** initialize + notifications/initialized; returns the session ID. */
    private String connect() throws Exception {
        FakeHttp.Response init = FakeHttp.exchange(endpoint.servlet(), "POST", headers(), initialize());
        assertEquals(200, init.status(), init.body());
        String session = init.header(SESSION);
        assertNotNull(session, "session id header");
        FakeHttp.Response initialized = FakeHttp.exchange(endpoint.servlet(), "POST", headers(SESSION, session),
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        assertEquals(202, initialized.status(), initialized.body());
        return session;
    }

    /** One JSON-RPC request in the session; returns the one response message. */
    private Map<String, Object> rpc(String session, String body) throws Exception {
        FakeHttp.Response r = FakeHttp.exchange(endpoint.servlet(), "POST", headers(SESSION, session), body);
        assertEquals(200, r.status(), r.body());
        assertTrue(r.header("Content-Type").startsWith("text/event-stream"), r.header("Content-Type"));
        assertTrue(r.asyncCompleted(), "the SSE response stream is closed after the answer");
        List<String> messages = r.messages();
        assertEquals(1, messages.size(), r.body());
        return parse(messages.getFirst());
    }

    @Test
    void initializeAdvertisesToolsAndInstructions() throws Exception {
        FakeHttp.Response init = FakeHttp.exchange(endpoint.servlet(), "POST", headers(), initialize());
        assertEquals(200, init.status(), init.body());
        Map<String, Object> result = map(parse(init.body()).get("result"));
        assertEquals("macvagdiag", map(result.get("serverInfo")).get("name"));
        assertTrue(map(result.get("capabilities")).containsKey("tools"));
        assertFalse(map(result.get("capabilities")).containsKey("resources"));
        assertFalse(map(result.get("capabilities")).containsKey("prompts"));
        assertTrue(((String) result.get("instructions")).contains("read-only"));
        assertEquals(List.of(), scan.calls(), "initialize doesn't touch scan");
    }

    @Test
    void toolsListOverTheTransportIsExactlyTheV1List() throws Exception {
        String session = connect();
        Map<String, Object> response = rpc(session, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        List<Object> tools = list(map(response.get("result")).get("tools"));
        assertEquals(ToolListTest.EXPECTED, tools.stream().map(ToolTesting::map).map(t -> t.get("name")).toList());
        for (Object t : tools) {
            assertNotNull(map(t).get("outputSchema"));
            assertEquals(false, map(map(t).get("inputSchema")).get("additionalProperties"));
        }
    }

    @Test
    void toolsCallReturnsStructuredJsonAndTextAndIsAudited() throws Exception {
        String session = connect();
        Map<String, Object> response = rpc(session, """
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"read_fault_codes",
                 "arguments":{"module":"01"}}}""");
        Map<String, Object> result = map(response.get("result"));
        assertEquals(false, result.get("isError"), String.valueOf(result));
        Map<String, Object> structured = map(result.get("structuredContent"));
        assertEquals("FAULTS", structured.get("status"));
        assertEquals("P0401-00", map(list(structured.get("faults")).getFirst()).get("id"));
        String text = (String) map(list(result.get("content")).getFirst()).get("text");
        assertEquals(structured, parse(text));
        assertFalse(text.contains(SampleScanData.VIN), "VIN masked over the wire too");

        assertEquals(List.of("readFaultCodes"), scan.calls().stream().map(FakeScanService.Call::method)
                .filter(m -> !m.equals("recordToolCall")).toList());
        assertEquals("read_fault_codes", scan.toolCalls().getFirst().tool());
        assertEquals("ok", scan.toolCalls().getFirst().outcome());
    }

    @Test
    void badArgumentsOverTheTransportAreAToolErrorAndAudited() throws Exception {
        String session = connect();
        Map<String, Object> response = rpc(session, """
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"read_fault_codes",
                 "arguments":{"module":"01","force":true}}}""");
        Map<String, Object> result = map(response.get("result"));
        assertEquals(true, result.get("isError"));
        assertTrue(((String) map(list(result.get("content")).getFirst()).get("text")).contains("unknown argument(s)"));
        assertEquals(List.of("recordToolCall"), scan.calls().stream().map(FakeScanService.Call::method).toList());
        assertEquals("rejected", scan.toolCalls().getFirst().outcome());
    }

    @Test
    void unknownToolOverTheTransportIsRefusedAndAudited() throws Exception {
        String session = connect();
        Map<String, Object> response = rpc(session, """
                {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"clear_fault_codes",
                 "arguments":{"module":"01"}}}""");
        assertNotNull(response.get("error"), String.valueOf(response));
        assertEquals(List.of("recordToolCall"), scan.calls().stream().map(FakeScanService.Call::method).toList());
        ToolCallRecord audit = scan.toolCalls().getFirst();
        assertEquals(McpTools.UNKNOWN_TOOL, audit.tool());
        assertEquals("rejected", audit.outcome());
        assertEquals("clear_fault_codes", audit.arguments().get("(tool)"));
        assertEquals("01", audit.arguments().get("module"));
    }

    @Test
    void localhostOriginIsAccepted() throws Exception {
        FakeHttp.Response r = FakeHttp.exchange(endpoint.servlet(), "POST",
                headers("Origin", "http://localhost:8080"), initialize());
        assertEquals(200, r.status(), r.body());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://evil.example", "null", "http://localhost.evil.example:8080"})
    void foreignOriginIsRefusedBeforeTheSdkSeesIt(String origin) throws Exception {
        FakeHttp.Response r = FakeHttp.exchange(endpoint.servlet(), "POST", headers("Origin", origin), initialize());
        assertEquals(403, r.status(), r.body());
        assertTrue(r.body().contains("Origin not allowed"), r.body());
        assertEquals(null, r.header(SESSION), "no session was created");
        assertEquals(List.of(), scan.calls());
    }

    @Test
    void foreignOriginIsRefusedOnToolCallsAndGetAndDeleteToo() throws Exception {
        String session = connect();
        String call = "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":{\"name\":\"list_modules\"}}";
        for (String method : List.of("POST", "GET", "DELETE")) {
            FakeHttp.Response r = FakeHttp.exchange(endpoint.servlet(), method,
                    headers(SESSION, session, "Origin", "http://evil.example"), method.equals("POST") ? call : null);
            assertEquals(403, r.status(), method);
        }
        assertEquals(List.of(), scan.calls(), "nothing reached scan, not even the audit");
    }

    @Test
    void repeatedOriginHeaderIsRefused() throws Exception {
        Map<String, List<String>> h = new LinkedHashMap<>();
        headers().forEach((k, v) -> h.put(k, List.of(v)));
        h.put("Origin", List.of("http://localhost:8080", "http://evil.example"));
        FakeHttp.Response r = FakeHttp.exchangeMulti(endpoint.servlet(), "POST", h, initialize());
        assertEquals(403, r.status());
    }

    @Test
    void foreignHostIsRefused() throws Exception {
        FakeHttp.Response r = FakeHttp.exchange(endpoint.servlet(), "POST", headers("Host", "evil.example:8080"),
                initialize());
        assertEquals(421, r.status(), r.body());
        assertEquals(null, r.header(SESSION));
    }

    @Test
    void oversizedBodyIsRefusedUnread() throws Exception {
        String big = "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"x\",\"arguments\":{\"k\":\""
                + "A".repeat(McpServlet.MAX_BODY_BYTES) + "\"}}}";
        FakeHttp.Response r = FakeHttp.exchange(endpoint.servlet(), "POST", headers(), big);
        assertEquals(413, r.status());
        assertEquals(List.of(), scan.calls());
    }

    @Test
    void pathIsMcp() {
        assertEquals("/mcp", endpoint.path());
        assertEquals(McpEndpoint.PATH, endpoint.path());
    }
}
