package io.github.samsanjaysatish.macvagdiag.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Root rules 1 and 5 from scan's side. {@code CanAdapter.send} is public and visible here, so this scan of
 * {@code scan/src/main/java} makes sure only the wiring classes touch can-core adapters and the transport's
 * {@code ChannelFactory}, and that nothing in scan sends frames or requests itself:
 * <ul>
 *   <li>Outside {@link #WIRING}: from can-core only {@code TimeSource}; from transport only the types diag's API
 *       exposes ({@code ApplicationProtocol}, {@code Response}).</li>
 *   <li>Everywhere: no {@code .send(}, {@code .request(}, {@code .awaitResponse(}, no wildcard imports of our
 *       packages, no reflection, no {@code server} / {@code mcp} references.</li>
 *   <li>{@code .keepAlive(} only in the wiring (scan calls it through {@code keepChannelAlive}).</li>
 * </ul>
 */
class NoBypassSourceTest {

    private static final Path MAIN = Path.of("src/main/java");

    /** The wiring set: the only main classes allowed to reference can-core adapters and the channel factory. */
    static final Set<String> WIRING = Set.of("ScanServiceFactory.java", "ChannelConnector.java", "DemoCar.java");

    static final Set<String> ALLOWED_CAN = Set.of("TimeSource");
    static final Set<String> ALLOWED_TRANSPORT = Set.of("ApplicationProtocol", "Response");

    private static final Pattern PROJECT_REF = Pattern.compile(
            "io\\s*\\.\\s*github\\s*\\.\\s*samsanjaysatish\\s*\\.\\s*macvagdiag\\s*(?:\\.\\s*([\\w*]+))?(?:\\s*\\.\\s*([\\w*]+))?");

    private static final List<Pattern> FORBIDDEN_EVERYWHERE = List.of(
            Pattern.compile("\\.\\s*send\\s*\\("),
            Pattern.compile("\\.\\s*request\\s*\\("),
            Pattern.compile("\\.\\s*awaitResponse\\s*\\("),
            Pattern.compile("Class\\s*\\.\\s*forName"),
            Pattern.compile("\\.\\s*loadClass\\s*\\("),
            Pattern.compile("setAccessible"),
            Pattern.compile("MethodHandles"));

    /** Simple names that only the wiring may use (catches same-package or static-import tricks). */
    private static final List<Pattern> WIRING_ONLY = List.of(
            Pattern.compile("\\bCanAdapter\\b"), Pattern.compile("\\bCanFrame\\b"),
            Pattern.compile("\\bSimulatedAdapter\\b"), Pattern.compile("\\bReplayAdapter\\b"),
            Pattern.compile("\\bSlcanAdapter\\b"), Pattern.compile("\\bChannelFactory\\b"),
            Pattern.compile("\\bDiagnosticChannel\\b"), Pattern.compile("\\bRequestAuditListener\\b"),
            Pattern.compile("\\bModuleRoute\\b"), Pattern.compile("\\.\\s*keepAlive\\s*\\("));

    static List<String> violations(String fileName, String source) {
        List<String> out = new ArrayList<>();
        boolean wiring = WIRING.contains(fileName);
        Matcher m = PROJECT_REF.matcher(source);
        while (m.find()) {
            String pkg = m.group(1);
            String type = m.group(2);
            String where = fileName + ": " + m.group().replaceAll("\\s+", "");
            if (pkg == null || "*".equals(pkg) || "*".equals(type)) {
                out.add(where + " (no wildcard or bare references to our packages)");
            } else if ("scan".equals(pkg) || "diag".equals(pkg)) {
                continue;
            } else if ("can".equals(pkg)) {
                if (!wiring && (type == null || !ALLOWED_CAN.contains(type))) {
                    out.add(where + " (outside the wiring only can.TimeSource is allowed)");
                }
            } else if ("transport".equals(pkg)) {
                if (!wiring && (type == null || !ALLOWED_TRANSPORT.contains(type))) {
                    out.add(where + " (outside the wiring only transport.ApplicationProtocol/Response are allowed)");
                }
            } else {
                out.add(where + " (scan may only reference can, transport, diag and itself)");
            }
        }
        for (Pattern p : FORBIDDEN_EVERYWHERE) {
            if (p.matcher(source).find()) {
                out.add(fileName + ": uses " + p.pattern());
            }
        }
        if (!wiring) {
            for (Pattern p : WIRING_ONLY) {
                if (p.matcher(source).find()) {
                    out.add(fileName + ": uses " + p.pattern() + " (wiring classes only)");
                }
            }
        }
        return out;
    }

    @Test
    @DisplayName("scan main: can-core adapters and ChannelFactory only in the wiring classes; nothing calls .send(")
    void mainSourcesStayInBounds() throws IOException {
        assertTrue(Files.isDirectory(MAIN), "run from the scan project directory: " + MAIN.toAbsolutePath());
        List<Path> files;
        try (Stream<Path> s = Files.walk(MAIN)) {
            files = s.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        assertTrue(files.size() >= 30, "found only " + files.size() + " source files");
        List<String> all = new ArrayList<>();
        for (Path f : files) {
            all.addAll(violations(f.getFileName().toString(), Files.readString(f, StandardCharsets.UTF_8)));
        }
        assertEquals(List.of(), all);
        // The wiring really is where the stack is assembled.
        String connector = Files.readString(MAIN.resolve("io/github/samsanjaysatish/macvagdiag/scan/ChannelConnector.java"));
        assertTrue(connector.contains("ChannelFactory.create(adapter, time, forwarder)"));
        assertTrue(connector.contains("channel.keepAlive()"));
        for (String wiring : WIRING) {
            assertTrue(files.stream().anyMatch(p -> p.getFileName().toString().equals(wiring)), wiring);
        }
    }

    @Test
    @DisplayName("the scanner catches forbidden references, wildcards, sends and reflection")
    void scannerCatchesViolations() {
        List<String> bad = List.of(
                "import io.github.samsanjaysatish.macvagdiag.can.CanAdapter;",
                "import io.github.samsanjaysatish.macvagdiag.can.*;",
                "import io.github.samsanjaysatish.macvagdiag.can.Hex;",
                "import static io.github.samsanjaysatish.macvagdiag.can.SimulatedAdapter.foo;",
                "Object x = new io.github.samsanjaysatish.macvagdiag.can.SlcanAdapter(null);",
                "io.github.samsanjaysatish . macvagdiag . can . CanAdapter a;",
                "import io.github.samsanjaysatish.macvagdiag.transport.ChannelFactory;",
                "import io.github.samsanjaysatish.macvagdiag.transport.DiagnosticChannel;",
                "import io.github.samsanjaysatish.macvagdiag.transport.*;",
                "import io.github.samsanjaysatish.macvagdiag.server.Foo;",
                "import io.github.samsanjaysatish.macvagdiag.*;",
                "void f(CanAdapter a) {}",
                "ChannelFactory f;",
                "adapter.send(frame);",
                "channel.request(bytes, t);",
                "channel.awaitResponse(t);",
                "channel.keepAlive();",
                "Class.forName(\"x\");",
                "f.setAccessible(true);");
        for (String line : bad) {
            assertFalse(violations("Other.java", line).isEmpty(), "should be caught: " + line);
        }
        List<String> good = List.of(
                "import io.github.samsanjaysatish.macvagdiag.can.TimeSource;",
                "import io.github.samsanjaysatish.macvagdiag.diag.DiagnosticService;",
                "import io.github.samsanjaysatish.macvagdiag.transport.ApplicationProtocol;",
                "import io.github.samsanjaysatish.macvagdiag.transport.Response;",
                "connection.keepChannelAlive();");
        for (String line : good) {
            assertEquals(List.of(), violations("Other.java", line), line);
        }
        assertEquals(List.of(), violations("ChannelConnector.java",
                "import io.github.samsanjaysatish.macvagdiag.transport.ChannelFactory; channel.keepAlive();"));
        assertFalse(violations("ChannelConnector.java", "adapter.send(frame);").isEmpty(), "not even the wiring sends");
    }
}
