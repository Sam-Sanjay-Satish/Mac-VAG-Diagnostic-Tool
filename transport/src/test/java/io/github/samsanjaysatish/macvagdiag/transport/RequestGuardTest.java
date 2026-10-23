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
    @DisplayName("allowlist is exactly the list the user approved on 2026-10-22 (changing it needs approval)")
    void allowlistIsPinned() {
        assertEquals(List.of(
                "KWP2000 10 89 (start VW diagnostic session)",
                "KWP2000 1A xx (read ECU identification)",
                "KWP2000 18 xx (read DTCs by status)",
                "KWP2000 12 xx (read freeze frame data)",
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
    @DisplayName("KWP 21 xx (measuring blocks) is deliberately not on the v1 allowlist")
    void measuringBlocksExcluded() {
        for (int block = 0; block <= 0xFF; block++) {
            assertFalse(RequestGuard.isAllowed(KWP2000, new byte[] {0x21, (byte) block}), "21 " + block);
        }
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
            "UDS, 10 01", "UDS, 10 03", "UDS, 3E 00", "UDS, 3E 80",
            "UDS, 19 01 FF", "UDS, 19 02 FF", "UDS, 19 02 08", "UDS, 19 04 00 04 01 FF", "UDS, 19 04 12 34 56 01",
            "UDS, 19 06 00 04 01 FF", "UDS, 22 F1 90", "UDS, 22 F1 87", "UDS, 22 00 00", "UDS, 22 FF FF"})
    void allows(ApplicationProtocol protocol, String hex) {
        assertTrue(RequestGuard.isAllowed(protocol, Hex.parse(hex)));
        assertDoesNotThrow(() -> RequestGuard.check(protocol, Hex.parse(hex)));
    }

    @ParameterizedTest(name = "rejects {0} {1}: {2}")
    @CsvSource({
            "KWP2000, 21 01, service 21 is not on the read-only allowlist for KWP2000",
            "UDS, 27 01, service 27 is not on the read-only allowlist for UDS",
            "UDS, 10 02, only 10 01 / 10 03 allowed for service 10",
            "KWP2000, 10 85, only 10 89 allowed for service 10",
            "UDS, 19 03, only 19 01 xx / 19 02 xx / 19 04 xx xx xx xx / 19 06 xx xx xx xx allowed for service 19",
            "UDS, 22 F1 90 F1 87, only 22 xx xx allowed for service 22",
            "KWP2000, 18 02 FF 00, only 18 xx allowed for service 18"})
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
                    || (sid == 0x12 && n == 2)
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
        // KWP: 10 89 (1) + 1A/18/12 xx (3 x 256) + 3E (1); UDS: 10 01/03 (2) + 3E 00/80 (2) + 19 01/02/04/06 (4) + 22 xx (256)
        assertEquals(1 + 3 * 256 + 1 + 2 + 2 + 4 + 256, allowed);
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
