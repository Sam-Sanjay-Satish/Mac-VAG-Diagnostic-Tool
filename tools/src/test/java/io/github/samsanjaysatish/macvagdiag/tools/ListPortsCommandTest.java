package io.github.samsanjaysatish.macvagdiag.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.can.FakeTimeSource;
import io.github.samsanjaysatish.macvagdiag.can.SlcanPortDiscovery.Candidate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ListPortsCommandTest {

    @Test
    void listsPortsMarkingTheCanable() {
        Candidate unknown = new Candidate("/dev/cu.usbmodem777", null, -1, -1);
        TestEnv t = new TestEnv(new FakeTimeSource(), List.of(TestEnv.CANABLE, TestEnv.ARDUINO, unknown));

        assertEquals(0, t.run("list-ports"));

        List<String> lines = t.out().lines().toList();
        assertEquals("/dev/cu.usbmodem14101  CANable  16D0:117E  " + TestEnv.FIRMWARE, lines.get(0));
        assertEquals("/dev/cu.usbmodem3101   -        2341:0043  Arduino Uno", lines.get(1));
        assertEquals("/dev/cu.usbmodem777    -        ????:????  -", lines.get(2));
        assertTrue(lines.get(3).contains("One CANable found"), t.out());
    }

    @Test
    void noPorts() {
        TestEnv t = new TestEnv(new FakeTimeSource(), List.of());
        assertEquals(1, t.run("list-ports"));
        assertTrue(t.err().contains("Is the CANable plugged in?"), t.err());
    }

    @Test
    void noCanableAmongThePorts() {
        TestEnv t = new TestEnv(new FakeTimeSource(), List.of(TestEnv.ARDUINO));
        assertEquals(0, t.run("list-ports"));
        assertTrue(t.out().contains("No CANable"), t.out());
    }

    @Test
    void rejectsArguments() {
        assertEquals(2, new TestEnv(new FakeTimeSource(), List.of()).run("list-ports", "--all"));
    }
}
