package io.github.samsanjaysatish.macvagdiag.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.can.SlcanPortDiscovery.Candidate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PortChoiceTest {

    @Test
    void autoDetectsTheOnlyCanableAndTakesFirmwareFromTheUsbDescription() throws UsageException {
        PortChoice p = PortChoice.resolve(Optional.empty(), List.of(TestEnv.ARDUINO, TestEnv.CANABLE));
        assertEquals(TestEnv.CANABLE_PORT, p.path());
        assertEquals(TestEnv.FIRMWARE, p.firmware());
    }

    @Test
    void missingPortWithNoCanableIsAnError() {
        UsageException e = assertThrows(UsageException.class,
                () -> PortChoice.resolve(Optional.empty(), List.of(TestEnv.ARDUINO)));
        assertTrue(e.getMessage().contains("no CANable found"), e.getMessage());
        assertTrue(e.getMessage().contains("--port"), e.getMessage());
        assertTrue(e.getMessage().contains("list-ports"), e.getMessage());
        assertThrows(UsageException.class, () -> PortChoice.resolve(Optional.empty(), List.of()));
    }

    @Test
    void missingPortWithSeveralCanablesListsThem() {
        Candidate second = new Candidate("/dev/cu.usbmodem2201", TestEnv.FIRMWARE, 0x16D0, 0x117E);
        UsageException e = assertThrows(UsageException.class,
                () -> PortChoice.resolve(Optional.empty(), List.of(TestEnv.CANABLE, second)));
        assertTrue(e.getMessage().contains(TestEnv.CANABLE_PORT), e.getMessage());
        assertTrue(e.getMessage().contains("/dev/cu.usbmodem2201"), e.getMessage());
    }

    @Test
    void explicitPortIsUsedEvenIfDiscoveryDoesNotKnowIt() throws UsageException {
        PortChoice p = PortChoice.resolve(Optional.of("/dev/cu.usbmodem9"), List.of(TestEnv.CANABLE));
        assertEquals("/dev/cu.usbmodem9", p.path());
        assertEquals(PortChoice.UNKNOWN_FIRMWARE, p.firmware());
    }

    @Test
    void explicitNonCanablePortDoesNotClaimAFirmwareVersion() throws UsageException {
        PortChoice p = PortChoice.resolve(Optional.of(TestEnv.ARDUINO.path()), List.of(TestEnv.ARDUINO));
        assertEquals("unknown (USB description: Arduino Uno)", p.firmware());
    }

    @Test
    void blankDescriptionMeansUnknownFirmware() throws UsageException {
        Candidate bare = new Candidate(TestEnv.CANABLE_PORT, " ", 0x16D0, 0x117E);
        assertEquals(PortChoice.UNKNOWN_FIRMWARE, PortChoice.resolve(Optional.empty(), List.of(bare)).firmware());
    }
}
