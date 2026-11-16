package io.github.samsanjaysatish.macvagdiag.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.can.FakeTimeSource;
import io.github.samsanjaysatish.macvagdiag.transport.FakeDiagnosticChannel;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@code 22 04 A1} edge cases; the real list is in {@link KwpCaptureTest#installationList}. */
class InstallationListTest {

    private final FakeTimeSource time = new FakeTimeSource();

    @Test
    @DisplayName("truncated last entry: whole entries decoded, the rest undecoded with a warning")
    void truncated() {
        InstallationList l = InstallationList.parse(RawBytes.ofHex("62 04 A1 01 01 05 10 02 02")).valueIfSuccess().orElseThrow();
        assertEquals(List.of(new InstallationList.Entry(0x01, 0x01, 0x05, 0x10)), l.entries());
        assertEquals(RawBytes.ofHex("02 02"), l.undecoded());
        assertEquals(1, l.warnings().size());
        assertFalse(l.complete());
    }

    @Test
    @DisplayName("62 04 A1 alone: empty list; 62 04 A2 / 62 04: malformed, raw kept")
    void headers() {
        InstallationList empty = InstallationList.parse(RawBytes.ofHex("62 04 A1")).valueIfSuccess().orElseThrow();
        assertEquals(List.of(), empty.entries());
        assertTrue(empty.complete());
        for (String bad : List.of("62 04 A2 01 01 05 10", "62 04")) {
            DiagResult.Malformed<?> m = assertInstanceOf(DiagResult.Malformed.class, InstallationList.parse(RawBytes.ofHex(bad)));
            assertEquals(RawBytes.ofHex(bad), m.response());
        }
    }

    @Test
    @DisplayName("status bytes: 00 01 05 19 known, anything else OTHER; VCDS bits are bits 4..1")
    void statuses() {
        assertEquals(InstallationList.Entry.Status.OTHER, new InstallationList.Entry(0x01, 0x01, 0x03, 0x10).statusKind());
        assertTrue(new InstallationList.Entry(0x01, 0x01, 0x03, 0x10).installed());
        assertEquals("0001", new InstallationList.Entry(0x01, 0x01, 0x03, 0x10).vcdsStatusBits());
        assertEquals("0000", new InstallationList.Entry(0x01, 0x01, 0x01, 0x10).vcdsStatusBits());
        assertEquals("1111", new InstallationList.Entry(0x01, 0x01, 0xFF, 0x10).vcdsStatusBits());
        assertThrows(IllegalArgumentException.class, () -> new InstallationList.Entry(0x100, 0, 0, 0));
    }

    @Test
    @DisplayName("a module without the list answers 7F 22 31: Negative; UDS sends the same 22 04 A1")
    void negativeAndUds() throws InterruptedException {
        FakeDiagnosticChannel kwp = FakeDiagnosticChannel.kwp().on("22 04 A1", "7F 22 31");
        assertInstanceOf(DiagResult.Negative.class, new KwpDiagnosticService(kwp, time).readInstallationList());
        FakeDiagnosticChannel uds = FakeDiagnosticChannel.uds().on("22 04 A1", "62 04 A1 17 07 05 10");
        InstallationList l = new UdsDiagnosticService(uds, time).readInstallationList().valueIfSuccess().orElseThrow();
        assertEquals(List.of(new InstallationList.Entry(0x17, 0x07, 0x05, 0x10)), l.entries());
        assertEquals(List.of("22 04 A1"), uds.requestsHex());
    }
}
