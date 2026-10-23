package io.github.samsanjaysatish.macvagdiag.diag;

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
 * Root rules 1 and 5 from {@code diag}'s side. {@code can-core} is visible here transitively, so the public
 * {@code CanAdapter.send} is reachable at compile time; this scan of {@code diag/src/main/java} makes sure it is
 * never used. {@code diag} may reference only {@code TimeSource} and {@code Hex} from {@code can}, and only the
 * public channel API from {@code transport}. It must never see a CAN frame or open a channel.
 */
class NoBypassSourceTest {

    private static final Path MAIN = Path.of("src/main/java");

    static final Set<String> ALLOWED_CAN = Set.of("TimeSource", "Hex");
    static final Set<String> ALLOWED_TRANSPORT = Set.of(
            "DiagnosticChannel", "Response", "ModuleRoute", "ApplicationProtocol", "RequestGuard",
            "ForbiddenRequestException");

    /** Any reference into this project's packages: {@code io.github.samsanjaysatish.macvagdiag.<pkg>[.<Type>]}. */
    private static final Pattern PROJECT_REF =
            Pattern.compile("io\\s*\\.\\s*github\\s*\\.\\s*samsanjaysatish\\s*\\.\\s*macvagdiag\\s*(?:\\.\\s*([\\w*]+))?(?:\\s*\\.\\s*([\\w*]+))?");

    /** Ways to reach a type without naming it in an import, and channel calls outside {@link Exchange}. */
    private static final List<Pattern> FORBIDDEN_ANYWHERE = List.of(
            Pattern.compile("Class\\s*\\.\\s*forName"),
            Pattern.compile("\\.\\s*loadClass\\s*\\("),
            Pattern.compile("setAccessible"),
            Pattern.compile("MethodHandles"),
            Pattern.compile("\\.\\s*keepAlive\\s*\\("), // transport keep-alive is scan's job
            Pattern.compile("\\bCanFrame\\b"),
            Pattern.compile("\\bCanAdapter\\b"),
            Pattern.compile("\\bChannelFactory\\b"));

    private static final List<Pattern> CHANNEL_CALLS = List.of(
            Pattern.compile("\\.\\s*request\\s*\\("),
            Pattern.compile("\\.\\s*awaitResponse\\s*\\("));

    /** Violations in one source file. */
    static List<String> violations(String fileName, String source) {
        List<String> out = new ArrayList<>();
        Matcher m = PROJECT_REF.matcher(source);
        while (m.find()) {
            String pkg = m.group(1);
            String type = m.group(2);
            String where = fileName + ": " + m.group().replaceAll("\\s+", "");
            if ("diag".equals(pkg)) {
                continue;
            }
            if ("can".equals(pkg)) {
                if (type == null || !ALLOWED_CAN.contains(type)) {
                    out.add(where + " (only can.TimeSource and can.Hex are allowed)");
                }
            } else if ("transport".equals(pkg)) {
                if (type == null || !ALLOWED_TRANSPORT.contains(type)) {
                    out.add(where + " (only the public channel API of transport is allowed)");
                }
            } else {
                out.add(where + " (diag may only reference can and transport)");
            }
        }
        for (Pattern p : FORBIDDEN_ANYWHERE) {
            if (p.matcher(source).find()) {
                out.add(fileName + ": uses " + p.pattern());
            }
        }
        if (!fileName.equals("Exchange.java")) {
            for (Pattern p : CHANNEL_CALLS) {
                if (p.matcher(source).find()) {
                    out.add(fileName + ": calls the channel (" + p.pattern() + "); only Exchange may");
                }
            }
        }
        return out;
    }

    @Test
    @DisplayName("diag main sources reference only can.TimeSource/Hex and the public channel API; only Exchange calls the channel")
    void mainSourcesStayInBounds() throws IOException {
        assertTrue(Files.isDirectory(MAIN), "run from the diag project directory: " + MAIN.toAbsolutePath());
        List<Path> files;
        try (Stream<Path> s = Files.walk(MAIN)) {
            files = s.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        assertTrue(files.size() >= 15, "found only " + files.size() + " source files");
        List<String> all = new ArrayList<>();
        boolean exchangeCallsChannel = false;
        for (Path f : files) {
            String src = Files.readString(f, StandardCharsets.UTF_8);
            all.addAll(violations(f.getFileName().toString(), src));
            if (f.getFileName().toString().equals("Exchange.java")) {
                exchangeCallsChannel = src.contains("channel.request(") && src.contains("channel.awaitResponse(");
            }
        }
        assertEquals(List.of(), all);
        assertTrue(exchangeCallsChannel, "the scan must actually see Exchange's channel calls");
    }

    @Test
    @DisplayName("the scanner itself catches forbidden imports, FQNs, wildcards, reflection and stray channel calls")
    void scannerCatchesViolations() {
        List<String> bad = List.of(
                "import io.github.samsanjaysatish.macvagdiag.can.CanAdapter;",
                "import io.github.samsanjaysatish.macvagdiag.can.CanFrame;",
                "import io.github.samsanjaysatish.macvagdiag.can.*;",
                "import static io.github.samsanjaysatish.macvagdiag.can.SimulatedAdapter.foo;",
                "Object x = new io.github.samsanjaysatish.macvagdiag.can.SlcanAdapter(null);",
                "import io.github.samsanjaysatish.macvagdiag.transport.ChannelFactory;",
                "import io.github.samsanjaysatish.macvagdiag.transport.GuardedChannel;",
                "import io.github.samsanjaysatish.macvagdiag.transport.*;",
                "import io.github.samsanjaysatish.macvagdiag.scan.ScanService;",
                "import io.github.samsanjaysatish.macvagdiag.*;",
                "io.github.samsanjaysatish . macvagdiag . can . CanAdapter a;",
                "Class.forName(\"x\");",
                "f.setAccessible(true);",
                "channel.keepAlive();",
                "void f(CanFrame frame) {}",
                "channel.request(bytes, t);",
                "channel.awaitResponse(t);");
        for (String line : bad) {
            assertFalse(violations("Other.java", line).isEmpty(), "should be caught: " + line);
        }
        List<String> good = List.of(
                "package io.github.samsanjaysatish.macvagdiag.diag;",
                "import io.github.samsanjaysatish.macvagdiag.can.TimeSource;",
                "import io.github.samsanjaysatish.macvagdiag.can.Hex;",
                "import io.github.samsanjaysatish.macvagdiag.transport.DiagnosticChannel;",
                "import io.github.samsanjaysatish.macvagdiag.transport.Response;",
                "import io.github.samsanjaysatish.macvagdiag.transport.RequestGuard;",
                "import io.github.samsanjaysatish.macvagdiag.diag.DiagResult;");
        for (String line : good) {
            assertEquals(List.of(), violations("Other.java", line), line);
        }
        assertEquals(List.of(), violations("Exchange.java", "channel.request(bytes, t); channel.awaitResponse(t);"));
    }
}
