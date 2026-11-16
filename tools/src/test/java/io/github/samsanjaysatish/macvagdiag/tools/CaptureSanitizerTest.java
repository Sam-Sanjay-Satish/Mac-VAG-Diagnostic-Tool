package io.github.samsanjaysatish.macvagdiag.tools;

import static io.github.samsanjaysatish.macvagdiag.tools.VinTest.TEST_VIN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.can.CaptureReader;
import io.github.samsanjaysatish.macvagdiag.tools.CaptureSanitizer.Occurrence;
import io.github.samsanjaysatish.macvagdiag.tools.Redactions.Kind;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Hand-written captures holding the made-up VIN {@code 1M8GDM9AXKP042788}
 * ({@code 31 4D 38 47 44 4D 39 41 58 4B 50 30 34 32 37 38 38}). The replacement {@code 3VW00000XD0000000} is
 * {@code 33 56 57 30 30 30 30 30 58 44 30 30 30 30 30 30 30}.
 */
class CaptureSanitizerTest {

    private static final String HEADER = """
            # macvagdiag can-log capture in candump log format (candump -L)
            # Raw capture, may contain the VIN. Keep it in local/ and never commit it unsanitized
            # date: 2026-11-13T15:07:41-07:00
            # bitrate: 500000
            """;

    /** OBD-II 09 02 on 7E0 / 7E8, VIN split over a first frame and two consecutive frames. */
    private static final String OBD_VIN = """
            (1794607708.000100) can0 7E0#0209020000000000
            (1794607708.010000) can0 7E8#1014490201314D38
            (1794607708.011000) can0 7E0#3000000000000000
            (1794607708.012000) can0 7E8#2147444D3941584B
            (1794607708.013000) can0 7E8#2250303432373838
            """;

    private static final String OBD_FAKE = """
            (1794607708.000100) can0 7E0#0209020000000000
            (1794607708.010000) can0 7E8#1014490201335657
            (1794607708.011000) can0 7E0#3000000000000000
            (1794607708.012000) can0 7E8#2130303030305844
            (1794607708.013000) can0 7E8#2230303030303030
            """;

    /** TP 2.0 to engine 01; a 7F 1A 78 with the length flag, then KWP 5A 90 + VIN over three packets. */
    private static final String TP20_VIN = """
            (1794607709.000000) can0 200#01C00010000301
            (1794607709.001000) can0 201#00D00003400701
            (1794607709.002000) can0 740#A00F8AFF32FF
            (1794607709.003000) can0 300#A10F8AFF4AFF
            (1794607709.004000) can0 740#1000021A90
            (1794607709.005000) can0 300#B1
            (1794607709.010000) can0 300#1180037F1A78
            (1794607709.011000) can0 740#B2
            (1794607709.020000) can0 300#2200135A90314D38
            (1794607709.021000) can0 300#2347444D3941584B
            (1794607709.022000) can0 300#1450303432373838
            (1794607709.023000) can0 740#B5
            """;

    private static final Redactions VIN = Redactions.of(Optional.of(TEST_VIN), List.of());

    private static String sanitizedLine(int places) {
        return "# sanitized: macvagdiag-tools 0.1.0-dev sanitize-capture; VIN replaced by 3VW00000XD0000000 in "
                + places + " place" + (places == 1 ? "" : "s") + "; --mask strings masked in 0 places\n";
    }

    @Test
    void obdVinSplitAcrossFramesIsReplacedAndEverythingElseStays() throws SanitizeException {
        CaptureSanitizer.Result r = CaptureSanitizer.sanitize(HEADER + OBD_VIN, VIN);

        assertEquals(HEADER + sanitizedLine(1) + OBD_FAKE, r.output());
        assertEquals(5, r.frames());
        assertEquals(3, r.framesChanged());
        assertEquals(List.of(new Occurrence("7E8 ISO-TP payload", Kind.VIN, 6)), r.occurrences());
        CaptureSanitizer.verify(HEADER + OBD_VIN, r.output(), VIN);
    }

