package io.github.samsanjaysatish.macvagdiag.tools;

import static io.github.samsanjaysatish.macvagdiag.tools.VinTest.TEST_VIN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.can.FakeTimeSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code sanitize-capture} and {@code sanitize-vcds-log} end to end on temporary files. */
class SanitizeCommandsTest {

    private static final String CAPTURE = """
            # macvagdiag can-log capture in candump log format (candump -L)
            # date: 2026-11-13T15:07:41-07:00
            (1794607708.000100) can0 7E0#0209020000000000
            (1794607708.010000) can0 7E8#1014490201314D38
            (1794607708.011000) can0 7E0#3000000000000000
            (1794607708.012000) can0 7E8#2147444D3941584B
            (1794607708.013000) can0 7E8#2250303432373838
            """;

    /** A capture without any VIN response (like the freeze-frame session). */
    private static final String NO_VIN_CAPTURE = """
            # date: 2026-11-13T15:14:19-07:00
            (1794608090.000000) can0 200#01C00010000301
            (1794608090.001000) can0 201#00D00003400701
            (1794608090.010000) can0 740#1000041800FF00
            """;

    @TempDir
    Path dir;

    private TestEnv env() {
        return new TestEnv(new FakeTimeSource(), List.of());
    }

    private Path file(String name, String content) throws IOException {
        return Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    }

    @Test
    void sanitizeCaptureFindsTheVinItselfAndWritesAVerifiedCopy() throws IOException {
        Path in = file("raw.log", CAPTURE);
        Path out = dir.resolve("sanitized/clean.log");
        TestEnv t = env();

        assertEquals(0, t.run("sanitize-capture", "--output", out.toString(), in.toString()), t.err());

        String written = Files.readString(out);
        assertFalse(written.contains(TEST_VIN));
        assertTrue(written.contains("7E8#1014490201335657"));
        assertTrue(t.out().contains("frames: 5 (3 changed)"), t.out());
        assertTrue(t.out().contains("7E8 ISO-TP, OBD-II 09 02 response (49 02 01): 1"), t.out());
        assertTrue(t.out().contains("1x 7E8 ISO-TP payload: VIN (line 4)"), t.out());
        assertTrue(t.out().contains("verified on the written file"), t.out());
        assertFalse(t.out().contains(TEST_VIN) || t.err().contains(TEST_VIN), "never prints the VIN");
        assertEquals(CAPTURE, Files.readString(in), "input untouched");
    }

    @Test
    void captureWithoutVinNeedsOneGivenAndThenVerifiesItsAbsence() throws IOException {
        Path in = file("ff.log", NO_VIN_CAPTURE);
        Path out = dir.resolve("ff-clean.log");
        TestEnv t = env();
        assertEquals(1, t.run("sanitize-capture", "--output", out.toString(), in.toString()));
        assertTrue(t.err().contains("no VIN found in the input; pass --vin or --vin-from"), t.err());
        assertFalse(Files.exists(out));

        Path vcds = file("log.vcds.txt", VcdsLogSanitizerTest.LOG);
        TestEnv t2 = env();
        assertEquals(0, t2.run("sanitize-capture", "--vin-from", vcds.toString(), "--output", out.toString(),
                in.toString()), t2.err());
        assertTrue(t2.out().contains("VIN: from " + vcds), t2.out());
        assertTrue(t2.out().contains("masked: 0 place(s)"), t2.out());
        assertTrue(Files.readString(out).contains("in 0 places"));
    }

    @Test
    void vinFromAnotherCaptureWorksToo() throws IOException {
        Path vinSource = file("raw.log", CAPTURE);
        Path in = file("ff.log", NO_VIN_CAPTURE);
        TestEnv t = env();
        assertEquals(0, t.run("sanitize-capture", "--vin-from", vinSource.toString(),
                "--output=" + dir.resolve("o.log"), in.toString()), t.err());
    }

    @Test
    void refusesADifferentVinThanTheCapturesOwn() throws IOException {
        Path in = file("raw.log", CAPTURE);
        Path out = dir.resolve("o.log");
        TestEnv t = env();
        assertEquals(1, t.run("sanitize-capture", "--vin", "WVWZZZ1KZ6W000001", "--output", out.toString(),
                in.toString()));
        assertTrue(t.err().contains("holds a VIN different from the one given"), t.err());
        assertFalse(t.err().contains(TEST_VIN) || t.err().contains("WVWZZZ1KZ6W000001"));
        assertFalse(Files.exists(out));
    }

