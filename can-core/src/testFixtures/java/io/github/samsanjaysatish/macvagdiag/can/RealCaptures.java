package io.github.samsanjaysatish.macvagdiag.can;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * The real-car fixtures from the 2026-11-13 sniffing session, shared by every layer's tests (they live in this
 * project's test fixtures: {@code src/testFixtures/resources/fixtures/2026-11-13/}).
 *
 * <ul>
 *   <li>{@link #autoScan()}: the listen-only capture of a full VCDS Auto-Scan (2535 frames), and
 *       {@link #autoScanVcdsLog()}, the VCDS report of the same scan.</li>
 *   <li>{@link #freezeFrame01()}: the capture of a VCDS 01 Engine fault-code read with freeze frames (454 frames),
 *       and {@link #freezeFrame01VcdsLog()}.</li>
 * </ul>
 *
 * <p><b>Privacy:</b> the files were made with {@code macvagdiag-tools sanitize-capture} / {@code sanitize-vcds-log},
 * which replaced the VIN by {@link #MASKED_VIN} everywhere (in the captures: the 6 OBD-II {@code 09 02} replies).
 * By the user's decision of 2026-11-15 nothing else is masked: module serial numbers, VCIDs, the VINID and workshop
 * codes are kept as recorded. Never add a fixture that still contains the real VIN.
 *
 * <p>The VCDS logs are Windows-1252 text with CRLF line ends; they are read as such and returned with {@code \n}
 * line ends. The laptop's clock was wrong, so dates in the VCDS headers (and VCDS's UDS freeze-frame dates) are
 * not the session's.
 */
public final class RealCaptures {

    /** What the sanitizer put in place of the real VIN (valid check digit, VW Mexico, model year 2013). */
    public static final String MASKED_VIN = "3VW00000XD0000000";

    public static final String DIRECTORY = "/fixtures/2026-11-13/";
    public static final String AUTO_SCAN_CAPTURE = DIRECTORY + "2026-11-13-150741-autoscan-2.log";
    public static final String AUTO_SCAN_VCDS_LOG = DIRECTORY + "2026-11-13-150741-autoscan-2.vcds.txt";
    public static final String FREEZE_FRAME_01_CAPTURE = DIRECTORY + "2026-11-13-151419-freezeframe-01.log";
    public static final String FREEZE_FRAME_01_VCDS_LOG = DIRECTORY + "2026-11-13-151419-freezeframe-01.vcds.txt";

    private static final Charset VCDS_CHARSET = Charset.forName("windows-1252");

    private static Capture autoScan;
    private static Capture freezeFrame01;

    private RealCaptures() {
    }

    /** The VCDS Auto-Scan capture (cached; {@link Capture} is immutable). */
    public static synchronized Capture autoScan() {
        if (autoScan == null) {
            autoScan = capture(AUTO_SCAN_CAPTURE);
        }
        return autoScan;
    }

    /** The 01 Engine fault codes + freeze frames capture (cached). */
    public static synchronized Capture freezeFrame01() {
        if (freezeFrame01 == null) {
            freezeFrame01 = capture(FREEZE_FRAME_01_CAPTURE);
        }
        return freezeFrame01;
    }

    public static String autoScanVcdsLog() {
        return vcdsLog(AUTO_SCAN_VCDS_LOG);
    }

    public static String freezeFrame01VcdsLog() {
        return vcdsLog(FREEZE_FRAME_01_VCDS_LOG);
    }

    /** A capture fixture by resource name. */
    public static Capture capture(String resource) {
        try (Reader in = new InputStreamReader(open(resource), StandardCharsets.US_ASCII)) {
            return CaptureReader.read(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Can't read fixture " + resource, e);
        }
    }

    /** A VCDS log fixture by resource name, with {@code \n} line ends. */
    public static String vcdsLog(String resource) {
        try (InputStream in = open(resource)) {
            return new String(in.readAllBytes(), VCDS_CHARSET).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException("Can't read fixture " + resource, e);
        }
    }

    /** The raw bytes of a fixture, as committed. */
    public static byte[] bytes(String resource) {
        try (InputStream in = open(resource)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Can't read fixture " + resource, e);
        }
    }

    private static InputStream open(String resource) {
        InputStream in = RealCaptures.class.getResourceAsStream(resource);
        if (in == null) {
            throw new IllegalArgumentException("No fixture " + resource + " on the class path");
        }
        return in;
    }
}
