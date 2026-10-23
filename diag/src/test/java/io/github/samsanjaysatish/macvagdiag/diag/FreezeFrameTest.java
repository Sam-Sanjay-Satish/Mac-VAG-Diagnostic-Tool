package io.github.samsanjaysatish.macvagdiag.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.can.FakeTimeSource;
import io.github.samsanjaysatish.macvagdiag.transport.FakeDiagnosticChannel;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** UDS {@code 19 04} / {@code 19 06} by DTC, kept raw; KWP not available yet. */
class FreezeFrameTest {

    private static final UdsDtc P0401 = new UdsDtc(0x040100, 0x2F);
    private static final String SNAPSHOT = "59 04 04 01 00 2F 01 02 F4 0D 00 F4 0C 0B B8";
    private static final String EXTENDED = "59 06 04 01 00 2F 01 05 02 FF 03 00 12 34";

    private final FakeTimeSource time = new FakeTimeSource();

    private static FreezeFrame ok(DiagResult<FreezeFrame> r) {
        assertTrue(r.isSuccess(), r.message());
        return r.valueIfSuccess().orElseThrow();
    }

    @Test
    @DisplayName("19 04 04 01 00 FF and 19 06 04 01 00 FF: both kept raw with their request as ID, responses kept whole")
    void both() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.uds()
                .on("19 04 04 01 00 FF", SNAPSHOT)
                .on("19 06 04 01 00 FF", EXTENDED);
        FreezeFrame ff = ok(new UdsDiagnosticService(ch, time).readFreezeFrame(P0401));
        assertEquals(List.of("19 04 04 01 00 FF", "19 06 04 01 00 FF"), ch.requestsHex());
        assertEquals(P0401, ff.dtc());
        assertEquals(List.of(
                new RawRecord("19 04 04 01 00 FF", RawBytes.ofHex("01 02 F4 0D 00 F4 0C 0B B8")),
                new RawRecord("19 06 04 01 00 FF", RawBytes.ofHex("01 05 02 FF 03 00 12 34"))), ff.records());
        assertEquals(List.of(RawBytes.ofHex(SNAPSHOT), RawBytes.ofHex(EXTENDED)), ff.responses());
        assertEquals(List.of(), ff.fields(), "nothing decoded from memory");
        assertEquals(List.of(), ff.notes());
    }

    @Test
    @DisplayName("19 04 -> 7F 19 31 (no snapshot), 19 06 answers: success with a note")
    void snapshotRefused() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.uds()
                .on("19 04 04 01 00 FF", "7F 19 31")
                .on("19 06 04 01 00 FF", EXTENDED);
        FreezeFrame ff = ok(new UdsDiagnosticService(ch, time).readFreezeFrame(P0401));
        assertEquals(1, ff.records().size());
        assertEquals(1, ff.notes().size());
        assertTrue(ff.notes().getFirst().startsWith("19 04 04 01 00 FF: Negative response 7F 19 31"), ff.notes().getFirst());
    }

    @Test
    @DisplayName("19 04 and 19 06 both -> 7F 19 31: the first Negative is returned")
    void bothRefused() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.uds()
                .on("19 04 04 01 00 FF", "7F 19 31")
                .on("19 06 04 01 00 FF", "7F 19 12");
        DiagResult.Negative<?> n = assertInstanceOf(DiagResult.Negative.class,
                new UdsDiagnosticService(ch, time).readFreezeFrame(P0401));
        assertEquals(0x31, n.response().code());
    }

    @Test
    @DisplayName("19 04 -> 7F 19 21 busy stops: Busy, 19 06 not sent")
    void busyStops() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.uds().on("19 04 04 01 00 FF", "7F 19 21");
        assertInstanceOf(DiagResult.Busy.class, new UdsDiagnosticService(ch, time).readFreezeFrame(P0401));
        assertEquals(List.of("19 04 04 01 00 FF"), ch.requestsHex());
    }

    @Test
    @DisplayName("59 04 04 01 00 2F with no records, and 59 06 for another DTC: notes, nothing dropped")
    void emptyAndEchoMismatch() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.uds()
                .on("19 04 04 01 00 FF", "59 04 04 01 00 2F")
                .on("19 06 04 01 00 FF", "59 06 03 00 00 2F 01 05");
        FreezeFrame ff = ok(new UdsDiagnosticService(ch, time).readFreezeFrame(P0401));
        assertEquals(List.of(new RawRecord("19 06 04 01 00 FF", RawBytes.ofHex("01 05"))), ff.records());
        assertEquals(2, ff.notes().size(), ff.notes().toString());
        assertTrue(ff.notes().get(0).contains("no records stored"));
        assertTrue(ff.notes().get(1).contains("answered for DTC 03 00 00"));
        assertEquals(2, ff.responses().size());
    }

    @Test
    @DisplayName("truncated 59 04 04 01 header and wrong echo 59 02 ...: whole response kept raw with a note")
    void badHeaderKeptWhole() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.uds()
                .on("19 04 04 01 00 FF", "59 04 04 01")
                .on("19 06 04 01 00 FF", "59 02 04 01 00 2F 01");
        FreezeFrame ff = ok(new UdsDiagnosticService(ch, time).readFreezeFrame(P0401));
        assertEquals(List.of(
                new RawRecord("19 04 04 01 00 FF (whole response, unexpected header)", RawBytes.ofHex("59 04 04 01")),
                new RawRecord("19 06 04 01 00 FF (whole response, unexpected header)", RawBytes.ofHex("59 02 04 01 00 2F 01"))),
                ff.records());
        assertEquals(2, ff.notes().size());
    }

    @Test
    @DisplayName("19 04 with a 24-bit DTC C1 00 87 is built byte for byte")
    void dtcBytes() throws InterruptedException {
        UdsDtc u0100 = new UdsDtc(0xC10087, 0x09);
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.uds()
                .on("19 04 C1 00 87 FF", "59 04 C1 00 87 09")
                .on("19 06 C1 00 87 FF", "7F 19 31");
        assertTrue(new UdsDiagnosticService(ch, time).readFreezeFrame(u0100).isSuccess());
        assertEquals(List.of("19 04 C1 00 87 FF", "19 06 C1 00 87 FF"), ch.requestsHex());
    }

    @Test
    @DisplayName("KWP freeze frame is NotAvailable and sends nothing (12 xx can't name a DTC)")
    void kwpNotAvailable() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.kwp();
        DiagResult<FreezeFrame> r = new KwpDiagnosticService(ch, time).readFreezeFrame(new KwpDtc(0x412C, 0xA3));
        DiagResult.NotAvailable<?> n = assertInstanceOf(DiagResult.NotAvailable.class, r);
        assertEquals(KwpDiagnosticService.FREEZE_FRAME_NOT_AVAILABLE, n.reason());
        assertEquals(List.of(), ch.calls());
    }

    @Test
    @DisplayName("a DTC of the other protocol is a programming error, nothing sent")
    void wrongProtocolDtc() {
        FakeDiagnosticChannel uds = FakeDiagnosticChannel.uds();
        FakeDiagnosticChannel kwp = FakeDiagnosticChannel.kwp();
        assertThrows(IllegalArgumentException.class,
                () -> new UdsDiagnosticService(uds, time).readFreezeFrame(new KwpDtc(0x412C, 0xA3)));
        assertThrows(IllegalArgumentException.class,
                () -> new KwpDiagnosticService(kwp, time).readFreezeFrame(P0401));
        assertEquals(List.of(), uds.calls());
        assertEquals(List.of(), kwp.calls());
    }
}
