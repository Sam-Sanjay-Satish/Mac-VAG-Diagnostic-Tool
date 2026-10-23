package io.github.samsanjaysatish.macvagdiag.can;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Safety rule: in LISTEN_ONLY, send throws on every adapter type and nothing reaches the wire.
 */
class ListenOnlyGuaranteeTest {

    private static final CanFrame SESSION_REQUEST = CanFrame.standardHex(0x7E0, "02 10 03 00 00 00 00 00");
    private static final CanFrame TP20_SETUP = CanFrame.standardHex(0x200, "01 C0 00 10 00 03 01");

    private static Capture captureWithTesterFrame() {
        Instant t = Instant.ofEpochSecond(1_729_600_000L);
        return new Capture(Map.of(), List.of(), List.of(
                new CapturedFrame(t, "can0", TP20_SETUP),
                new CapturedFrame(t.plusMillis(5), "can0", CanFrame.standardHex(0x201, "00 D0 00 03 40 07 01"))));
    }

    @Test
    @DisplayName("SimulatedAdapter: send 7E0 [8] 02 10 03 ... in LISTEN_ONLY throws and nothing is sent")
    void simulatedAdapter() throws Exception {
        FakeTimeSource time = new FakeTimeSource();
        SimulatedAdapter adapter = new SimulatedAdapter(time,
                sent -> List.of(SimulatedAdapter.Reply.now(CanFrame.standard(0x7E8, 0x02, 0x50, 0x03))));
        adapter.open(Bitrate.KBIT_500, AdapterMode.LISTEN_ONLY);

        assertThrows(ListenOnlyViolationException.class, () -> adapter.send(SESSION_REQUEST));
        assertThrows(ListenOnlyViolationException.class, () -> adapter.send(TP20_SETUP));

        assertEquals(List.of(), adapter.sentFrames(), "nothing reached the simulated wire");
        assertEquals(Optional.empty(), adapter.receive(Duration.ZERO), "the responder never ran");
        assertTrue(adapter.isOpen(), "a refused send does not close the adapter");
        adapter.close();
    }

    @Test
    @DisplayName("ReplayAdapter (interactive): send 200 [7] 01 C0 ... in LISTEN_ONLY throws and releases nothing")
    void replayAdapterInteractive() throws Exception {
        ReplayAdapter adapter = ReplayAdapter.interactive(captureWithTesterFrame(),
                frame -> frame.id() == 0x200, new FakeTimeSource());
        adapter.open(Bitrate.KBIT_500, AdapterMode.LISTEN_ONLY);

        assertThrows(ListenOnlyViolationException.class, () -> adapter.send(TP20_SETUP));

        assertEquals(List.of(), adapter.sentFrames());
        assertEquals(Optional.of(TP20_SETUP), adapter.nextExpectedFrame(), "the replay did not advance");
        assertEquals(Optional.empty(), adapter.receive(Duration.ZERO), "no recorded reply was released");
        adapter.close();
    }

    @Test
    @DisplayName("ReplayAdapter (passive): send in LISTEN_ONLY throws ListenOnlyViolationException")
    void replayAdapterPassive() {
        ReplayAdapter adapter = ReplayAdapter.passive(captureWithTesterFrame(), ReplayAdapter.Pacing.IMMEDIATE,
                new FakeTimeSource());
        adapter.open(Bitrate.KBIT_500, AdapterMode.LISTEN_ONLY);
        assertThrows(ListenOnlyViolationException.class, () -> adapter.send(SESSION_REQUEST));
        adapter.close();
    }

    @Test
    @DisplayName("SlcanAdapter: LISTEN_ONLY open is refused and the fake serial port receives zero bytes")
    void slcanAdapter() {
        FakeSerialPort port = new FakeSerialPort();
        SlcanAdapter adapter = new SlcanAdapter(port, new FakeTimeSource());

        assertThrows(CanAdapterException.class, () -> adapter.open(Bitrate.KBIT_500, AdapterMode.LISTEN_ONLY));
        assertThrows(CanAdapterException.class, () -> adapter.send(SESSION_REQUEST));

        assertFalse(adapter.isOpen());
        assertEquals(0, port.writtenByteCount(), "zero bytes, so zero frame bytes, reached the serial port");
        adapter.close();
        assertEquals(0, port.writtenByteCount(), "close of a never-opened adapter writes nothing either");
    }

    @Test
    @DisplayName("SlcanAdapter: even with the port open in NORMAL, frame bytes only go out through send")
    void slcanAdapterWritesOnlyCommandsUntilSend() {
        FakeSerialPort port = new FakeSerialPort();
        SlcanAdapter adapter = new SlcanAdapter(port, new FakeTimeSource());
        adapter.open(Bitrate.KBIT_500, AdapterMode.NORMAL);
        adapter.close();
        assertFalse(port.writtenText().contains("t"), "no frame command ('t') written: " + port.writtenText());
        assertFalse(port.writtenText().contains("T"));
    }

    @Test
    @DisplayName("every adapter type is a subclass of AbstractCanAdapter, which no one can bypass")
    void everyAdapterGoesThroughTheBase() throws Exception {
        assertTrue(CanAdapter.class.isSealed());
        assertEquals(List.of(AbstractCanAdapter.class), List.of(CanAdapter.class.getPermittedSubclasses()));
        assertTrue(AbstractCanAdapter.class.isSealed());
        for (Class<?> adapter : AbstractCanAdapter.class.getPermittedSubclasses()) {
            assertTrue(Modifier.isFinal(adapter.getModifiers()), adapter + " must be final");
        }
        for (String name : List.of("open", "send", "receive", "close", "isOpen", "mode")) {
            for (Method m : AbstractCanAdapter.class.getDeclaredMethods()) {
                if (m.getName().equals(name)) {
                    assertTrue(Modifier.isFinal(m.getModifiers()), name + " must be final");
                }
            }
        }
        Method transmit = AbstractCanAdapter.class.getDeclaredMethod("transmit", CanFrame.class);
        assertFalse(Modifier.isPublic(transmit.getModifiers()) || Modifier.isProtected(transmit.getModifiers()),
                "transmit must be package-private");
    }
}
