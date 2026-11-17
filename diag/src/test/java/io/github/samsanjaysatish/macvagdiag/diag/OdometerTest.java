package io.github.samsanjaysatish.macvagdiag.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.github.samsanjaysatish.macvagdiag.can.FakeTimeSource;
import io.github.samsanjaysatish.macvagdiag.transport.FakeDiagnosticChannel;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** {@link DiagnosticService#readOdometer()}: {@code 22 22 03} → {@code 62 22 03 [hi] [lo]}, 10 km per count. */
class OdometerTest {

    private final FakeTimeSource time = FakeTimeSource.autoAdvancing();

    @Test
    @DisplayName("UDS 22 22 03 -> 62 22 03 8F 14 (17 Instruments, 2026-11-13): 366280 km, one request")
    void udsCapturedValue() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.uds().on("22 22 03", "62 22 03 8F 14");
        DiagResult<Odometer> r = new UdsDiagnosticService(ch, time).readOdometer();
        assertInstanceOf(DiagResult.Success.class, r);
        assertEquals(new Odometer(366280, RawBytes.ofHex("62 22 03 8F 14")), r.valueIfSuccess().orElseThrow());
        assertEquals(List.of("22 22 03"), ch.requestsHex());
    }

    @Test
    @DisplayName("KWP 22 22 03 on a TP 2.0 channel -> 62 22 03 00 01: 10 km")
    void kwpSameRequest() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.kwp().on("22 22 03", "62 22 03 00 01");
        assertEquals(10, new KwpDiagnosticService(ch, time).readOdometer().valueIfSuccess().orElseThrow().kilometres());
        assertEquals(List.of("22 22 03"), ch.requestsHex());
    }

    @Test
    @DisplayName("62 22 03 00 00 -> 0 km; 62 22 03 FF FF -> 655350 km (kept as read)")
    void bounds() {
        assertEquals(0, Odometer.parse(RawBytes.ofHex("62 22 03 00 00")).valueIfSuccess().orElseThrow().kilometres());
        assertEquals(655350, Odometer.parse(RawBytes.ofHex("62 22 03 FF FF")).valueIfSuccess().orElseThrow().kilometres());
    }

    @Test
    @DisplayName("7F 22 31 (a module without the DID): Negative, nothing else sent")
    void refused() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.uds().on("22 22 03", "7F 22 31");
        DiagResult.Negative<?> n = assertInstanceOf(DiagResult.Negative.class, new UdsDiagnosticService(ch, time).readOdometer());
        assertEquals(0x31, n.response().code());
        assertEquals(List.of("22 22 03"), ch.requestsHex());
    }

    @ParameterizedTest(name = "{0} is malformed, kept raw")
    @ValueSource(strings = {"62 22 03 8F", "62 22 03 8F 14 00", "62 22 04 8F 14", "62 23 03 8F 14", "62 22 03"})
    void malformed(String response) {
        DiagResult.Malformed<?> m = assertInstanceOf(DiagResult.Malformed.class, Odometer.parse(RawBytes.ofHex(response)));
        assertEquals(RawBytes.ofHex(response), m.response());
    }
}
