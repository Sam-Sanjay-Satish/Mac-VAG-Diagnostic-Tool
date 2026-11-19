package io.github.samsanjaysatish.macvagdiag.transport;

import static io.github.samsanjaysatish.macvagdiag.transport.ApplicationProtocol.KWP2000;
import static io.github.samsanjaysatish.macvagdiag.transport.ApplicationProtocol.UDS;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.can.Hex;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The allowlist itself, without any channel: exact contents, payload checks, and an exhaustive sweep. */
class RequestGuardTest {

    @Test
    @DisplayName("allowlist is exactly the list the user approved on 2026-10-22 and 2026-11-15 (changing it needs approval)")
    void allowlistIsPinned() {
        assertEquals(List.of(
                "KWP2000 10 89 (start VW diagnostic session)",
                "KWP2000 1A xx (read ECU identification)",
                "KWP2000 18 xx (read DTCs by status)",
                "KWP2000 18 00 FF 00 (read DTCs by status 00, all groups)",
                "KWP2000 18 02 FF 00 (read DTCs by status 02, all groups)",
                "KWP2000 12 xx (read freeze frame data)",
                "KWP2000 12 00 04 xx xx (read freeze frame 00 by DTC)",
                "KWP2000 22 xx xx (read data by identifier over TP 2.0 (one DID))",
                "KWP2000 21 01..FF (read measuring block group 01..FF)",
                "KWP2000 3E (tester present)",
                "UDS 10 01 (default session)",
                "UDS 10 03 (extended diagnostic session)",
                "UDS 3E 00 (tester present)",
                "UDS 3E 80 (tester present, suppress positive response)",
                "UDS 19 01 xx (report number of DTCs by status mask)",
                "UDS 19 02 xx (report DTCs by status mask)",
                "UDS 19 04 xx xx xx xx (report DTC snapshot record by DTC number)",
                "UDS 19 06 xx xx xx xx (report DTC extended data record by DTC number)",
                "UDS 22 xx xx (read data by identifier (one DID))"),
                RequestGuard.ALLOWLIST.stream().map(RequestGuard.Entry::toString).toList());
    }

    @Test
    @DisplayName("KWP 21 gg (measuring blocks, approved 2026-11-19): 21 01..21 FF only, never 21 00, never on UDS")
    void measuringBlocks() {
        assertFalse(RequestGuard.isAllowed(KWP2000, new byte[] {0x21, 0x00}), "21 00");
        for (int group = 1; group <= 0xFF; group++) {
            assertTrue(RequestGuard.isAllowed(KWP2000, new byte[] {0x21, (byte) group}), "21 " + group);
        }
        for (int group = 0; group <= 0xFF; group++) {
            assertFalse(RequestGuard.isAllowed(UDS, new byte[] {0x21, (byte) group}), "UDS 21 " + group);
            assertFalse(RequestGuard.isAllowed(KWP2000, new byte[] {0x21, (byte) group, 0x00}), "21 gg 00");
        }
        assertFalse(RequestGuard.isAllowed(KWP2000, new byte[] {0x21}), "21 alone");
    }

