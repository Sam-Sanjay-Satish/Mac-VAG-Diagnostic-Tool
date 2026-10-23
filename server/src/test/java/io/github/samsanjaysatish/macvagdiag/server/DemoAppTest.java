package io.github.samsanjaysatish.macvagdiag.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.can.FakeTimeSource;
import io.github.samsanjaysatish.macvagdiag.can.TimeSource;
import io.github.samsanjaysatish.macvagdiag.scan.ScanService;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.AbstractProtocol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The whole app in demo mode on a real Tomcat at a random port on 127.0.0.1: the real scan / diag / transport stack
 * on the simulated demo car, on an auto-advancing fake clock so the scan is instant. Everything goes through HTTP,
 * like the browser.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "macvagdiag.demo=true")
@Import(DemoAppTest.FastClock.class)
@ExtendWith(OutputCaptureExtension.class)
class DemoAppTest {

    /** The demo car's made-up VIN (scan's {@code DemoCar.VIN}). */
    static final String DEMO_VIN = "DEMOVIN0000000000";

    static final Path AUDIT = TestAuditLogs.newFile("demo");

    @DynamicPropertySource
    static void auditLog(DynamicPropertyRegistry registry) {
        registry.add("macvagdiag.audit-log", AUDIT::toString);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FastClock {
        @Bean
        TimeSource timeSource() {
            return FakeTimeSource.autoAdvancing();
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    WebServerApplicationContext context;

    @Autowired
    ScanService scan;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json = new ObjectMapper();

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.timeout(Duration.ofSeconds(8)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
    }

    private JsonNode getJson(String path) throws Exception {
        HttpResponse<String> r = send(request(path).GET());
        assertEquals(200, r.statusCode(), r.body());
        return json.readTree(r.body());
    }

    @Test
    @DisplayName("Auto-Scan through the API: the demo modules, codes, statuses, VIN, DEMO flag and report")
    void demoAutoScan(CapturedOutput output) throws Exception {
        JsonNode status = getJson("/api/status");
        assertEquals("CONNECTED", status.get("adapter").asString());
        assertTrue(status.get("demo").asBoolean());

        HttpResponse<String> started = send(request("/api/scans").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")));
        assertEquals(202, started.statusCode(), started.body());
        String id = json.readTree(started.body()).get("id").asString();

        // Wait for the scan on its own completion signal (bounded, real time), never by sleeping.
        scan.findAutoScan(id).orElseThrow().await(Duration.ofSeconds(8));

        JsonNode job = getJson("/api/scans/" + id);
        assertEquals("FINISHED", job.get("state").asString());
        assertTrue(job.get("demo").asBoolean());
        Map<String, String> statuses = new LinkedHashMap<>();
        Map<String, String> codes = new LinkedHashMap<>();
        for (JsonNode m : job.get("modules")) {
            statuses.put(m.get("address").asString(), m.get("status").asString());
            StringBuilder c = new StringBuilder();
            for (JsonNode f : m.get("faults")) {
                c.append(c.isEmpty() ? "" : ",").append(f.get("displayCode").asString());
            }
            codes.put(m.get("address").asString(), c.toString());
        }
        Map<String, String> expected = new LinkedHashMap<>();
        for (String ok : List.of("02", "08", "09", "15", "17")) {
            expected.put(ok, "OK");
        }
        expected.put("01", "FAULTS");
        expected.put("03", "FAULTS");
        expected.put("19", "FAULTS");
        for (String off : List.of("16", "25", "44", "46", "56")) {
            expected.put(off, "NOT_RESPONDING");
        }
        assertEquals(new java.util.TreeMap<>(expected), new java.util.TreeMap<>(statuses));
        assertEquals("P0401-00,P2463-00", codes.get("01"));
        assertEquals("01314", codes.get("03"));
        assertEquals("01044", codes.get("19"));

        JsonNode engine = job.get("modules").get(0);
        assertEquals("ISOTP", engine.get("link").get("type").asString());
        assertEquals("READ", engine.get("faults").get(0).get("freezeFrame").get("availability").asString());
        assertEquals("19 04 04 01 00 FF",
                engine.get("faults").get(0).get("freezeFrame").get("records").get(0).get("id").asString());

        JsonNode result = job.get("result");
        assertEquals(DEMO_VIN, result.get("vin").asString());
        assertEquals(4, result.get("faultCodes").asInt());
        assertTrue(result.get("demo").asBoolean());

        HttpResponse<String> report = send(request("/api/scans/" + id + "/report.txt?maskVin=true").GET());
        assertEquals(200, report.statusCode());
        assertTrue(report.body().contains("*** DEMO DATA: simulated car, not a real vehicle ***"), report.body());
        assertTrue(report.body().contains("DEMOVIN0000******"), report.body());
        assertFalse(report.body().contains(DEMO_VIN));
        assertTrue(report.headers().firstValue("Content-Disposition").orElse("").contains("-demo-vin-masked.txt"));

        // One module through the API, and the module list.
        JsonNode gateway = getJson("/api/modules/19/faults");
        assertEquals("01044", gateway.get("faults").get(0).get("code").asString());
        assertTrue(gateway.get("faults").get(0).get("statusText").asString().contains("intermittent"));
        JsonNode modules = getJson("/api/modules");
        assertEquals("RESPONDING", modules.get("modules").get(0).get("reachability").asString());

        // The audit log got the web requests, marked demo; the app's own log never has the full VIN.
        List<String> audit = Files.readAllLines(AUDIT, StandardCharsets.UTF_8);
        assertTrue(audit.stream().anyMatch(l -> l.contains("\"event\":\"request\"") && l.contains("\"caller\":\"web\"")),
                String.join("\n", audit));
        assertTrue(audit.stream().allMatch(l -> l.contains("\"demo\":true")));
        assertFalse(output.getAll().contains(DEMO_VIN), "the app log must never contain the full VIN");
    }

    @Test
    @DisplayName("the web page and its files are served, with a DEMO banner and no inline script")
    void webPage() throws Exception {
        HttpResponse<String> page = send(request("/").GET());
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("Run Auto-Scan"));
        assertTrue(page.body().contains("id=\"demo-banner\""));
        assertFalse(page.body().contains("<script>"), "the CSP allows only script files from this server");
        assertEquals("DENY", page.headers().firstValue("X-Frame-Options").orElse(""));
        for (String file : List.of("/app.js", "/app.css")) {
            assertEquals(200, send(request(file).GET()).statusCode(), file);
        }
    }

    @Test
    @DisplayName("the server is bound to a loopback address only")
    void boundToLoopback() {
        TomcatWebServer server = (TomcatWebServer) context.getWebServer();
        Connector[] connectors = server.getTomcat().getService().findConnectors();
        assertEquals(1, connectors.length);
        InetAddress address = ((AbstractProtocol<?>) connectors[0].getProtocolHandler()).getAddress();
        assertTrue(address != null && address.isLoopbackAddress(), "bound to " + address);
        assertEquals("127.0.0.1", address.getHostAddress());
    }

    /**
     * A raw HTTP/1.1 request with a chosen Host header (Java's HttpClient doesn't allow setting Host). Returns the
     * status line and headers.
     */
    private List<String> rawRequest(String method, String path, String host, String... headers) throws Exception {
        try (Socket socket = new Socket(InetAddress.getByName("127.0.0.1"), port)) {
            socket.setSoTimeout(5_000);
            StringBuilder req = new StringBuilder(method + " " + path + " HTTP/1.1\r\nHost: " + host + "\r\n");
            for (String h : headers) {
                req.append(h).append("\r\n");
            }
            req.append("Content-Length: 0\r\nConnection: close\r\n\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.US_ASCII));
            out.flush();
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            List<String> lines = new java.util.ArrayList<>();
            for (String line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine()) {
                lines.add(line);
            }
            return lines;
        }
    }

    @ParameterizedTest(name = "wrong Host on {0} → 403")
    @ValueSource(strings = {"/api/status", "/", "/app.js", "/mcp", "/no/such/path", "/error"})
    @DisplayName("on real Tomcat the Host check covers every path, also outside Spring MVC (e.g. a future /mcp)")
    void wrongHostEverywhere(String path) throws Exception {
        List<String> response = rawRequest("GET", path, "evil.example:" + port);
        assertEquals("HTTP/1.1 403 ", response.getFirst().substring(0, 13), response.toString());
        assertTrue(response.stream().noneMatch(h -> h.toLowerCase(java.util.Locale.ROOT).startsWith("access-control-")));
    }

    @Test
    @DisplayName("on real Tomcat: cross-origin POST and form POST to any path are rejected")
    void originAndContentTypeEverywhere() throws Exception {
        String host = "127.0.0.1:" + port;
        assertTrue(rawRequest("POST", "/mcp", host, "Origin: https://evil.example", "Content-Type: application/json")
                .getFirst().startsWith("HTTP/1.1 403"));
        assertTrue(rawRequest("POST", "/mcp", host, "Content-Type: application/x-www-form-urlencoded")
                .getFirst().startsWith("HTTP/1.1 415"));
        assertTrue(rawRequest("POST", "/api/scans", host, "Content-Type: text/plain")
                .getFirst().startsWith("HTTP/1.1 415"));
        // An allowed request to an unmapped path passes the filter and is a plain 404.
        assertTrue(rawRequest("GET", "/no/such/path", host).getFirst().startsWith("HTTP/1.1 404"));
    }

    /** One JSON-RPC message to {@code /mcp}; returns the response (plain JSON or the last SSE {@code data:} line). */
    private McpReply mcp(String sessionId, String body) throws Exception {
        HttpRequest.Builder r = request("/mcp").header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (sessionId != null) {
            r.header("Mcp-Session-Id", sessionId);
        }
        HttpResponse<String> response = send(r);
        String payload = response.body().strip();
        if (payload.contains("data:")) {
            payload = payload.lines().filter(l -> l.startsWith("data:")).reduce((a, b) -> b).orElseThrow()
                    .substring("data:".length()).strip();
        }
        return new McpReply(response, payload.isEmpty() ? null : json.readTree(payload));
    }

    private record McpReply(HttpResponse<String> response, JsonNode message) {
    }

    @Test
    @DisplayName("MCP at /mcp on real Tomcat: initialize, tools/list, read_fault_codes 03 on the demo car, audited")
    void mcpOverHttp() throws Exception {
        McpReply init = mcp(null, """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
                 "capabilities":{},"clientInfo":{"name":"DemoAppTest","version":"1"}}}""");
        assertEquals(200, init.response().statusCode(), init.response().body());
        String session = init.response().headers().firstValue("Mcp-Session-Id").orElseThrow();
        assertEquals("macvagdiag", init.message().get("result").get("serverInfo").get("name").asString(),
                init.message().toString());
        mcp(session, """
                {"jsonrpc":"2.0","method":"notifications/initialized"}""");

        McpReply list = mcp(session, """
                {"jsonrpc":"2.0","id":2,"method":"tools/list"}""");
        java.util.Set<String> tools = new java.util.TreeSet<>();
        list.message().get("result").get("tools").forEach(t -> tools.add(t.get("name").asString()));
        assertEquals(java.util.Set.of("get_session_status", "get_vehicle_info", "list_modules", "read_fault_codes"),
                tools);

        McpReply call = mcp(session, """
                {"jsonrpc":"2.0","id":3,"method":"tools/call",
                 "params":{"name":"read_fault_codes","arguments":{"module":"03"}}}""");
        JsonNode result = call.message().get("result");
        assertFalse(result.path("isError").asBoolean(false), result.toString());
        assertTrue(result.get("structuredContent").toString().contains("01314"), result.toString());

        List<String> audit = Files.readAllLines(AUDIT, StandardCharsets.UTF_8);
        assertTrue(audit.stream().anyMatch(l -> l.contains("read_fault_codes") && l.contains("\"caller\":\"mcp:")),
                String.join("\n", audit));
    }
}
