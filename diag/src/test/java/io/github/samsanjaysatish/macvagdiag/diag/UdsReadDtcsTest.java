package io.github.samsanjaysatish.macvagdiag.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.can.FakeTimeSource;
import io.github.samsanjaysatish.macvagdiag.transport.FakeDiagnosticChannel;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** UDS {@code 19 02 FF} → {@code 59 02 [mask] ([DTC 3] [status])...}, hand-written bytes. */
class UdsReadDtcsTest {

    private final FakeTimeSource time = new FakeTimeSource();

    private DiagResult<DtcList> run(String response) throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.uds().on("19 02 FF", response);
        DiagResult<DtcList> r = new UdsDiagnosticService(ch, time).readDtcs();
        assertEquals(List.of("19 02 FF"), ch.requestsHex());
        return r;
    }

    private DtcList read(String response) throws InterruptedException {
        DiagResult<DtcList> r = run(response);
        assertTrue(r.isSuccess(), r.message());
        return r.valueIfSuccess().orElseThrow();
    }

    @Test
    @DisplayName("19 02 FF -> 59 02 FF with zero DTCs")
    void zero() throws InterruptedException {
        DtcList l = read("59 02 FF");
        assertEquals(List.of(), l.dtcs());
        assertEquals(OptionalInt.of(0xFF), l.statusAvailabilityMask());
        assertTrue(l.complete());
    }

    @Test
    @DisplayName("19 02 FF -> 59 02 FF 04 01 00 2F with one DTC: P0401")
    void one() throws InterruptedException {
        DtcList l = read("59 02 FF 04 01 00 2F");
        assertEquals(List.of(new UdsDtc(0x040100, 0x2F)), l.dtcs());
        assertEquals("P0401", l.dtcs().getFirst().displayCode());
        assertEquals(RawBytes.ofHex("59 02 FF 04 01 00 2F"), l.response());
        assertTrue(l.complete());
    }

    @Test
    @DisplayName("19 02 FF with four DTCs: P, C, B and U codes in order")
    void many() throws InterruptedException {
        DtcList l = read("59 02 FF 04 01 00 2F 44 20 11 09 90 12 00 08 C1 00 87 24");
        assertEquals(List.of("P0401", "C0420", "B1012", "U0100"), l.dtcs().stream().map(Dtc::displayCode).toList());
        assertEquals(List.of(0x2F, 0x09, 0x08, 0x24), l.dtcs().stream().map(d -> d.status().raw()).toList());
        assertEquals(0x87, ((UdsDtc) l.dtcs().get(3)).failureType());
    }

    @Test
    @DisplayName("truncated 59 02 FF 04 01 00 2F 03 00: one DTC, 03 00 kept undecoded with a warning")
    void truncated() throws InterruptedException {
        DtcList l = read("59 02 FF 04 01 00 2F 03 00");
        assertEquals(List.of(new UdsDtc(0x040100, 0x2F)), l.dtcs());
        assertEquals(RawBytes.ofHex("03 00"), l.undecoded());
        assertEquals(1, l.warnings().size());
        assertTrue(l.warnings().getFirst().contains("03 00"));
        assertFalse(l.complete());
    }

    @Test
    @DisplayName("status bits outside the availability mask (59 02 0F ... FF) are kept as received")
    void unknownBitsKept() throws InterruptedException {
        DtcList l = read("59 02 0F 04 01 00 FF");
        assertEquals(OptionalInt.of(0x0F), l.statusAvailabilityMask());
        assertEquals(0xFF, l.dtcs().getFirst().status().raw());
        assertEquals(8, l.dtcs().getFirst().status().flagNames().size());
    }

    @Test
    @DisplayName("59 02 (no mask), 59 01 FF 00 (wrong echo) and 62 F1 90 (wrong service) are malformed, raw kept")
    void malformed() throws InterruptedException {
        for (String bad : new String[] {"59 02", "59 01 FF 00", "62 F1 90"}) {
            DiagResult.Malformed<?> m = assertInstanceOf(DiagResult.Malformed.class, run(bad));
            assertEquals(RawBytes.ofHex(bad), m.response());
        }
    }
}
