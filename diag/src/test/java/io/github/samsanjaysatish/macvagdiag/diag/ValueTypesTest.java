package io.github.samsanjaysatish.macvagdiag.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.transport.ApplicationProtocol;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** RawBytes, DiagTimings, Nrc and the result records' defensive copies. */
class ValueTypesTest {

    @Test
    @DisplayName("RawBytes copies in and out, prints as bus hex, compares by content")
    void rawBytes() {
        byte[] b = {0x1A, 0x2B};
        RawBytes r = new RawBytes(b);
        b[0] = 0;
        assertEquals("1A 2B", r.hex());
        r.bytes()[1] = 0;
        assertEquals(0x2B, r.byteAt(1));
        assertEquals(RawBytes.ofHex("1A 2B"), r);
        assertEquals(RawBytes.ofHex("1A2B").hashCode(), r.hashCode());
        assertEquals("1A 2B", r.toString());
        assertTrue(RawBytes.EMPTY.isEmpty());
        assertEquals(RawBytes.ofHex("2B"), RawBytes.slice(r.bytes(), 1, 2));
    }

    @Test
    @DisplayName("asciiText: trims 00/20/FF padding, refuses non-printable bytes")
    void asciiText() {
        assertEquals(Optional.of("03L906018JL"), RawBytes.ofHex(Ascii.hex("03L906018JL") + " 20 20 00 FF").asciiText());
        assertEquals(Optional.of("A B"), RawBytes.ofHex("41 20 42").asciiText());
        assertEquals(Optional.empty(), RawBytes.ofHex("41 01 42").asciiText());
        assertEquals(Optional.empty(), RawBytes.ofHex("41 80").asciiText());
        assertEquals(Optional.empty(), RawBytes.ofHex("00 20 FF").asciiText());
        assertEquals(Optional.empty(), RawBytes.EMPTY.asciiText());
    }

    @Test
    @DisplayName("DiagTimings: defaults as documented; zero, negative and above-ceiling values refused")
    void timings() {
        assertEquals(DiagTimings.DEFAULT_REQUEST_TIMEOUT, DiagTimings.DEFAULTS.requestTimeout());
        assertEquals(DiagTimings.RESPONSE_PENDING_TIMEOUT, DiagTimings.DEFAULTS.pendingTimeout());
        assertEquals(DiagTimings.MAX_RESPONSE_PENDING, DiagTimings.DEFAULTS.maxPendingResponses());
        assertEquals(DiagTimings.MAX_TOTAL_PENDING_WAIT, DiagTimings.DEFAULTS.maxTotalPendingWait());
        Duration s = Duration.ofSeconds(1);
        assertThrows(IllegalArgumentException.class, () -> new DiagTimings(Duration.ZERO, s, 1, s));
        assertThrows(IllegalArgumentException.class, () -> new DiagTimings(s, Duration.ofMillis(-1), 1, s));
        assertThrows(IllegalArgumentException.class, () -> new DiagTimings(s, s, 1, Duration.ofMinutes(3)));
        assertThrows(IllegalArgumentException.class, () -> new DiagTimings(s, s, -1, s));
        assertThrows(IllegalArgumentException.class, () -> new DiagTimings(s, s, DiagTimings.MAX_RESPONSE_PENDING_CEILING + 1, s));
        assertThrows(NullPointerException.class, () -> new DiagTimings(null, s, 1, s));
        new DiagTimings(s, s, 0, s); // zero 78s allowed: the first 78 then gives PendingLimitExceeded
    }

    @Test
    @DisplayName("Nrc codes are unique and look-up works; unknown codes give empty")
    void nrcTable() {
        assertEquals(Nrc.values().length, java.util.Arrays.stream(Nrc.values()).mapToInt(Nrc::code).distinct().count());
        for (Nrc n : Nrc.values()) {
            assertEquals(Optional.of(n), Nrc.of(n.code()));
        }
        assertEquals(Optional.empty(), Nrc.of(0x99));
        assertEquals(0x78, Nrc.RESPONSE_PENDING.code());
    }

    @Test
    @DisplayName("result records copy their lists")
    void defensiveCopies() {
        List<Dtc> dtcs = new ArrayList<>(List.of(new UdsDtc(0x040100, 0x2F)));
        DtcList l = new DtcList(dtcs, RawBytes.EMPTY, RawBytes.EMPTY, OptionalInt.empty(), List.of());
        dtcs.clear();
        assertEquals(1, l.dtcs().size());
        assertThrows(UnsupportedOperationException.class, () -> l.dtcs().clear());
        List<RawRecord> recs = new ArrayList<>(List.of(new RawRecord("22 F1 87", RawBytes.ofHex("30"))));
        EcuIdentification id = new EcuIdentification(ApplicationProtocol.UDS, Map.of(), recs, List.of());
        recs.clear();
        assertEquals(1, id.records().size());
        assertEquals("22 F1 87: 30", id.records().getFirst().toString());
        assertEquals("x: (no bytes)", new RawRecord("x", RawBytes.EMPTY).toString());
    }
}
