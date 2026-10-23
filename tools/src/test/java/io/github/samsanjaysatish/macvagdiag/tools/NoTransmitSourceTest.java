package io.github.samsanjaysatish.macvagdiag.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * "There is no code path in this program that calls send": checked on the sources, so a later change
 * that adds one fails the build. Gradle runs tests with the project directory ({@code tools/}) as the
 * working directory.
 */
class NoTransmitSourceTest {

    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");
    /** A call to send (CanAdapter#send), or naming the transmitting mode. */
    private static final Pattern FORBIDDEN = Pattern.compile("\\.send\\s*\\(|\\bsend\\s*\\(|AdapterMode\\.NORMAL|\\bNORMAL\\b");

    @Test
    void toolsMainSourcesNeverSendAndNeverNameNormalMode() throws IOException {
        assertTrue(Files.isDirectory(MAIN_SOURCES), "run from the tools project directory");
        List<Path> files;
        try (Stream<Path> walk = Files.walk(MAIN_SOURCES)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
        assertFalse(files.isEmpty());
        List<String> hits = new ArrayList<>();
        for (Path file : files) {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                if (FORBIDDEN.matcher(lines.get(i)).find()) {
                    hits.add(file + ":" + (i + 1) + ": " + lines.get(i).strip());
                }
            }
        }
        assertEquals(List.of(), hits);
    }
}