    @Test
    void kwpVinOverTp20WithTheLengthFlagIsReplaced() throws SanitizeException {
        CaptureSanitizer.Result r = CaptureSanitizer.sanitize(HEADER + TP20_VIN, VIN);

        String expected = HEADER + sanitizedLine(1) + TP20_VIN
                .replace("300#2200135A90314D38", "300#2200135A90335657")
                .replace("300#2347444D3941584B", "300#2330303030305844")
                .replace("300#1450303432373838", "300#1430303030303030");
        assertEquals(expected, r.output());
        assertEquals(List.of(new Occurrence("300 TP 2.0 payload", Kind.VIN, 13)), r.occurrences());
        CaptureSanitizer.verify(HEADER + TP20_VIN, r.output(), VIN);
    }

    @Test
    void udsF190AndPartialVinsElsewhereAreMasked() throws SanitizeException {
        String capture = HEADER + """
                (1794607710.000000) can0 714#0322F19055555555
                (1794607710.010000) can0 77E#101462F190314D38
                (1794607710.011000) can0 714#3000015555555555
                (1794607710.012000) can0 77E#2147444D3941584B
                (1794607710.013000) can0 77E#2250303432373838
                (1794607710.100000) can0 77E#100B62F18C4B5030
                (1794607710.101000) can0 77E#21343237383820AA
                (1794607710.200000) can0 77E#10085A8630343237
                (1794607710.201000) can0 77E#213838AAAAAAAAAA
                """;
        CaptureSanitizer.Result r = CaptureSanitizer.sanitize(capture, VIN);

        assertTrue(r.output().contains("77E#101462F190335657"));
        // "KP042788" (last 8) -> fake offset 9..16 "D0000000".
        assertTrue(r.output().contains("77E#100B62F18C4430"));
        assertTrue(r.output().contains("77E#21303030303020AA"), "padding and the byte after the length stay");
        // the serial "042788" alone -> "000000"
        assertTrue(r.output().contains("77E#10085A8630303030"));
        assertTrue(r.output().contains("77E#213030AAAAAAAAAA"));
        assertEquals(List.of(Kind.VIN, Kind.VIN_PART, Kind.VIN_SERIAL),
                r.occurrences().stream().map(Occurrence::kind).toList());
        CaptureSanitizer.verify(capture, r.output(), VIN);
    }

    @Test
    void vinInCommentLinesIsMaskedAsText() throws SanitizeException {
        String capture = HEADER + "# note: Jetta " + TEST_VIN + ", serial 042788\n" + OBD_VIN;
        CaptureSanitizer.Result r = CaptureSanitizer.sanitize(capture, VIN);
        assertTrue(r.output().contains("# note: Jetta 3VW00000XD0000000, serial 000000\n"));
        assertFalse(r.output().contains(TEST_VIN));
        assertEquals(3, r.occurrences().size());
        CaptureSanitizer.verify(capture, r.output(), VIN);
    }

    @Test
    void keepsLowercaseHexCrlfAndFramesWithoutVin() throws SanitizeException, IOException {
        String capture = (HEADER + OBD_VIN.toLowerCase()).replace("\n", "\r\n");
        CaptureSanitizer.Result r = CaptureSanitizer.sanitize(capture, VIN);
        assertEquals((HEADER + sanitizedLine(1) + OBD_FAKE.toLowerCase()).replace("\n", "\r\n"), r.output());
        assertEquals(5, CaptureReader.read(new StringReader(r.output())).frames().size());
        CaptureSanitizer.verify(capture, r.output(), VIN);
    }

    @Test
    void captureWithoutTheVinGetsOnlyTheSanitizedLine() throws SanitizeException {
        String capture = HEADER + "(1794607708.000100) can0 7E0#0209020000000000\n";
        CaptureSanitizer.Result r = CaptureSanitizer.sanitize(capture, VIN);
        assertEquals(HEADER + sanitizedLine(0) + "(1794607708.000100) can0 7E0#0209020000000000\n", r.output());
        assertEquals(0, r.framesChanged());
        CaptureSanitizer.verify(capture, r.output(), VIN);
    }

