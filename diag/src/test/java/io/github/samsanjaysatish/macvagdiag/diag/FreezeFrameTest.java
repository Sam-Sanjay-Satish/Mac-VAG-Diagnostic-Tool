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

/**
 * UDS freeze frame: {@code 19 06 [DTC] FF} only (VCDS never sent {@code 19 04} on the 2026-11-13 capture), kept raw.
 * KWP freeze frames: {@link KwpFreezeFrameTest}, {@link KwpCaptureTest}.
 */
class FreezeFrameTest {

    private static final UdsDtc P0401 = new UdsDtc(0x040100, 0x2F);
    private static final String EXTENDED = "59 06 04 01 00 2F 01 05 02 FF 03 00 12 34";

    private final FakeTimeSource time = new FakeTimeSource();

    private static FreezeFrame ok(DiagResult<FreezeFrame> r) {
        assertTrue(r.isSuccess(), r.message());
        return r.valueIfSuccess().orElseThrow();
    }

    @Test
    @DisplayName("19 06 04 01 00 FF only, no 19 04: kept raw with its request as ID, response kept whole")
    void extendedDataOnly() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.uds().on("19 06 04 01 00 FF", EXTENDED);
        FreezeFrame ff = ok(new UdsDiagnosticService(ch, time).readFreezeFrame(P0401));
        assertEquals(List.of("19 06 04 01 00 FF"), ch.requestsHex());
        assertEquals(1, UdsDiagnosticService.FREEZE_FRAME_REQUESTS);
        assertEquals(P0401, ff.dtc());
        assertEquals(List.of(new RawRecord("19 06 04 01 00 FF", RawBytes.ofHex("01 05 02 FF 03 00 12 34"))), ff.records());
        assertEquals(List.of(RawBytes.ofHex(EXTENDED)), ff.responses());
        assertEquals(List.of(), ff.fields(), "the record layout is still a hypothesis");
        assertEquals(List.of(), ff.notes());
    }

    @Test
    @DisplayName("19 06 -> 7F 19 31: the Negative is returned")
    void refused() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.uds().on("19 06 04 01 00 FF", "7F 19 31");
        DiagResult.Negative<?> n = assertInstanceOf(DiagResult.Negative.class,
                new UdsDiagnosticService(ch, time).readFreezeFrame(P0401));
        assertEquals(0x31, n.response().code());
    }

    @Test
    @DisplayName("19 06 -> 7F 19 21 busy: Busy, sent once")
    void busy() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.uds().on("19 06 04 01 00 FF", "7F 19 21");
        assertInstanceOf(DiagResult.Busy.class, new UdsDiagnosticService(ch, time).readFreezeFrame(P0401));
        assertEquals(List.of("19 06 04 01 00 FF"), ch.requestsHex());
    }

    @Test
    @DisplayName("59 06 04 01 00 2F with no records, and 59 06 for another DTC: notes, nothing dropped")
    void emptyAndEchoMismatch() throws InterruptedException {
        FreezeFrame empty = ok(new UdsDiagnosticService(
                FakeDiagnosticChannel.uds().on("19 06 04 01 00 FF", "59 06 04 01 00 2F"), time).readFreezeFrame(P0401));
        assertEquals(List.of(), empty.records());
        assertEquals(1, empty.notes().size());
        assertTrue(empty.notes().getFirst().contains("no records stored"));

        FreezeFrame other = ok(new UdsDiagnosticService(
                FakeDiagnosticChannel.uds().on("19 06 04 01 00 FF", "59 06 03 00 00 2F 01 05"), time).readFreezeFrame(P0401));
        assertEquals(List.of(new RawRecord("19 06 04 01 00 FF", RawBytes.ofHex("01 05"))), other.records());
        assertTrue(other.notes().getFirst().contains("answered for DTC 03 00 00"), other.notes().toString());
        assertEquals(1, other.responses().size());
    }

    @Test
    @DisplayName("truncated 59 06 04 01 header, and a wrong echo 59 02 ...: whole response kept raw with a note")
    void badHeaderKeptWhole() throws InterruptedException {
        for (String bad : List.of("59 06 04 01", "59 02 04 01 00 2F 01")) {
            FreezeFrame ff = ok(new UdsDiagnosticService(
                    FakeDiagnosticChannel.uds().on("19 06 04 01 00 FF", bad), time).readFreezeFrame(P0401));
            assertEquals(List.of(new RawRecord("19 06 04 01 00 FF (whole response, unexpected header)", RawBytes.ofHex(bad))),
                    ff.records());
            assertEquals(1, ff.notes().size());
        }
    }

    @Test
    @DisplayName("19 06 with a 24-bit DTC 90 3E 12 (17's B103E on the capture) is built byte for byte")
    void dtcBytes() throws InterruptedException {
        UdsDtc b103e = new UdsDtc(0x903E12, 0x08);
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.uds()
                .on("19 06 90 3E 12 FF", "59 06 90 3E 12 08 01 02 8C 02 A3 05 7A 8E 00 01 FF BF 2E 8E");
        assertTrue(new UdsDiagnosticService(ch, time).readFreezeFrame(b103e).isSuccess());
        assertEquals(List.of("19 06 90 3E 12 FF"), ch.requestsHex());
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
