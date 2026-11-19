package io.github.samsanjaysatish.macvagdiag.labels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Lookup order and redirects, against made-up label files in {@code src/test/resources/labels} (the real set is
 * kept out of git, root CLAUDE.md "Label files"). The shapes copy the real ones: the target car's engine is found
 * through {@code 03-01.lbl}'s pattern redirect, its DSG by hardware part number, its gateway by an {@code HW}
 * pattern.
 */
class LabelLibraryTest {

    static Path folder(String name) {
        try {
            return Path.of(LabelLibraryTest.class.getResource("/labels/" + name).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    static LabelLibrary main() throws Exception {
        return LabelLibrary.of(List.of(folder("main")));
    }

    private static String title(LabelLookup l) {
        return l.labels().orElseThrow().group(1).orElseThrow().title();
    }

    @Test
    @DisplayName("engine 03L 997 030 M at 01: AA-BB file, pattern redirect (bare and SL lines skipped), comment ignored")
    void enginePatternRedirect() throws Exception {
        LabelLookup l = main().lookup(0x01, "03L997030M  ", Optional.of("03L907309AA"));
        assertEquals(Optional.of("03L-111-111-AAA.lbl"), l.source());
        assertEquals(List.of("03-01.lbl", "03L-111-111-AAA.lbl"), l.trail());
        assertEquals("Basic Values", title(l));
    }

    @Test
    @DisplayName("? wildcards: another 03L 997 0xx part falls to the second pattern")
    void wildcard() throws Exception {
        assertEquals(Optional.of("03L-222-222.lbl"), main().lookup(0x01, "03L 997 012 B", Optional.empty()).source());
    }

    @Test
    @DisplayName("no redirect matches: the AA-BB file itself is used")
    void aaBbItself() throws Exception {
        LabelLookup l = main().lookup(0x01, "03L 906 999", Optional.empty());
        assertEquals(Optional.of("03-01.lbl"), l.source());
        assertEquals("Generic Title From AA-BB", title(l));
    }

    @Test
    @DisplayName("search order in one folder: full part number, then root, then AA-BB")
    void fileOrder() throws Exception {
        LabelLibrary lib = main();
        assertEquals(Optional.of("1K0-907-379-BL.lbl"), lib.lookup(0x03, "1K0907379BL", Optional.empty()).source());
        assertEquals(Optional.of("1K0-907-379.lbl"), lib.lookup(0x03, "1K0907379AC", Optional.empty()).source());
        assertEquals(Optional.of("1K-03.lbl"), lib.lookup(0x03, "1K0907999", Optional.empty()).source());
    }

    @Test
    @DisplayName("folders in order: a user folder's TEST-NN and root file win over the main folder")
    void userFolderFirst() throws Exception {
        LabelLibrary lib = LabelLibrary.of(List.of(folder("user"), folder("main")));
        assertEquals(Optional.of("TEST-03.lbl"), lib.lookup(0x03, "1K0907379BL", Optional.empty()).source());
        assertEquals(Optional.of("1K-03.lbl"), LabelLibrary.of(List.of(folder("main"))).lookup(0x03, "1K0907999",
                Optional.empty()).source());
        LabelLibrary userRoot = LabelLibrary.of(List.of(folder("user"), folder("main")));
        assertEquals(Optional.of("1K0-907-379.lbl"), userRoot.lookup(0x08, "1K0907379BL", Optional.empty()).source());
        assertEquals("User Root File", title(userRoot.lookup(0x08, "1K0907379BL", Optional.empty())));
    }

    @Test
    @DisplayName("suffix table: AF and AQ go to the AF file (a .clb name finds the decoded copy), others stay")
    void suffixTable() throws Exception {
        LabelLibrary lib = main();
        assertEquals("Suffix AF Or AQ", title(lib.lookup(0x01, "038906019AF", Optional.empty())));
        assertEquals("Suffix AF Or AQ", title(lib.lookup(0x01, "038 906 019 AQ", Optional.empty())));
        assertEquals("Generic Root File", title(lib.lookup(0x01, "038906019BB", Optional.empty())));
        LabelLookup missing = lib.lookup(0x01, "038906019XX", Optional.empty());
        assertEquals("Generic Root File", title(missing));
        assertTrue(missing.trail().contains("redirect target 038-906-019-XX.CLB not found; using 038-906-019.lbl"),
                missing.trail().toString());
    }

    @Test
    @DisplayName("redirect loop ends after the hop limit, with a note")
    void loop() throws Exception {
        LabelLookup l = main().lookup(0x01, "038906019LP", Optional.empty());
        assertTrue(l.labels().isPresent());
        assertTrue(l.trail().getLast().startsWith("more than " + LabelLibrary.MAX_REDIRECTS + " redirects"), l.trail().toString());
    }

    @Test
    @DisplayName("HW pattern redirect matches the hardware part number only")
    void hardwarePattern() throws Exception {
        LabelLibrary lib = main();
        assertEquals(Optional.of("6N0-555-555.lbl"),
                lib.lookup(0x19, "5C0 999 999", Optional.of("5C0 937 087 A")).source());
        assertEquals(Optional.of("5C-19.lbl"), lib.lookup(0x19, "5C0 999 999", Optional.empty()).source());
        assertEquals(Optional.of("5C-19.lbl"), lib.lookup(0x19, "5C0 937 087 A", Optional.of("5C0 111 111")).source());
    }

    @Test
    @DisplayName("nothing for the software part number: the hardware part number is searched (the target car's DSG)")
    void hardwareFallback() throws Exception {
        LabelLookup l = main().lookup(0x02, "02E300062B", Optional.of("02E927770AL"));
        assertEquals(Optional.of("02E-927-770.lbl"), l.source());
        assertEquals("by hardware part number 02E-927-770-AL", l.trail().get(1));
    }

    @Test
    @DisplayName("no file, no part number, no folder: a normal 'none' with the reason")
    void none() throws Exception {
        LabelLookup l = main().lookup(0x56, "7P6035730K", Optional.empty());
        assertFalse(l.labels().isPresent());
        assertEquals(Optional.empty(), l.source());
        assertEquals(List.of("no label file for 7P6-035-730-K at 56"), l.trail());
        assertEquals(List.of("no usable part number"), main().lookup(0x01, "  ", Optional.empty()).trail());
        assertFalse(LabelLibrary.empty().lookup(0x01, "03L997030M", Optional.empty()).labels().isPresent());
        assertFalse(LabelLibrary.of(List.of(Path.of("does-not-exist"))).lookup(0x01, "03L997030M", Optional.empty())
                .labels().isPresent());
        assertThrows(IllegalArgumentException.class, () -> main().lookup(0, "03L997030M", Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> main().lookup(0x100, "03L997030M", Optional.empty()));
    }

    @Test
    @DisplayName("files given as text (bundled demo labels): same lookup, names without .lbl/.clb ignored")
    void ofTexts() {
        LabelLibrary lib = LabelLibrary.ofTexts(java.util.Map.of(
                "03L-906-018.lbl", "001,0,Demo\r\n001,1,Speed,(demo),Range: 0...5000 /min",
                "readme.txt", "001,0,Not A Label File"));
        LabelLookup l = lib.lookup(0x01, "03L906018XX", Optional.empty());
        assertEquals(Optional.of("03L-906-018.lbl"), l.source());
        assertEquals("Speed (demo)", l.labels().orElseThrow().group(1).orElseThrow().field(1).orElseThrow().displayName());
        assertFalse(lib.lookup(0x01, "03L906019", Optional.empty()).labels().isPresent());
    }

    @Test
    void partNumbers() {
        PartNumber p = PartNumber.parse("03L997030M  ").orElseThrow();
        assertEquals("03L-997-030-M", p.dashed());
        assertEquals("03L-997-030", p.dashedRoot());
        assertEquals("03", p.prefix());
        assertEquals("03L 997 030 M", p.toString());
        assertEquals(p, PartNumber.parse("03l-997-030-m").orElseThrow());
        assertEquals("", PartNumber.parse("6N0 909 901").orElseThrow().suffix());
        assertEquals(Optional.empty(), PartNumber.parse("03L99703"));
        assertEquals(Optional.empty(), PartNumber.parse("03L997030ABCDE"));
        assertEquals(Optional.empty(), PartNumber.parse("03L99703?M"));
        assertEquals(Optional.empty(), PartNumber.parse(null));
    }

    @Test
    void patterns() {
        assertTrue(LabelFile.patternMatches("4K0-907-160-???  ", "4K0-907-160-A"));
        assertTrue(LabelFile.patternMatches("4K0-907-160-???", "4K0-907-160"));
        assertTrue(LabelFile.patternMatches("03l-997-030-m", "03L-997-030-M"));
        assertFalse(LabelFile.patternMatches("03L-997-030", "03L-997-030-M"));
        assertFalse(LabelFile.patternMatches("03L-997-030-A", "03L-997-030-M"));
        assertFalse(LabelFile.patternMatches("03L-997-030-?", "03L-997-030-AB"));
    }
}
