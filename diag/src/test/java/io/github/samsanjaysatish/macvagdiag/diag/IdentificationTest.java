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
    @DisplayName("KWP 1A 9B (16 bytes, not the 46-byte layout): kept raw with a note; nothing else is sent")
    void kwpShortBlock() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.kwp().on("1A 9B", "5A 9B " + KWP_9B_DATA);
        EcuIdentification id = ok(new KwpDiagnosticService(ch, time).readIdentification());
        assertEquals(List.of("1A 9B"), ch.requestsHex());
        assertEquals(ApplicationProtocol.KWP2000, id.protocol());
        assertEquals(List.of(new RawRecord("1A 9B", RawBytes.ofHex(KWP_9B_DATA))), id.records());
        assertEquals(Map.of(), id.fields());
        assertEquals(List.of("1A 9B: 16 bytes, not the 46-byte block layout; kept raw"), id.notes());
    }

    @Test
    @DisplayName("KWP 1A 9B decoded: only 1A 9B is sent, never 1A 90 (VIN, user's decision 2026-11-17)")
    void kwpBlockOnlyNoVin() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.kwp().on("1A 9B", CaptureBytes.ENGINE_ID_9B);
        EcuIdentification id = ok(new KwpDiagnosticService(ch, time).readIdentification());
        assertEquals(List.of("1A 9B"), ch.requestsHex());
        assertEquals(Optional.of("0050078"), id.coding());
        assertEquals(List.of(), id.notes());
    }

    @Test
    @DisplayName("KWP 1A 9B -> 7F 1A 11 and every 22 DID -> 7F 22 31: the 1A 9B Negative is returned")
    void kwpAllRefused() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.kwp().on("1A 9B", "7F 1A 11");
        for (String did : List.of("22 F1 87", "22 F1 89", "22 F1 91", "22 F1 97", "22 06 00")) {
            ch.on(did, "7F 22 31");
        }
        DiagResult.Negative<?> n = assertInstanceOf(DiagResult.Negative.class, new KwpDiagnosticService(ch, time).readIdentification());
        assertEquals(0x1A, n.response().serviceId());
        assertEquals(0x11, n.response().code());
        assertEquals(List.of("1A 9B", "22 F1 87", "22 F1 89", "22 F1 91", "22 F1 97", "22 06 00"), ch.requestsHex());
        assertEquals(KwpDiagnosticService.MAX_IDENTIFICATION_REQUESTS, ch.requestsHex().size());
    }

    @Test
    @DisplayName("KWP 22 path: some DIDs refused, one not text, coding as hex: the rest decoded, each refusal noted")
    void kwpDidPathPartial() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.kwp().on("1A 9B", "7F 1A 11")
                .on("22 F1 87", "62 F1 87 " + Ascii.hex("1K0907379BL"))
                .on("22 F1 89", "7F 22 31")
                .on("22 F1 91", "62 F1 91 01 02")
                .on("22 F1 97", "62 F1 98 41")
                .on("22 06 00", "62 06 00 B1 4B 40 0C");
        EcuIdentification id = ok(new KwpDiagnosticService(ch, time).readIdentification());
        assertEquals(Map.of(EcuIdentification.Field.PART_NUMBER, "1K0907379BL", EcuIdentification.Field.CODING, "B14B400C"),
                id.fields());
        assertEquals(List.of("22 F1 87", "22 F1 91", "22 F1 97 (whole response, DID not echoed)", "22 06 00"),
                id.records().stream().map(RawRecord::id).toList());
        assertEquals(4, id.notes().size(), id.notes().toString()); // 1A 9B, F1 89, F1 91 not text, F1 97 echo
    }

    @Test
    @DisplayName("KWP 22 path: a DID timing out stops identification: TimedOut, later DIDs not sent")
    void kwpDidPathTimeoutStops() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.kwp().on("1A 9B", "7F 1A 11")
                .on("22 F1 87", "62 F1 87 41").onTimeout("22 F1 89");
        assertInstanceOf(DiagResult.TimedOut.class, new KwpDiagnosticService(ch, time).readIdentification());
        assertEquals(List.of("1A 9B", "22 F1 87", "22 F1 89"), ch.requestsHex());
    }

    @Test
    @DisplayName("KWP 1A 9B -> 7F 22 11 (negative for another SID: malformed): the Malformed is returned, nothing else sent")
    void kwpMalformed() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.kwp().on("1A 9B", "7F 22 11");
        assertInstanceOf(DiagResult.Malformed.class, new KwpDiagnosticService(ch, time).readIdentification());
        assertEquals(List.of("1A 9B"), ch.requestsHex());
    }

    @Test
    @DisplayName("KWP 1A 9B with an unknown coding type 05: coding kept raw with a note, the rest decoded")
    void kwpUnknownCodingType() throws InterruptedException {
        String block = CaptureBytes.ENGINE_ID_9B.replace("03 00 C3 9E", "05 00 C3 9E");
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.kwp().on("1A 9B", block);
        EcuIdentification id = ok(new KwpDiagnosticService(ch, time).readIdentification());
        assertEquals(Optional.empty(), id.coding());
        assertEquals(Optional.of("03L997030M"), id.partNumber());
        assertTrue(id.notes().getFirst().contains("coding type 05 not known"), id.notes().toString());
    }

    @Test
    @DisplayName("KWP 1A 9B timing out stops identification: TimedOut")
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
    @DisplayName("KWP 1A 9B -> 5A 9C ... (wrong option echo): kept raw with a note")
    void kwpOddAnswerKeptRaw() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.kwp().on("1A 9B", "5A 9C 01 02");
        EcuIdentification id = ok(new KwpDiagnosticService(ch, time).readIdentification());
        assertEquals(List.of(new RawRecord("1A 9B (whole response, option not echoed)", RawBytes.ofHex("5A 9C 01 02"))),
                id.records());
        assertEquals(Map.of(), id.fields());
        assertEquals(1, id.notes().size());
    }

    // ---- UDS ----

    private static FakeDiagnosticChannel udsAll() {
        return FakeDiagnosticChannel.uds()
                .on("22 F1 87", "62 F1 87 " + Ascii.hex("03L906018JL "))
                .on("22 F1 89", "62 F1 89 " + Ascii.hex("1234") + " 00 00")
                .on("22 F1 91", "62 F1 91 " + Ascii.hex("03L907309  "))
                .on("22 F1 97", "62 F1 97 " + Ascii.hex("R4 2.0L EDC  "))
                .on("22 06 00", "62 06 00 00 00 03 2F 41");
    }

    @Test
    @DisplayName("UDS 22 F1 87 / F1 89 / F1 91 / F1 97 / 06 00: all fields decoded, every answer kept raw, never F1 90")
    void udsAllFields() throws InterruptedException {
        FakeDiagnosticChannel ch = udsAll();
        EcuIdentification id = ok(new UdsDiagnosticService(ch, time).readIdentification());
        assertEquals(List.of("22 F1 87", "22 F1 89", "22 F1 91", "22 F1 97", "22 06 00"), ch.requestsHex());
        assertEquals(Optional.of("03L906018JL"), id.partNumber());
        assertEquals(Optional.of("1234"), id.softwareVersion());
        assertEquals(Optional.of("03L907309"), id.hardwareNumber());
        assertEquals(Optional.of("R4 2.0L EDC"), id.systemName());
        assertEquals(Optional.of("0000032F41"), id.coding());
        assertEquals(5, id.records().size());
        assertEquals(new RawRecord("22 06 00", RawBytes.ofHex("00 00 03 2F 41")), id.records().get(4));
        assertEquals(List.of(), id.notes());
    }

    @Test
    @DisplayName("UDS 22 F1 97 -> 7F 22 31 and 22 06 00 -> 7F 22 31: noted, the rest still decoded")
    void udsSomeRefused() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.uds()
                .on("22 F1 87", "62 F1 87 " + Ascii.hex("03L906018JL"))
                .on("22 F1 89", "62 F1 89 " + Ascii.hex("1234"))
                .on("22 F1 91", "62 F1 91 " + Ascii.hex("03L907309"))
                .on("22 F1 97", "7F 22 31")
                .on("22 06 00", "7F 22 31");
        EcuIdentification id = ok(new UdsDiagnosticService(ch, time).readIdentification());
        assertEquals(3, id.records().size());
        assertEquals(Optional.empty(), id.systemName());
        assertEquals(2, id.notes().size());
        assertTrue(id.notes().getFirst().contains("requestOutOfRange"), id.notes().getFirst());
    }

    @Test
    @DisplayName("UDS non-text F1 87 (01 02 03), empty values and a DID echo mismatch are kept raw with notes")
    void udsOddValuesKeptRaw() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.uds()
                .on("22 F1 87", "62 F1 87 01 02 03")
                .on("22 F1 89", "62 F1 88 " + Ascii.hex("1234"))
                .on("22 F1 91", "62 F1 91")
                .on("22 F1 97", "62 F1 97 " + Ascii.hex("EDC17"))
                .on("22 06 00", "62 06 00");
        EcuIdentification id = ok(new UdsDiagnosticService(ch, time).readIdentification());
        assertEquals(Map.of(EcuIdentification.Field.SYSTEM_NAME, "EDC17"), id.fields());
        assertTrue(id.records().contains(new RawRecord("22 F1 87", RawBytes.ofHex("01 02 03"))));
        assertTrue(id.records().contains(new RawRecord("22 F1 89 (whole response, DID not echoed)",
                RawBytes.ofHex("62 F1 88 " + Ascii.hex("1234")))));
        assertEquals(4, id.notes().size(), id.notes().toString());
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
                .on("22 06 00", "7F 22 31");
        EcuIdentification id = ok(new UdsDiagnosticService(ch, time).readIdentification());
        assertEquals(Optional.of("03L906018JL"), id.partNumber());
        assertEquals(5, ch.requestsHex().size(), "no resend after 78");
    }
}
