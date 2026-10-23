package io.github.samsanjaysatish.macvagdiag.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.can.FakeTimeSource;
import io.github.samsanjaysatish.macvagdiag.can.Hex;
import io.github.samsanjaysatish.macvagdiag.transport.ApplicationProtocol;
import io.github.samsanjaysatish.macvagdiag.transport.ChannelFactory;
import io.github.samsanjaysatish.macvagdiag.transport.DiagnosticChannel;
import io.github.samsanjaysatish.macvagdiag.transport.ForbiddenRequestException;
import io.github.samsanjaysatish.macvagdiag.transport.ModuleRoute;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The JSON-lines audit log: sent and rejected requests with the caller, tool calls, append-only, escaping. */
class AuditLogTest {

    @Test
    @DisplayName("JSON strings are escaped: quotes, backslash, control characters, U+2028, lone surrogates")
    void escaping() {
        assertEquals("\"a\\\"b\\\\c\"", Json.quote("a\"b\\c"));
        assertEquals("\"\\n\\r\\t\\b\\f\\u0000\\u001b\\u007f\"", Json.quote("\n\r\t\b\f\u0000\u001b\u007f"));
        assertEquals("\"\\u2028\\u2029\"", Json.quote("\u2028\u2029"));
        assertEquals("\"\\ud800x\"", Json.quote("\ud800x"));
        assertEquals("\"\ud83d\ude97\"", Json.quote("\ud83d\ude97"), "a valid surrogate pair stays as it is");
        assertEquals("null", Json.quote(null));
        assertEquals("{\"a\":\"1\",\"n\":2,\"b\":true,\"l\":[\"x\",\"y\"],\"m\":{\"k\":\"v\"}}",
                Json.object().put("a", "1").put("n", 2).put("b", true).put("l", List.of("x", "y"))
                        .put("m", Map.of("k", "v")).toString());
    }

    @Test
    @DisplayName("an MCP tool call is one escaped line with the caller mcp:<tool>")
    void toolCall() {
        try (TestScanServices t = TestScanServices.create()) {
            Map<String, String> args = new LinkedHashMap<>();
            args.put("module", "08\"}\n{\"event\":\"forged\"");
            t.service().recordToolCall(new ToolCallRecord("read_fault_codes", args, "rejected", "unknown module"));
            List<String> lines = t.auditLines("tool-call");
            assertEquals(1, lines.size());
            String line = lines.getFirst();
            assertTrue(line.contains("\"caller\":\"mcp:read_fault_codes\""), line);
            assertTrue(line.contains("\"arguments\":{\"module\":\"08\\\"}\\n{\\\"event\\\":\\\"forged\\\"\"}"), line);
            assertTrue(line.contains("\"outcome\":\"rejected\""), line);
            assertFalse(t.auditLines().stream().anyMatch(l -> l.startsWith("{\"event\":\"forged\"")));
        }
    }

    @Test
    @DisplayName("every line has time and event; results carry the caller, operation, outcome and response")
    void resultLines() throws InterruptedException {
        try (TestScanServices t = TestScanServices.create()) {
            t.connector().kwp("17", () -> Scripts.kwp());
            t.service().readFaultCodes(Caller.cli(), ModuleAddress.INSTRUMENTS);
            for (String line : t.auditLines()) {
                assertTrue(line.startsWith("{\"time\":\"2026-01-01T00:00"), line);
                assertTrue(line.contains("\"event\":\""), line);
            }
            List<String> results = t.auditLines("result");
            assertEquals(2, results.size());
            assertTrue(results.get(0).contains("\"caller\":\"cli\"") && results.get(0).contains("\"module\":\"17\"")
                    && results.get(0).contains("\"operation\":\"startSession\"")
                    && results.get(0).contains("\"outcome\":\"success\"")
                    && results.get(0).contains("\"responses\":[\"50 89\"]"), results.get(0));
            assertEquals(1, t.auditLines("connect").size());
            assertEquals(1, t.auditLines("close").size());
        }
    }

