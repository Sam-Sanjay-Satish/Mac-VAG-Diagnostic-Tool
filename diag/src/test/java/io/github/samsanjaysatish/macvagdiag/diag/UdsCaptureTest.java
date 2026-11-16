package io.github.samsanjaysatish.macvagdiag.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.can.FakeTimeSource;
import io.github.samsanjaysatish.macvagdiag.transport.FakeDiagnosticChannel;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The UDS fault-code reads of the 2026-11-13 capture, against the VCDS Auto-Scan log of the same session. */
class UdsCaptureTest {

    private final FakeTimeSource time = new FakeTimeSource();

    private DtcList read(String response) throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.uds().on("19 02 AE", response);
        DiagResult<DtcList> r = new UdsDiagnosticService(ch, time).readDtcs();
        assertEquals(List.of("19 02 AE"), ch.requestsHex());
        assertTrue(r.isSuccess(), r.message());
        return r.valueIfSuccess().orElseThrow();
    }

    @Test
    @DisplayName("17 Instruments: 9453074 B103E and 9459995 B1059, status 08 'Intermittent - Confirmed - Tested Since Memory Clear'")
    void instruments() throws InterruptedException {
        DtcList l = read(CaptureBytes.INSTRUMENTS_DTCS);
        assertTrue(l.complete());
        assertEquals(0x99, l.statusAvailabilityMask().orElseThrow());
        List<UdsDtc> dtcs = l.dtcs().stream().map(UdsDtc.class::cast).toList();
        assertEquals(List.of("9453074", "9459995"), dtcs.stream().map(UdsDtc::vagNumber).toList());
        assertEquals(List.of("B103E", "B1059"), dtcs.stream().map(UdsDtc::displayCode).toList());
        for (UdsDtc d : dtcs) {
            assertEquals("Intermittent - Confirmed - Tested Since Memory Clear", d.status().vcdsText());
        }
        // VCDS prints 1B as the failure type of 90 3E 12 (from its database); ours stays the raw byte.
        assertEquals(0x12, dtcs.get(0).failureType());
        assertEquals(0x1B, dtcs.get(1).failureType());
    }

    @Test
    @DisplayName("77 Telephone: 9457427, 9457171, 0022, status 09 'Confirmed - Tested Since Memory Clear'")
    void telephone() throws InterruptedException {
        DtcList l = read(CaptureBytes.TELEPHONE_DTCS);
        List<UdsDtc> dtcs = l.dtcs().stream().map(UdsDtc.class::cast).toList();
        assertEquals(List.of("9457427", "9457171", "0022"), dtcs.stream().map(UdsDtc::vagNumber).toList());
        assertEquals(List.of("B104F", "B104E"), dtcs.subList(0, 2).stream().map(UdsDtc::displayCode).toList());
        assertEquals(List.of(0x13, 0x13, 0x16), dtcs.stream().map(UdsDtc::failureType).toList());
        for (UdsDtc d : dtcs) {
            assertEquals("Confirmed - Tested Since Memory Clear", d.status().vcdsText());
        }
    }

    @Test
    @DisplayName("modules without faults answer 59 02 19 / 59 02 99 with no records")
    void noFaults() throws InterruptedException {
        assertEquals(List.of(), read("59 02 19").dtcs());
        assertEquals(List.of(), read("59 02 99").dtcs());
    }
}
