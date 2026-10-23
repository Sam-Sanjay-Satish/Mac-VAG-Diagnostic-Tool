package io.github.samsanjaysatish.macvagdiag.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.can.FakeTimeSource;
import io.github.samsanjaysatish.macvagdiag.transport.FakeDiagnosticChannel;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** KWP {@code 18 02} → {@code 58 [count] ([hi] [lo] [status])...}, hand-written bytes. */
class KwpReadDtcsTest {

    private final FakeTimeSource time = new FakeTimeSource();

    private DtcList read(String response) throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.kwp().on("18 02", response);
        DiagResult<DtcList> r = new KwpDiagnosticService(ch, time).readDtcs();
        assertEquals(List.of("18 02"), ch.requestsHex());
        assertTrue(r.isSuccess(), r.message());
        return r.valueIfSuccess().orElseThrow();
    }

    @Test
    @DisplayName("18 02 -> 58 00 with zero DTCs")
    void zero() throws InterruptedException {
        DtcList l = read("58 00");
        assertEquals(List.of(), l.dtcs());
        assertTrue(l.complete());
        assertEquals(RawBytes.ofHex("58 00"), l.response());
        assertTrue(l.statusAvailabilityMask().isEmpty());
    }

    @Test
    @DisplayName("18 02 -> 58 01 41 2C A3 with one DTC: 16684, status A3 kept raw")
    void one() throws InterruptedException {
        DtcList l = read("58 01 41 2C A3");
        assertEquals(List.of(new KwpDtc(0x412C, 0xA3)), l.dtcs());
        KwpDtc d = (KwpDtc) l.dtcs().getFirst();
        assertEquals("16684", d.displayCode());
        assertEquals(0xA3, d.status().raw());
        assertEquals(RawBytes.ofHex("41 2C A3"), d.record());
        assertTrue(l.complete());
    }

    @Test
    @DisplayName("18 02 -> 58 03 with three DTCs, in order")
    void many() throws InterruptedException {
        DtcList l = read("58 03 41 2C A3 46 6E 23 00 01 00");
        assertEquals(List.of(new KwpDtc(0x412C, 0xA3), new KwpDtc(0x466E, 0x23), new KwpDtc(0x0001, 0x00)), l.dtcs());
        assertEquals(List.of("16684", "18030", "00001"), l.dtcs().stream().map(Dtc::displayCode).toList());
        assertTrue(l.complete());
    }

    @Test
    @DisplayName("truncated 58 02 41 2C A3 46 6E: one DTC decoded, 46 6E kept undecoded, two warnings")
    void truncated() throws InterruptedException {
        DtcList l = read("58 02 41 2C A3 46 6E");
        assertEquals(List.of(new KwpDtc(0x412C, 0xA3)), l.dtcs());
        assertEquals(RawBytes.ofHex("46 6E"), l.undecoded());
        assertEquals(RawBytes.ofHex("58 02 41 2C A3 46 6E"), l.response());
        assertEquals(2, l.warnings().size(), l.warnings().toString());
        assertTrue(l.warnings().get(0).contains("46 6E"));
        assertTrue(l.warnings().get(1).contains("says 2"));
        assertFalse(l.complete());
    }

    @Test
    @DisplayName("count mismatch 58 05 41 2C A3: the DTC that is there is decoded, with a warning")
    void countMismatch() throws InterruptedException {
        DtcList l = read("58 05 41 2C A3");
        assertEquals(List.of(new KwpDtc(0x412C, 0xA3)), l.dtcs());
        assertEquals(List.of("count byte says 5 DTC(s), the response holds 1"), l.warnings());
        assertTrue(l.undecoded().isEmpty());
        assertFalse(l.complete());
    }

    @Test
    @DisplayName("58 alone (no count) and a 59 answer are malformed, raw kept")
    void malformed() throws InterruptedException {
        for (String bad : new String[] {"58", "59 02 FF"}) {
            FakeDiagnosticChannel ch = FakeDiagnosticChannel.kwp().on("18 02", bad);
            DiagResult<DtcList> r = new KwpDiagnosticService(ch, time).readDtcs();
            DiagResult.Malformed<?> m = assertInstanceOf(DiagResult.Malformed.class, r);
            assertEquals(RawBytes.ofHex(bad), m.response());
        }
    }

    @Test
    @DisplayName("a VagCodeMapping, when one exists, fills obdCode (test mapping, not real data)")
    void mappingHook() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.kwp().on("18 02", "58 02 41 2C A3 00 01 00");
        VagCodeMapping testMapping = code -> code == 0x412C ? Optional.of("TEST-ONLY") : Optional.empty();
        DiagResult<DtcList> r = new KwpDiagnosticService(ch, time, DiagTimings.DEFAULTS, testMapping).readDtcs();
        List<Dtc> dtcs = r.valueIfSuccess().orElseThrow().dtcs();
        assertEquals(Optional.of("TEST-ONLY"), ((KwpDtc) dtcs.get(0)).obdCode());
        assertEquals(Optional.empty(), ((KwpDtc) dtcs.get(1)).obdCode());
    }
}
