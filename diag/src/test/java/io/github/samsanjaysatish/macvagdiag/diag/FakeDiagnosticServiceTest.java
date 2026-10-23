package io.github.samsanjaysatish.macvagdiag.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.transport.ApplicationProtocol;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The test-fixture fake that {@code scan} will use. */
class FakeDiagnosticServiceTest {

    @Test
    @DisplayName("scripted results come back in order, calls are recorded, unscripted calls throw")
    void scripted() {
        UdsDtc p0401 = new UdsDtc(0x040100, 0x2F);
        FakeDiagnosticService f = FakeDiagnosticService.uds()
                .onStartSession(FakeDiagnosticService.ok(RawBytes.ofHex("50 03")))
                .onReadDtcs(FakeDiagnosticService.ok(FakeDiagnosticService.dtcList(p0401)))
                .onReadDtcs(new DiagResult.TimedOut<>(Duration.ofSeconds(1), 0))
                .onReadFreezeFrame(new DiagResult.Busy<>(NegativeResponse.of(0x19, 0x21)))
                .onReadIdentification(FakeDiagnosticService.ok(FakeDiagnosticService.identification(
                        ApplicationProtocol.UDS, Map.of(EcuIdentification.Field.PART_NUMBER, "03L906018JL"))))
                .onTesterPresent(FakeDiagnosticService.ok(RawBytes.ofHex("7E 00")));
        assertEquals(ApplicationProtocol.UDS, f.protocol());
        assertTrue(f.startSession(DiagnosticSession.UDS_EXTENDED).isSuccess());
        assertEquals(List.of(p0401), f.readDtcs().valueIfSuccess().orElseThrow().dtcs());
        assertTrue(f.readDtcs() instanceof DiagResult.TimedOut<DtcList>);
        assertTrue(f.readFreezeFrame(p0401).retryable());
        assertEquals("03L906018JL", f.readIdentification().valueIfSuccess().orElseThrow().partNumber().orElseThrow());
        assertTrue(f.testerPresent().isSuccess());
        assertTrue(f.allScriptUsed());
        assertThrows(IllegalStateException.class, f::readDtcs);
        assertEquals(List.of(
                new FakeDiagnosticService.Call(FakeDiagnosticService.Operation.START_SESSION, DiagnosticSession.UDS_EXTENDED),
                new FakeDiagnosticService.Call(FakeDiagnosticService.Operation.READ_DTCS, null),
                new FakeDiagnosticService.Call(FakeDiagnosticService.Operation.READ_DTCS, null),
                new FakeDiagnosticService.Call(FakeDiagnosticService.Operation.READ_FREEZE_FRAME, p0401),
                new FakeDiagnosticService.Call(FakeDiagnosticService.Operation.READ_IDENTIFICATION, null),
                new FakeDiagnosticService.Call(FakeDiagnosticService.Operation.TESTER_PRESENT, null),
                new FakeDiagnosticService.Call(FakeDiagnosticService.Operation.READ_DTCS, null)), f.calls());
    }

    @Test
    @DisplayName("like the real services, a session or DTC of the other protocol throws")
    void protocolChecks() {
        FakeDiagnosticService f = FakeDiagnosticService.kwp();
        assertThrows(IllegalArgumentException.class, () -> f.startSession(DiagnosticSession.UDS_DEFAULT));
        assertThrows(IllegalArgumentException.class, () -> f.readFreezeFrame(new UdsDtc(0x040100, 0x2F)));
        assertFalse(f.calls().iterator().hasNext());
        KwpDtc d = new KwpDtc(0x412C, 0xA3);
        f.onReadFreezeFrame(new DiagResult.NotAvailable<>("KWP"));
        assertTrue(f.readFreezeFrame(d) instanceof DiagResult.NotAvailable<FreezeFrame>);
        assertEquals(FakeDiagnosticService.freezeFrame(d).dtc(), d);
    }
}
