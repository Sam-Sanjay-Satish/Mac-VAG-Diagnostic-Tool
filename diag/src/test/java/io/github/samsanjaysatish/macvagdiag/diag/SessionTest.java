package io.github.samsanjaysatish.macvagdiag.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.samsanjaysatish.macvagdiag.can.FakeTimeSource;
import io.github.samsanjaysatish.macvagdiag.transport.ApplicationProtocol;
import io.github.samsanjaysatish.macvagdiag.transport.FakeDiagnosticChannel;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Session and Tester Present requests that {@code scan}'s session layer calls; plus service construction. */
class SessionTest {

    private final FakeTimeSource time = new FakeTimeSource();

    @Test
    @DisplayName("KWP 10 89 -> 50 89")
    void kwpSession() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.kwp().on("10 89", "50 89");
        DiagResult<RawBytes> r = new KwpDiagnosticService(ch, time).startSession(DiagnosticSession.KWP_VW_DIAGNOSTIC);
        assertEquals(new DiagResult.Success<>(RawBytes.ofHex("50 89")), r);
        assertEquals(List.of("10 89"), ch.requestsHex());
    }

    @Test
    @DisplayName("UDS 10 03 -> 50 03 00 32 01 F4 (P2 timings kept raw); 10 01 -> 50 01 00 32 01 F4")
    void udsSessions() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.uds()
                .on("10 03", "50 03 00 32 01 F4")
                .on("10 01", "50 01 00 32 01 F4");
        UdsDiagnosticService s = new UdsDiagnosticService(ch, time);
        assertEquals(new DiagResult.Success<>(RawBytes.ofHex("50 03 00 32 01 F4")), s.startSession(DiagnosticSession.UDS_EXTENDED));
        assertEquals(new DiagResult.Success<>(RawBytes.ofHex("50 01 00 32 01 F4")), s.startSession(DiagnosticSession.UDS_DEFAULT));
        assertEquals(List.of("10 03", "10 01"), ch.requestsHex());
    }

    @Test
    @DisplayName("session answers that don't echo the session byte (50 81, 50 01 to 10 03, 50) are malformed")
    void sessionEcho() throws InterruptedException {
        FakeDiagnosticChannel kwp = FakeDiagnosticChannel.kwp().on("10 89", "50 81").on("10 89", "50");
        KwpDiagnosticService k = new KwpDiagnosticService(kwp, time);
        assertInstanceOf(DiagResult.Malformed.class, k.startSession(DiagnosticSession.KWP_VW_DIAGNOSTIC));
        assertInstanceOf(DiagResult.Malformed.class, k.startSession(DiagnosticSession.KWP_VW_DIAGNOSTIC));
        FakeDiagnosticChannel uds = FakeDiagnosticChannel.uds().on("10 03", "50 01 00 32 01 F4");
        assertInstanceOf(DiagResult.Malformed.class, new UdsDiagnosticService(uds, time).startSession(DiagnosticSession.UDS_EXTENDED));
    }

    @Test
    @DisplayName("10 03 -> 7F 10 22 conditions not correct is a typed Negative")
    void sessionRefused() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.uds().on("10 03", "7F 10 22");
        DiagResult.Negative<?> n = assertInstanceOf(DiagResult.Negative.class,
                new UdsDiagnosticService(ch, time).startSession(DiagnosticSession.UDS_EXTENDED));
        assertEquals(Nrc.CONDITIONS_NOT_CORRECT, n.response().nrc().orElseThrow());
    }

    @Test
    @DisplayName("a session of the other protocol is refused before anything is sent")
    void wrongProtocolSession() {
        FakeDiagnosticChannel kwp = FakeDiagnosticChannel.kwp();
        FakeDiagnosticChannel uds = FakeDiagnosticChannel.uds();
        assertThrows(IllegalArgumentException.class,
                () -> new KwpDiagnosticService(kwp, time).startSession(DiagnosticSession.UDS_EXTENDED));
        assertThrows(IllegalArgumentException.class,
                () -> new KwpDiagnosticService(kwp, time).startSession(DiagnosticSession.UDS_DEFAULT));
        assertThrows(IllegalArgumentException.class,
                () -> new UdsDiagnosticService(uds, time).startSession(DiagnosticSession.KWP_VW_DIAGNOSTIC));
        assertEquals(List.of(), kwp.calls());
        assertEquals(List.of(), uds.calls());
    }

    @Test
    @DisplayName("only the three allowlisted sessions exist: 10 89 (KWP), 10 01 and 10 03 (UDS)")
    void sessionsAreClosedSet() {
        assertEquals(List.of(DiagnosticSession.KWP_VW_DIAGNOSTIC, DiagnosticSession.UDS_DEFAULT, DiagnosticSession.UDS_EXTENDED),
                List.of(DiagnosticSession.values()));
        assertEquals(ApplicationProtocol.KWP2000, DiagnosticSession.KWP_VW_DIAGNOSTIC.protocol());
        assertEquals(ApplicationProtocol.UDS, DiagnosticSession.UDS_DEFAULT.protocol());
        assertEquals(ApplicationProtocol.UDS, DiagnosticSession.UDS_EXTENDED.protocol());
    }

    @Test
    @DisplayName("tester present: KWP 3E -> 7E, UDS 3E 00 -> 7E 00; UDS 7E 80 is malformed")
    void testerPresent() throws InterruptedException {
        FakeDiagnosticChannel kwp = FakeDiagnosticChannel.kwp().on("3E", "7E");
        assertEquals(new DiagResult.Success<>(RawBytes.ofHex("7E")), new KwpDiagnosticService(kwp, time).testerPresent());
        assertEquals(List.of("3E"), kwp.requestsHex());
        FakeDiagnosticChannel uds = FakeDiagnosticChannel.uds().on("3E 00", "7E 00").on("3E 00", "7E 80");
        UdsDiagnosticService s = new UdsDiagnosticService(uds, time);
        assertEquals(new DiagResult.Success<>(RawBytes.ofHex("7E 00")), s.testerPresent());
        assertInstanceOf(DiagResult.Malformed.class, s.testerPresent());
        assertEquals(List.of("3E 00", "3E 00"), uds.requestsHex());
    }

    @Test
    @DisplayName("services refuse a channel of the other protocol; forChannel picks by protocol")
    void construction() {
        assertThrows(IllegalArgumentException.class, () -> new KwpDiagnosticService(FakeDiagnosticChannel.uds(), time));
        assertThrows(IllegalArgumentException.class, () -> new UdsDiagnosticService(FakeDiagnosticChannel.kwp(), time));
        assertInstanceOf(KwpDiagnosticService.class, DiagnosticService.forChannel(FakeDiagnosticChannel.kwp(), time));
        assertInstanceOf(UdsDiagnosticService.class, DiagnosticService.forChannel(FakeDiagnosticChannel.uds(), time));
        assertEquals(ApplicationProtocol.UDS, DiagnosticService.forChannel(FakeDiagnosticChannel.uds(), time).protocol());
    }

    @Test
    @DisplayName("custom timings reach the channel: request timeout 250 ms")
    void customTimings() throws InterruptedException {
        DiagTimings t = new DiagTimings(java.time.Duration.ofMillis(250), DiagTimings.RESPONSE_PENDING_TIMEOUT, 5,
                DiagTimings.MAX_TOTAL_PENDING_WAIT);
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.kwp().on("3E", "7E");
        DiagnosticService.forChannel(ch, time, t).testerPresent();
        assertEquals(java.time.Duration.ofMillis(250), ch.calls().getFirst().timeout());
    }
}
