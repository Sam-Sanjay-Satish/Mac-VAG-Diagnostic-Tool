package io.github.samsanjaysatish.macvagdiag.can;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.samsanjaysatish.macvagdiag.can.SlcanPortDiscovery.Candidate;
import java.util.List;
import org.junit.jupiter.api.Test;

class SlcanPortDiscoveryTest {

    @Test
    void keepsOnlyCuUsbmodemPortsWithCanablesFirst() {
        Candidate other = new Candidate("/dev/cu.usbmodem1101", "Some Arduino", 0x2341, 0x0043);
        Candidate canable = new Candidate("/dev/cu.usbmodem2201", "CANable2 b158aa7 github.com/normaldotcom/canable2-fw.git",
                0x16D0, 0x117E);
        List<Candidate> result = SlcanPortDiscovery.filter(List.of(
                new Candidate("/dev/tty.usbmodem2201", "CANable2", 0x16D0, 0x117E),
                other,
                new Candidate("/dev/cu.Bluetooth-Incoming-Port", "", -1, -1),
                canable,
                new Candidate("/dev/cu.debug-console", "", -1, -1)));
        assertEquals(List.of(canable, other), result);
    }

    @Test
    void canableIsRecognisedByUsbIds() {
        assertEquals(true, new Candidate("/dev/cu.usbmodem1", "", 0x16D0, 0x117E).isCanable());
        assertEquals(false, new Candidate("/dev/cu.usbmodem1", "", 0x16D0, 0x0001).isCanable());
    }

    @Test
    void emptyWhenNothingMatches() {
        assertEquals(List.of(), SlcanPortDiscovery.filter(List.of(new Candidate("COM3", "", -1, -1))));
    }
}