    @Test
    @DisplayName("allowlist is a fixed constant: unmodifiable, all static fields final, no public mutators")
    void allowlistCannotBeChanged() throws Exception {
        assertThrows(UnsupportedOperationException.class, () -> RequestGuard.ALLOWLIST.add(RequestGuard.ALLOWLIST.get(0)));
        for (Field f : RequestGuard.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) {
                assertTrue(Modifier.isFinal(f.getModifiers()), f.getName() + " must be final");
            }
        }
        for (Method m : RequestGuard.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers())) {
                assertTrue(List.of("isAllowed", "check").contains(m.getName()), "unexpected public method " + m);
            }
        }
        assertTrue(Modifier.isFinal(RequestGuard.class.getModifiers()));
        assertTrue(Modifier.isPrivate(RequestGuard.class.getDeclaredConstructor().getModifiers()));
        // The entries' prefixes are copied, so mutating a returned prefix can't change the allowlist.
        RequestGuard.ALLOWLIST.get(0).prefix()[1] = 0x02;
        assertArrayEquals(Hex.parse("10 89"), RequestGuard.ALLOWLIST.get(0).prefix());
    }

    @ParameterizedTest(name = "allows {0} {1}")
    @CsvSource({
            "KWP2000, 10 89", "KWP2000, 1A 9B", "KWP2000, 1A 86", "KWP2000, 1A 00", "KWP2000, 1A FF",
            "KWP2000, 18 02", "KWP2000, 18 00", "KWP2000, 12 00", "KWP2000, 12 FF", "KWP2000, 3E",
            // Seen from VCDS in the 2026-11-13 capture, approved 2026-11-15.
            "KWP2000, 18 00 FF 00", "KWP2000, 18 02 FF 00",
            "KWP2000, 12 00 04 2B A6", "KWP2000, 12 00 04 00 87", "KWP2000, 12 00 04 00 00", "KWP2000, 12 00 04 FF FF",
            "KWP2000, 22 04 A1", "KWP2000, 22 F1 87", "KWP2000, 22 00 00", "KWP2000, 22 FF FF",
            // Measuring blocks, approved 2026-11-19.
            "KWP2000, 21 01", "KWP2000, 21 07", "KWP2000, 21 0B", "KWP2000, 21 FF",
            "UDS, 10 01", "UDS, 10 03", "UDS, 3E 00", "UDS, 3E 80",
            "UDS, 19 01 FF", "UDS, 19 02 FF", "UDS, 19 02 08", "UDS, 19 04 00 04 01 FF", "UDS, 19 04 12 34 56 01",
            "UDS, 19 06 00 04 01 FF", "UDS, 22 F1 90", "UDS, 22 F1 87", "UDS, 22 00 00", "UDS, 22 FF FF"})
    void allows(ApplicationProtocol protocol, String hex) {
        assertTrue(RequestGuard.isAllowed(protocol, Hex.parse(hex)));
        assertDoesNotThrow(() -> RequestGuard.check(protocol, Hex.parse(hex)));
    }

    @ParameterizedTest(name = "rejects {0} {1}: {2}")
    @CsvSource({
            "KWP2000, 21 00, only 21 01..FF allowed for service 21",
            "KWP2000, 21 07 00, only 21 01..FF allowed for service 21",
            "UDS, 21 01, service 21 is not on the read-only allowlist for UDS",
            "UDS, 27 01, service 27 is not on the read-only allowlist for UDS",
            "UDS, 10 02, only 10 01 / 10 03 allowed for service 10",
            "KWP2000, 10 85, only 10 89 allowed for service 10",
            "UDS, 19 03, only 19 01 xx / 19 02 xx / 19 04 xx xx xx xx / 19 06 xx xx xx xx allowed for service 19",
            "UDS, 22 F1 90 F1 87, only 22 xx xx allowed for service 22",
            "KWP2000, 18 02 FF 01, only 18 xx / 18 00 FF 00 / 18 02 FF 00 allowed for service 18",
            "KWP2000, 18 04 FF 00, only 18 xx / 18 00 FF 00 / 18 02 FF 00 allowed for service 18",
            "KWP2000, 18 02 FF, only 18 xx / 18 00 FF 00 / 18 02 FF 00 allowed for service 18",
            "KWP2000, 18 02 FF 00 00, only 18 xx / 18 00 FF 00 / 18 02 FF 00 allowed for service 18",
            "KWP2000, 12 00 01, only 12 xx / 12 00 04 xx xx allowed for service 12",
            "KWP2000, 12 00 04 2B, only 12 xx / 12 00 04 xx xx allowed for service 12",
            "KWP2000, 12 00 04 2B A6 00, only 12 xx / 12 00 04 xx xx allowed for service 12",
            "KWP2000, 12 01 04 2B A6, only 12 xx / 12 00 04 xx xx allowed for service 12",
            "KWP2000, 12 00 05 2B A6, only 12 xx / 12 00 04 xx xx allowed for service 12",
            "KWP2000, 22 F1 87 F1 89 F1 91 F1 A3 F1 A5 F1 DF, only 22 xx xx allowed for service 22",
            "KWP2000, 22 F1, only 22 xx xx allowed for service 22",
            "KWP2000, 31 B8 00 00, service 31 is not on the read-only allowlist for KWP2000"})
    void rejectionReasons(ApplicationProtocol protocol, String hex, String reason) {
        ForbiddenRequestException e = assertThrows(ForbiddenRequestException.class,
                () -> RequestGuard.check(protocol, Hex.parse(hex)));
        assertEquals(reason, e.reason());
        assertEquals(Optional.of(protocol), e.protocol());
        assertArrayEquals(Hex.parse(hex), e.payload());
        assertTrue(e.getMessage().contains(hex), e.getMessage());
    }

    @Test
    @DisplayName("unknown protocol, null payload and empty payload are rejected")
    void defaultsToReject() {
        ForbiddenRequestException noProtocol = assertThrows(ForbiddenRequestException.class,
                () -> RequestGuard.check(null, Hex.parse("22 F1 90")));
        assertEquals("no application protocol", noProtocol.reason());
        assertEquals(Optional.empty(), noProtocol.protocol());
        assertEquals("no payload", assertThrows(ForbiddenRequestException.class,
                () -> RequestGuard.check(UDS, null)).reason());
        assertEquals("empty payload", assertThrows(ForbiddenRequestException.class,
                () -> RequestGuard.check(KWP2000, new byte[0])).reason());
        assertFalse(RequestGuard.isAllowed(null, Hex.parse("3E 00")));
        assertFalse(RequestGuard.isAllowed(UDS, null));
        assertFalse(RequestGuard.isAllowed(UDS, new byte[0]));
    }

    /** An independent restatement of the approved allowlist, used to sweep every request shape. */
    private static boolean approved(ApplicationProtocol p, byte[] b) {
        int n = b.length;
        int sid = Byte.toUnsignedInt(b[0]);
        int sub = n > 1 ? Byte.toUnsignedInt(b[1]) : -1;
        return switch (p) {
            case KWP2000 -> (sid == 0x10 && sub == 0x89 && n == 2)
                    || (sid == 0x1A && n == 2)
                    || (sid == 0x18 && n == 2)
                    || (sid == 0x18 && (sub == 0x00 || sub == 0x02) && n == 4
                            && Byte.toUnsignedInt(b[2]) == 0xFF && b[3] == 0x00)
                    || (sid == 0x12 && n == 2)
                    || (sid == 0x12 && sub == 0x00 && n == 5 && b[2] == 0x04)
                    || (sid == 0x22 && n == 3)
                    || (sid == 0x21 && sub != 0x00 && n == 2)
                    || (sid == 0x3E && n == 1);
            case UDS -> (sid == 0x10 && (sub == 0x01 || sub == 0x03) && n == 2)
                    || (sid == 0x3E && (sub == 0x00 || sub == 0x80) && n == 2)
                    || (sid == 0x19 && (sub == 0x01 || sub == 0x02) && n == 3)
                    || (sid == 0x19 && (sub == 0x04 || sub == 0x06) && n == 6)
                    || (sid == 0x22 && n == 3);
        };
    }

    @Test
    @DisplayName("exhaustive: every SID x every second byte x lengths 1..8, for both protocols, matches the approved list")
    void exhaustiveSweep() {
        int allowed = 0;
        for (ApplicationProtocol p : ApplicationProtocol.values()) {
            for (int sid = 0; sid <= 0xFF; sid++) {
                for (int length = 1; length <= 8; length++) {
                    for (int second = 0; second <= (length == 1 ? 0 : 0xFF); second++) {
                        byte[] payload = new byte[length];
                        Arrays.fill(payload, (byte) 0x5A);
                        payload[0] = (byte) sid;
                        if (length > 1) {
                            payload[1] = (byte) second;
                        }
                        boolean expected = approved(p, payload);
                        assertEquals(expected, RequestGuard.isAllowed(p, payload), () -> p + " " + Hex.format(payload));
                        if (expected) {
                            allowed++;
                        }
                    }
                }
            }
        }
        // KWP: 10 89 (1) + 1A/18/12 xx (3 x 256) + 22 xx (256) + 21 01..FF (255) + 3E (1); UDS: 10 01/03 (2) + 3E 00/80 (2)
        // + 19 01/02/04/06 (4) + 22 xx (256). The filler 5A never forms 18 0x FF 00 or 12 00 04: see the next test.
        assertEquals(1 + 3 * 256 + 256 + 255 + 1 + 2 + 2 + 4 + 256, allowed);
    }

    @Test
    @DisplayName("KWP 18 long form: of every 18 ss gg gg, only 18 00 FF 00 and 18 02 FF 00 are allowed")
    void kwpReadDtcsLongFormSweep() {
        int allowed = 0;
        for (int status = 0; status <= 0xFF; status++) {
            for (int group = 0; group <= 0xFFFF; group++) {
                byte[] payload = {0x18, (byte) status, (byte) (group >> 8), (byte) group};
                boolean expected = (status == 0x00 || status == 0x02) && group == 0xFF00;
                assertEquals(expected, RequestGuard.isAllowed(KWP2000, payload), () -> Hex.format(payload));
                assertFalse(RequestGuard.isAllowed(UDS, payload), () -> "UDS " + Hex.format(payload));
                if (expected) {
                    allowed++;
                }
            }
        }
        assertEquals(2, allowed);
    }

    @Test
    @DisplayName("KWP 12 long form: 12 00 04 with any 2-byte DTC is allowed, any other number or access method is not")
    void kwpFreezeFrameLongFormSweep() {
        for (int dtc = 0; dtc <= 0xFFFF; dtc++) {
            byte[] payload = {0x12, 0x00, 0x04, (byte) (dtc >> 8), (byte) dtc};
            assertTrue(RequestGuard.isAllowed(KWP2000, payload), () -> Hex.format(payload));
            assertFalse(RequestGuard.isAllowed(UDS, payload), () -> "UDS " + Hex.format(payload));
        }
        for (int number = 0; number <= 0xFF; number++) {
            for (int method = 0; method <= 0xFF; method++) {
                byte[] payload = {0x12, (byte) number, (byte) method, 0x2B, (byte) 0xA6};
                boolean expected = number == 0x00 && method == 0x04;
                assertEquals(expected, RequestGuard.isAllowed(KWP2000, payload), () -> Hex.format(payload));
            }
        }
    }

    @Test
    @DisplayName("isAllowed and check agree, and check never mutates the payload")
    void checkAgreesWithIsAllowed() {
        for (String hex : List.of("10 89", "10 85", "3E", "3E 00", "19 02 FF", "27 01", "22 F1 90")) {
            for (ApplicationProtocol p : ApplicationProtocol.values()) {
                byte[] payload = Hex.parse(hex);
                boolean allowed = RequestGuard.isAllowed(p, payload);
                boolean checkPassed;
                try {
                    RequestGuard.check(p, payload);
                    checkPassed = true;
                } catch (ForbiddenRequestException e) {
                    checkPassed = false;
                }
                assertEquals(allowed, checkPassed, p + " " + hex);
                assertArrayEquals(Hex.parse(hex), payload);
            }
        }
    }
}