    @Test
    @DisplayName("append-only: a second service on the same file keeps the earlier lines")
    void appendOnly(@TempDir Path dir) throws IOException, InterruptedException {
        Path file = dir.resolve("nested").resolve("audit.jsonl");
        try (TestScanServices t = TestScanServices.create(FakeTimeSource.autoAdvancing(), ScanLimits.DEFAULTS, file)) {
            t.connector().kwp("17", () -> Scripts.kwp());
            t.service().readFaultCodes(Caller.web(), ModuleAddress.INSTRUMENTS);
        }
        List<String> first = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertTrue(first.size() > 4);
        try (TestScanServices t = TestScanServices.create(FakeTimeSource.autoAdvancing(), ScanLimits.DEFAULTS, file)) {
            t.service().recordToolCall(new ToolCallRecord("get_session_status", Map.of(), "ok", ""));
        }
        List<String> second = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(first, second.subList(0, first.size()));
        assertTrue(second.size() > first.size());
    }

    @Test
    @DisplayName("default path: ~/Library/Application Support/macvagdiag/audit.jsonl on macOS, XDG elsewhere")
    void defaultPath() {
        assertEquals(Path.of("/Users/x/Library/Application Support/macvagdiag/audit.jsonl"),
                ScanOptions.defaultAuditLogPath("Mac OS X", "/Users/x", null));
        assertEquals(Path.of("/home/x/.local/share/macvagdiag/audit.jsonl"),
                ScanOptions.defaultAuditLogPath("Linux", "/home/x", null));
        assertEquals(Path.of("/data/macvagdiag/audit.jsonl"), ScanOptions.defaultAuditLogPath("Linux", "/home/x", "/data"));
        assertTrue(ScanOptions.defaults().auditLog().endsWith(Path.of("macvagdiag", "audit.jsonl")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"14 FF FF FF", "11 01", "2E F1 90 00", "31 01 02 03", "27 01", "10 02", "28 00 01", "85 02"})
    @DisplayName("a request the guard rejects is audited, and nothing reaches the wire")
    void rejectedRequestsAreAudited(String forbidden) throws InterruptedException {
        StringWriter log = new StringWriter();
        FakeTimeSource time = FakeTimeSource.autoAdvancing();
        ScanServiceFactory.DemoStack stack = ScanServiceFactory.demoStack(time, ScanLimits.DEFAULTS, AuditLog.to(log, time, true));
        try (ScanService service = stack.service()) {
            service.sessionStatus(Caller.web()); // opens the adapter
            ChannelFactory rogue = ChannelFactory.create(stack.adapter(), time, stack.connector().auditListener());
            try (DiagnosticChannel ch = rogue.open(new ModuleRoute.IsoTp(0x7E0, 0x7E8, ApplicationProtocol.UDS))) {
                assertThrows(ForbiddenRequestException.class, () -> ch.request(Hex.parse(forbidden), Duration.ofSeconds(1)));
            }
            assertEquals(List.of(), stack.adapter().sentFrames(), "zero frames");
            String rejected = log.toString().lines().filter(l -> l.contains("\"event\":\"rejected\"")).findFirst().orElseThrow();
            assertTrue(rejected.contains("\"request\":\"" + forbidden + "\""), rejected);
            assertTrue(rejected.contains("\"reason\":"), rejected);
            assertTrue(rejected.contains("\"demo\":true"), rejected);
        }
    }

    @Test
    @DisplayName("an allowed request outside any scan operation is stopped by the audit hook: zero frames")
    void unreservedAllowedRequestIsStopped() throws InterruptedException {
        StringWriter log = new StringWriter();
        FakeTimeSource time = FakeTimeSource.autoAdvancing();
        ScanServiceFactory.DemoStack stack = ScanServiceFactory.demoStack(time, ScanLimits.DEFAULTS, AuditLog.to(log, time, true));
        try (ScanService service = stack.service()) {
            service.sessionStatus(Caller.web());
            ChannelFactory rogue = ChannelFactory.create(stack.adapter(), time, stack.connector().auditListener());
            try (DiagnosticChannel ch = rogue.open(new ModuleRoute.IsoTp(0x7E0, 0x7E8, ApplicationProtocol.UDS))) {
                assertThrows(ScanEngine.UnreservedRequestException.class,
                        () -> ch.request(Hex.parse("19 02 FF"), Duration.ofSeconds(1)));
            }
            assertEquals(List.of(), stack.adapter().sentFrames());
            assertTrue(log.toString().contains("\"reason\":\"unreserved\""));
        }
    }

    @Test
    @DisplayName("inside an operation, a request beyond its reservation is stopped: 19 02 FF once, not twice")
    void requestBeyondReservationIsStopped() throws InterruptedException {
        StringWriter log = new StringWriter();
        FakeTimeSource time = FakeTimeSource.autoAdvancing();
        ScanServiceFactory.DemoStack stack = ScanServiceFactory.demoStack(time, ScanLimits.DEFAULTS, AuditLog.to(log, time, true));
        try (DefaultScanService service = stack.service()) {
            service.sessionStatus(Caller.web());
            ChannelFactory factory = ChannelFactory.create(stack.adapter(), time, stack.connector().auditListener());
            ScanContext ctx = new ScanContext("t-2", Caller.mcp("read_fault_codes"), "test", () -> false);
            try (DiagnosticChannel ch = factory.open(new ModuleRoute.IsoTp(0x7E0, 0x7E8, ApplicationProtocol.UDS))) {
                assertThrows(ScanEngine.UnreservedRequestException.class, () -> service.engine().runReserved(ctx,
                        ModuleAddress.ENGINE, 1, () -> {
                            ch.request(Hex.parse("19 02 FF"), Duration.ofSeconds(1));
                            return ch.request(Hex.parse("19 02 FF"), Duration.ofSeconds(1));
                        }));
            }
            long singleFrames = stack.adapter().sentFrames().stream()
                    .filter(f -> f.id() == 0x7E0 && f.dataByte(0) == 0x03 && f.dataByte(1) == 0x19).count();
            assertEquals(1, singleFrames, "only the reserved request reached the wire");
            assertEquals(1, log.toString().lines().filter(l -> l.contains("\"event\":\"request\"")
                    && l.contains("\"caller\":\"mcp:read_fault_codes\"")).count());
        }
    }

    /** A writer that can be made to fail. */
    private static final class FlakyWriter extends Writer {
        final StringWriter inner = new StringWriter();
        volatile boolean fail;

        @Override
        public void write(char[] cbuf, int off, int len) throws IOException {
            if (fail) {
                throw new IOException("disk full");
            }
            inner.write(cbuf, off, len);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    @Test
    @DisplayName("if a request's audit line can't be written, the request is not sent")
    void auditFailureBlocksTheRequest() throws InterruptedException {
        FakeTimeSource time = FakeTimeSource.autoAdvancing();
        FlakyWriter writer = new FlakyWriter();
        ScanServiceFactory.DemoStack stack = ScanServiceFactory.demoStack(time, ScanLimits.DEFAULTS, AuditLog.to(writer, time, true));
        try (DefaultScanService service = stack.service()) {
            service.sessionStatus(Caller.web());
            ChannelFactory factory = ChannelFactory.create(stack.adapter(), time, stack.connector().auditListener());
            ScanContext ctx = new ScanContext("t-1", Caller.web(), "test", () -> false);
            try (DiagnosticChannel ch = factory.open(new ModuleRoute.IsoTp(0x7E0, 0x7E8, ApplicationProtocol.UDS))) {
                writer.fail = true;
                assertThrows(UncheckedIOException.class, () -> service.engine().runReserved(ctx, ModuleAddress.ENGINE, 1,
                        () -> ch.request(Hex.parse("19 02 FF"), Duration.ofSeconds(1))));
            }
            assertEquals(List.of(), stack.adapter().sentFrames(), "nothing sent without its audit line");
            writer.fail = false;
        }
    }

    @Test
    @DisplayName("ToolCallRecord needs a valid tool name; recordToolCall after close is refused")
    void toolCallAfterClose() {
        TestScanServices t = TestScanServices.create();
        t.close();
        assertThrows(ScanRefusedException.class,
                () -> t.service().recordToolCall(new ToolCallRecord("list_modules", Map.of(), "ok", "")));
    }
}