    @Test
    void neverOverwritesAndRejectsBadArguments() throws IOException {
        Path in = file("raw.log", CAPTURE);
        Path existing = file("exists.log", "keep me");
        TestEnv t = env();
        assertEquals(2, t.run("sanitize-capture", "--output", existing.toString(), in.toString()));
        assertEquals("keep me", Files.readString(existing));

        assertEquals(2, env().run("sanitize-capture", in.toString()), "missing --output");
        assertEquals(2, env().run("sanitize-capture", "--output", in.toString(), in.toString()));
        assertEquals(2, env().run("sanitize-capture", "--output", "o.log", "--vin", "NOT-A-VIN", in.toString()));
        assertEquals(2, env().run("sanitize-capture", "--output", "o.log", "--bogus", in.toString()));
        assertEquals(2, env().run("sanitize-capture", "--output", "o.log", "a.log", "b.log"));
        assertEquals(1, env().run("sanitize-capture", "--output", dir.resolve("x.log").toString(),
                dir.resolve("missing.log").toString()));
        TestEnv help = env();
        assertEquals(0, help.run("sanitize-capture", "--help"));
        assertTrue(help.out().contains("--vin-from"));
    }

    @Test
    void failedVerificationLeavesNoFile() throws IOException {
        // A VIN piece in a frame the reassembly can't see (setup range): masking can't reach it, so refuse.
        Path in = file("raw.log", CAPTURE + "(1794607709.000000) can0 250#314D3847444D3941\n");
        Path out = dir.resolve("o.log");
        TestEnv t = env();
        assertEquals(1, t.run("sanitize-capture", "--output", out.toString(), in.toString()));
        assertTrue(t.err().contains("verification failed"), t.err());
        assertFalse(Files.exists(out));
    }

    @Test
    void sanitizeVcdsLogDefaultAndMaskAll() throws IOException {
        Path in = dir.resolve("log.vcds.txt");
        Files.write(in, VcdsLogSanitizerTest.LOG.getBytes(StandardCharsets.ISO_8859_1));
        Path out = dir.resolve("clean.vcds.txt");
        TestEnv t = env();

        assertEquals(0, t.run("sanitize-vcds-log", "--output", out.toString(), in.toString()), t.err());
        byte[] written = Files.readAllBytes(out);
        assertEquals(Files.size(in), written.length);
        String text = new String(written, StandardCharsets.ISO_8859_1);
        assertFalse(text.contains(TEST_VIN));
        assertTrue(text.contains("°C\r\n"));
        assertTrue(t.out().contains("2x VIN (lines 5, 9)"), t.out());
        assertTrue(t.out().contains("VCID: 1 (line 16), kept; --mask-vcid masks them"), t.out());
        assertTrue(t.out().contains("License Plate: 1 (line 5), masked"), t.out());
        assertTrue(t.out().contains("Repair Order: none"), t.out());
        assertFalse(t.out().contains(TEST_VIN) || t.out().contains("0123456789ABCDEF01"));

        Path all = dir.resolve("all.vcds.txt");
        TestEnv t2 = env();
        assertEquals(0, t2.run("sanitize-vcds-log", "--mask-all", "--output", all.toString(), in.toString()),
                t2.err());
        String allText = Files.readString(all, StandardCharsets.ISO_8859_1);
        assertFalse(allText.contains("0123456789ABCDEF01"));
        assertFalse(allText.contains("00001234567890"));
        assertTrue(t2.out().contains("Serial number: 3 (lines 13, 20, 22), masked"), t2.out());
    }

    @Test
    void vcdsLogWithoutVinNeedsOne() throws IOException {
        Path in = file("ff.vcds.txt", "Address 01: Engine\r\n   VCID: 0123456789ABCDEF01-8000\r\n");
        TestEnv t = env();
        assertEquals(1, t.run("sanitize-vcds-log", "--output", dir.resolve("o.txt").toString(), in.toString()));
        assertTrue(t.err().contains("no VIN found"), t.err());

        TestEnv t2 = env();
        assertEquals(0, t2.run("sanitize-vcds-log", "--vin", TEST_VIN.toLowerCase(), "--mask-vcid",
                "--output", dir.resolve("o.txt").toString(), in.toString()), t2.err());
        assertTrue(Files.readString(dir.resolve("o.txt")).contains("VCID: 000000000000000000-0000"));
        assertEquals(2, env().run("sanitize-vcds-log", "--mask-vcid=yes", "--output", "x", in.toString()));
    }
}
