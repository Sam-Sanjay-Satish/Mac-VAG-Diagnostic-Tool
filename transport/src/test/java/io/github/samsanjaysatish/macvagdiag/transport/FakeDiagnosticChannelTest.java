package io.github.samsanjaysatish.macvagdiag.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.can.Hex;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The test-fixture fake that {@code diag} will use. */
class FakeDiagnosticChannelTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    @Test
    @DisplayName("scripted response, then 7F 19 78 pending followed by the real answer via awaitResponse")
    void scriptedResponsesAndPending() {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.uds()
                .on("22 F1 90", "62 F1 90 54 45 53 54")
                .on("19 02 FF", "7F 19 78", "59 02 FF 04 01 00 2F");

        assertEquals(new Response.Received(Hex.parse("62 F1 90 54 45 53 54")), ch.request(Hex.parse("22 F1 90"), TIMEOUT));
        assertEquals(new Response.Received(Hex.parse("7F 19 78")), ch.request(Hex.parse("19 02 FF"), TIMEOUT));
        assertEquals(new Response.Received(Hex.parse("59 02 FF 04 01 00 2F")), ch.awaitResponse(Duration.ofSeconds(5)));
        assertEquals(new Response.TimedOut(Duration.ofSeconds(5)), ch.awaitResponse(Duration.ofSeconds(5)));
        assertTrue(ch.allScriptUsed());
        assertEquals(List.of("22 F1 90", "19 02 FF"), ch.requestsHex());
        assertEquals(new FakeDiagnosticChannel.Call(FakeDiagnosticChannel.Call.Kind.AWAIT, new byte[0], Duration.ofSeconds(5)),
                ch.calls().get(2), "the timeout diag passed is recorded");
    }

    @Test
    @DisplayName("applies the real guard: KWP 21 01 and UDS 27 01 throw, and are not recorded")
    void appliesTheGuard() {
        FakeDiagnosticChannel kwp = FakeDiagnosticChannel.kwp().on("21 01", "61 01");
        assertThrows(ForbiddenRequestException.class, () -> kwp.request(Hex.parse("21 01"), TIMEOUT));
        FakeDiagnosticChannel uds = FakeDiagnosticChannel.uds();
        assertThrows(ForbiddenRequestException.class, () -> uds.request(Hex.parse("27 01"), TIMEOUT));
        assertThrows(ForbiddenRequestException.class, () -> uds.request(Hex.parse("10 89"), TIMEOUT),
                "KWP session bytes refused on a UDS channel");
        assertEquals(List.of(), kwp.calls());
        assertEquals(List.of(), uds.calls());
    }

    @Test
    @DisplayName("timeouts, failures, repeated scripts, unscripted requests, keep-alive and close")
    void otherBehaviour() {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.kwp()
                .onTimeout("1A 9B")
                .on("1A 9B", new Response.Failed(Response.Failed.Kind.DISCONNECTED_BY_MODULE, "A8"))
                .on("1A 9B", "5A 9B 30 33");
        assertEquals(ApplicationProtocol.KWP2000, ch.protocol());
        assertEquals(new Response.TimedOut(TIMEOUT), ch.request(Hex.parse("1A 9B"), TIMEOUT));
        assertEquals(new Response.Failed(Response.Failed.Kind.DISCONNECTED_BY_MODULE, "A8"),
                ch.request(Hex.parse("1A 9B"), TIMEOUT));
        assertEquals(new Response.Received(Hex.parse("5A 9B 30 33")), ch.request(Hex.parse("1A 9B"), TIMEOUT));
        assertThrows(IllegalStateException.class, () -> ch.request(Hex.parse("1A 9B"), TIMEOUT));
        assertTrue(ch.keepAlive());
        ch.keepAliveResult(false);
        assertFalse(ch.keepAlive());
        assertFalse(ch.isOpen());
        assertEquals(Response.Failed.Kind.CHANNEL_CLOSED,
                ((Response.Failed) ch.request(Hex.parse("10 89"), TIMEOUT)).kind());
    }
}
