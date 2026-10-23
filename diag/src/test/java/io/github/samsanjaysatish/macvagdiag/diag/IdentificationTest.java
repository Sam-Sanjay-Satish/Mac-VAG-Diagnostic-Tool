package io.github.samsanjaysatish.macvagdiag.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.can.FakeTimeSource;
import io.github.samsanjaysatish.macvagdiag.transport.ApplicationProtocol;
import io.github.samsanjaysatish.macvagdiag.transport.FakeDiagnosticChannel;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ECU identification: KWP {@code 1A xx}, UDS {@code 22 [DID]}. Raw always kept; fields only where decodable. */
class IdentificationTest {

    private static final String KWP_9B_DATA = Ascii.hex("03L906018JL ") + " 00 01 02 FE";

    private final FakeTimeSource time = new FakeTimeSource();

    private static EcuIdentification ok(DiagResult<EcuIdentification> r) {
        assertTrue(r.isSuccess(), r.message());
        return r.valueIfSuccess().orElseThrow();
    }

    // ---- KWP ----

    @Test
    @DisplayName("KWP 1A 9B and 1A 90: 9B block kept raw (not decoded yet), VIN decoded")
    void kwpBoth() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.kwp()
                .on("1A 9B", "5A 9B " + KWP_9B_DATA)
                .on("1A 90", "5A 90 " + Ascii.hex(Ascii.TEST_VIN));
        EcuIdentification id = ok(new KwpDiagnosticService(ch, time).readIdentification());
        assertEquals(List.of("1A 9B", "1A 90"), ch.requestsHex());
        assertEquals(ApplicationProtocol.KWP2000, id.protocol());
        assertEquals(List.of(new RawRecord("1A 9B", RawBytes.ofHex(KWP_9B_DATA)),
                new RawRecord("1A 90", RawBytes.ofHex(Ascii.hex(Ascii.TEST_VIN)))), id.records());
        assertEquals(Map.of(EcuIdentification.Field.VIN, Ascii.TEST_VIN), id.fields());
        assertEquals(Optional.empty(), id.partNumber(), "1A 9B layout is UNCONFIRMED, so nothing is decoded from it");
        assertEquals(List.of(), id.notes());
    }

    @Test
    @DisplayName("KWP 1A 90 -> 7F 1A 12: noted, identification still succeeds with 1A 9B")
    void kwpVinRefused() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.kwp()
                .on("1A 9B", "5A 9B " + KWP_9B_DATA)
                .on("1A 90", "7F 1A 12");
        EcuIdentification id = ok(new KwpDiagnosticService(ch, time).readIdentification());
        assertEquals(1, id.records().size());
        assertEquals(Optional.empty(), id.vin());
        assertEquals(1, id.notes().size());
        assertTrue(id.notes().getFirst().startsWith("1A 90: Negative response 7F 1A 12 subFunctionNotSupported"), id.notes().getFirst());
    }

    @Test
    @DisplayName("KWP 1A 9B -> 7F 1A 11 and 1A 90 -> 7F 1A 31: the first Negative is returned")
    void kwpAllRefused() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.kwp().on("1A 9B", "7F 1A 11").on("1A 90", "7F 1A 31");
        DiagResult.Negative<?> n = assertInstanceOf(DiagResult.Negative.class, new KwpDiagnosticService(ch, time).readIdentification());
        assertEquals(0x11, n.response().code());
        assertEquals(2, ch.requestsHex().size());
    }

    @Test
    @DisplayName("KWP 1A 9B timing out stops identification: TimedOut, 1A 90 not sent")
    void kwpTimeoutStops() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.kwp().onTimeout("1A 9B");
        assertInstanceOf(DiagResult.TimedOut.class, new KwpDiagnosticService(ch, time).readIdentification());
        assertEquals(List.of("1A 9B"), ch.requestsHex());
    }

    @Test
    @DisplayName("KWP 1A 9B -> 7F 1A 21 busy stops identification and is retryable")
    void kwpBusyStops() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.kwp().on("1A 9B", "7F 1A 21");
        DiagResult<EcuIdentification> r = new KwpDiagnosticService(ch, time).readIdentification();
        assertTrue(r.retryable());
        assertEquals(List.of("1A 9B"), ch.requestsHex());
    }

    @Test
    @DisplayName("KWP 1A 9B -> 5A 9C ... (wrong option echo) and 1A 90 -> 5A 90 41 42 (not a VIN): both kept raw with notes")
    void kwpOddAnswersKeptRaw() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.kwp().on("1A 9B", "5A 9C 01 02").on("1A 90", "5A 90 41 42");
        EcuIdentification id = ok(new KwpDiagnosticService(ch, time).readIdentification());
        assertEquals(List.of(
                new RawRecord("1A 9B (whole response, option not echoed)", RawBytes.ofHex("5A 9C 01 02")),
                new RawRecord("1A 90", RawBytes.ofHex("41 42"))), id.records());
        assertEquals(Map.of(), id.fields());
        assertEquals(2, id.notes().size());
    }

    // ---- UDS ----

    private static FakeDiagnosticChannel udsAll() {
        return FakeDiagnosticChannel.uds()
                .on("22 F1 87", "62 F1 87 " + Ascii.hex("03L906018JL "))
                .on("22 F1 89", "62 F1 89 " + Ascii.hex("1234") + " 00 00")
                .on("22 F1 91", "62 F1 91 " + Ascii.hex("03L907309  "))
                .on("22 F1 97", "62 F1 97 " + Ascii.hex("R4 2.0L EDC  "))
                .on("22 06 00", "62 06 00 00 00 03 2F 41")
                .on("22 F1 90", "62 F1 90 " + Ascii.hex(Ascii.TEST_VIN));
    }

    @Test
    @DisplayName("UDS 22 F1 87 / F1 89 / F1 91 / F1 97 / 06 00 / F1 90: all fields decoded, every answer kept raw")
    void udsAllFields() throws InterruptedException {
        FakeDiagnosticChannel ch = udsAll();
        EcuIdentification id = ok(new UdsDiagnosticService(ch, time).readIdentification());
        assertEquals(List.of("22 F1 87", "22 F1 89", "22 F1 91", "22 F1 97", "22 06 00", "22 F1 90"), ch.requestsHex());
        assertEquals(Optional.of("03L906018JL"), id.partNumber());
        assertEquals(Optional.of("1234"), id.softwareVersion());
        assertEquals(Optional.of("03L907309"), id.hardwareNumber());
        assertEquals(Optional.of("R4 2.0L EDC"), id.systemName());
        assertEquals(Optional.of("0000032F41"), id.coding());
        assertEquals(Optional.of(Ascii.TEST_VIN), id.vin());
        assertEquals(6, id.records().size());
        assertEquals(new RawRecord("22 06 00", RawBytes.ofHex("00 00 03 2F 41")), id.records().get(4));
        assertEquals(List.of(), id.notes());
    }

    @Test
    @DisplayName("UDS 22 F1 97 -> 7F 22 31 and 22 F1 90 -> 7F 22 31: noted, the rest still decoded")
    void udsSomeRefused() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.uds()
                .on("22 F1 87", "62 F1 87 " + Ascii.hex("03L906018JL"))
                .on("22 F1 89", "62 F1 89 " + Ascii.hex("1234"))
                .on("22 F1 91", "62 F1 91 " + Ascii.hex("03L907309"))
                .on("22 F1 97", "7F 22 31")
                .on("22 06 00", "7F 22 31")
                .on("22 F1 90", "7F 22 31");
        EcuIdentification id = ok(new UdsDiagnosticService(ch, time).readIdentification());
        assertEquals(3, id.records().size());
        assertEquals(Optional.empty(), id.systemName());
        assertEquals(3, id.notes().size());
        assertTrue(id.notes().getFirst().contains("requestOutOfRange"), id.notes().getFirst());
    }

    @Test
    @DisplayName("UDS non-text F1 87 (01 02 03), short VIN and DID echo mismatch are kept raw with notes")
    void udsOddValuesKeptRaw() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.uds()
                .on("22 F1 87", "62 F1 87 01 02 03")
                .on("22 F1 89", "62 F1 88 " + Ascii.hex("1234"))
                .on("22 F1 91", "62 F1 91")
                .on("22 F1 97", "62 F1 97 " + Ascii.hex("EDC17"))
                .on("22 06 00", "62 06 00")
                .on("22 F1 90", "62 F1 90 " + Ascii.hex("SHORTVIN"));
        EcuIdentification id = ok(new UdsDiagnosticService(ch, time).readIdentification());
        assertEquals(Map.of(EcuIdentification.Field.SYSTEM_NAME, "EDC17"), id.fields());
        assertTrue(id.records().contains(new RawRecord("22 F1 87", RawBytes.ofHex("01 02 03"))));
        assertTrue(id.records().contains(new RawRecord("22 F1 89 (whole response, DID not echoed)",
                RawBytes.ofHex("62 F1 88 " + Ascii.hex("1234")))));
        assertTrue(id.records().contains(new RawRecord("22 F1 90", RawBytes.ofHex(Ascii.hex("SHORTVIN")))));
        assertEquals(5, id.notes().size(), id.notes().toString());
    }

    @Test
    @DisplayName("UDS 22 F1 89 -> 7F 22 21 busy stops identification: Busy, later DIDs not sent")
    void udsBusyStops() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.uds()
                .on("22 F1 87", "62 F1 87 " + Ascii.hex("03L906018JL"))
                .on("22 F1 89", "7F 22 21");
        DiagResult<EcuIdentification> r = new UdsDiagnosticService(ch, time).readIdentification();
        assertInstanceOf(DiagResult.Busy.class, r);
        assertEquals(List.of("22 F1 87", "22 F1 89"), ch.requestsHex());
    }

    @Test
    @DisplayName("UDS 22 F1 87 -> 7F 22 78 then 62 F1 87 ...: pending handled per DID")
    void udsPendingPerDid() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.uds()
                .on("22 F1 87", "7F 22 78", "62 F1 87 " + Ascii.hex("03L906018JL"))
                .on("22 F1 89", "7F 22 31").on("22 F1 91", "7F 22 31").on("22 F1 97", "7F 22 31")
                .on("22 06 00", "7F 22 31").on("22 F1 90", "7F 22 31");
        EcuIdentification id = ok(new UdsDiagnosticService(ch, time).readIdentification());
        assertEquals(Optional.of("03L906018JL"), id.partNumber());
        assertEquals(6, ch.requestsHex().size(), "no resend after 78");
    }
}