    @Test
    void failsLoudlyWhenAVinPieceSitsOutsideAnyPayload() throws SanitizeException {
        // 250 is in the TP 2.0 setup range: not a payload, so masking can't touch it, but verification sees it.
        String capture = HEADER + "(1794607708.000100) can0 250#314D3847444D3941\n";
        CaptureSanitizer.Result r = CaptureSanitizer.sanitize(capture, VIN);
        SanitizeException e = assertThrows(SanitizeException.class,
                () -> CaptureSanitizer.verify(capture, r.output(), VIN));
        assertTrue(e.getMessage().contains("raw frame bytes at line 6"), e.getMessage());
        assertFalse(e.getMessage().contains("1M8GDM9"), "never prints the VIN");
    }

    @Test
    void verifyCatchesAVinLeftInThePayload() throws SanitizeException {
        String capture = HEADER + OBD_VIN;
        // a "sanitized" output that forgot to mask anything
        String bad = HEADER + sanitizedLine(0) + OBD_VIN;
        SanitizeException e = assertThrows(SanitizeException.class, () -> CaptureSanitizer.verify(capture, bad, VIN));
        assertTrue(e.getMessage().contains("7E8 ISO-TP payload: VIN remains at line 7"), e.getMessage());
    }

    @Test
    void verifyCatchesStructuralChanges() throws SanitizeException {
        String capture = HEADER + OBD_VIN;
        String output = CaptureSanitizer.sanitize(capture, VIN).output();
        assertThrows(SanitizeException.class, () -> CaptureSanitizer.verify(capture,
                output.replace("(1794607708.000100)", "(1794607708.000200)"), VIN));
        assertThrows(SanitizeException.class, () -> CaptureSanitizer.verify(capture,
                output.replace("7E0#0209020000000000", "7E0#0209020000000001"), VIN), "byte outside a payload");
        assertThrows(SanitizeException.class, () -> CaptureSanitizer.verify(capture,
                output.replace("# sanitized:", "# other:"), VIN));
        assertThrows(SanitizeException.class, () -> CaptureSanitizer.verify(capture,
                output.replace("can0 7E8#2230303030303030\n", ""), VIN));
    }

    @Test
    void extraMaskStringsInPayloads() throws SanitizeException {
        // A 14-digit module serial in a KWP 1A 86 style response, split over two ISO-TP frames.
        String capture = HEADER + """
                (1794607710.000000) can0 77E#100E5A8630303030
                (1794607710.001000) can0 77E#2131323034323730
                (1794607710.002000) can0 77E#2237AAAAAAAAAAAA
                """;
        Redactions r = Redactions.of(Optional.of(TEST_VIN), List.of("00001204270"));
        CaptureSanitizer.Result result = CaptureSanitizer.sanitize(capture, r);
        assertTrue(result.output().contains("77E#2130303030303030"), result.output());
        assertEquals(List.of(Kind.EXTRA), result.occurrences().stream().map(Occurrence::kind).toList());
        CaptureSanitizer.verify(capture, result.output(), r);
    }

    @Test
    void rejectsInvalidCaptures() {
        assertThrows(SanitizeException.class, () -> CaptureSanitizer.sanitize("(1) can0 7E8#123\n", VIN));
        assertThrows(SanitizeException.class, () -> CaptureSanitizer.sanitize("(1) can0 7E8#11.22\n", VIN),
                "dotted data isn't supported");
    }

    @Test
    void findsVinResponsesAndVinLikeStrings() throws SanitizeException {
        var messages = DiagPayloads.reassemble(CaptureSanitizer.Parsed.of(HEADER + OBD_VIN + TP20_VIN).frames);
        var found = VinFinder.inResponses(messages);
        assertEquals(List.of("7E8 ISO-TP, OBD-II 09 02 response (49 02 01)", "300 TP 2.0, KWP 1A 90 response (5A 90)"),
                found.stream().map(VinFinder.Found::where).toList());
        assertTrue(found.stream().allMatch(f -> f.vin().equals(TEST_VIN)));
        assertEquals(List.of(), VinFinder.vinLike(messages, Set.of(TEST_VIN)));
        assertEquals(2, VinFinder.vinLike(messages, Set.of()).size());
    }
}
