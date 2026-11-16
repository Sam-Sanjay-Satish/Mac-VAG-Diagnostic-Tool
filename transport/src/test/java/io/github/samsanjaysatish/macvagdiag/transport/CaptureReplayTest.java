package io.github.samsanjaysatish.macvagdiag.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.can.AdapterMode;
import io.github.samsanjaysatish.macvagdiag.can.Bitrate;
import io.github.samsanjaysatish.macvagdiag.can.CanFrame;
import io.github.samsanjaysatish.macvagdiag.can.Capture;
import io.github.samsanjaysatish.macvagdiag.can.FakeTimeSource;
import io.github.samsanjaysatish.macvagdiag.can.FrameMatcher;
import io.github.samsanjaysatish.macvagdiag.can.Hex;
import io.github.samsanjaysatish.macvagdiag.can.RealCaptures;
import io.github.samsanjaysatish.macvagdiag.can.ReplayAdapter;
import io.github.samsanjaysatish.macvagdiag.can.SimulatedAdapter;
import io.github.samsanjaysatish.macvagdiag.transport.CaptureExchanges.Control;
import io.github.samsanjaysatish.macvagdiag.transport.CaptureExchanges.Event;
import io.github.samsanjaysatish.macvagdiag.transport.CaptureExchanges.Link;
import io.github.samsanjaysatish.macvagdiag.transport.CaptureExchanges.Message;
import io.github.samsanjaysatish.macvagdiag.transport.CaptureExchanges.Session;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Transport against the real 2026-11-13 capture (VIN-masked fixture).
 *
 * <ul>
 *   <li><b>Strict replays:</b> every TP 2.0 and ISO-TP session VCDS had with a module is replayed frame for frame
 *       through our real {@link ChannelFactory} channels on an interactive {@link ReplayAdapter}, sending VCDS's own
 *       requests in VCDS's order, up to the first request our allowlist refuses ({@code 31 B8 00 00}, the six-DID
 *       {@code 22}, OBD {@code 09 02}) or VCDS's one-byte {@code A8} quirk on ISO-TP. Every frame we put on the bus
 *       must equal VCDS's (setup, {@code A0}, data packets with sequence numbers, ACKs, {@code A3}, {@code A8},
 *       ISO-TP padding {@code 55} and flow control {@code 30 00 01}), and every message we receive must equal the
 *       recorded one. The only tolerated difference: our {@code A1} answer to a module-initiated {@code A3}.</li>
 *   <li><b>Capture ECU:</b> {@link CaptureEcu} answers requests in our order from the recorded exchanges.</li>
 * </ul>
 */
class CaptureReplayTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    /** Exact, except our {@code A1} reply to a module's {@code A3} (VCDS sends {@code A1 0F 64 9C xx 9C}, we our timing). */
    private static final FrameMatcher VCDS_FRAMES = (recorded, sent) -> recorded.equals(sent)
            || (recorded.id() == sent.id() && recorded.length() > 0 && sent.length() > 0
                    && recorded.dataByte(0) == 0xA1 && sent.dataByte(0) == 0xA1);

    // ------------------------------------------------------------------ reassembly

    @Test
    @DisplayName("Auto-Scan capture reassembles cleanly: 7 TP 2.0 modules, 7 ISO-TP pairs, 98 response-pending with the length flag")
    void autoScanReassembles() {
        CaptureExchanges.Result r = CaptureExchanges.of(RealCaptures.autoScan());
        for (Session s : r.sessions()) {
            assertTrue(s.errors().isEmpty(), s.link() + ": " + s.errors());
        }
        Set<Link> tp20 = new LinkedHashSet<>();
        Set<Link> isoTp = new LinkedHashSet<>();
        r.links().forEach(l -> (l instanceof Link.Tp20 ? tp20 : isoTp).add(l));
        assertEquals(Set.of(new Link.Tp20(0x1F), new Link.Tp20(0x01), new Link.Tp20(0x02), new Link.Tp20(0x03),
                new Link.Tp20(0x2C), new Link.Tp20(0x20), new Link.Tp20(0x09)), tp20);
        assertEquals(Set.of(new Link.IsoTp(0x714, 0x77E), new Link.IsoTp(0x732, 0x79C), new Link.IsoTp(0x715, 0x77F),
                new Link.IsoTp(0x711, 0x77B), new Link.IsoTp(0x731, 0x79B), new Link.IsoTp(0x76B, 0x7D5),
                new Link.IsoTp(0x7E0, 0x7E8)), isoTp);
        assertEquals(Set.of(0x07, 0x31, 0x05, 0x14, 0x54, 0x52, 0x5A), r.unansweredSetups(),
                "the UDS modules' logical addresses and the two unreachable ones; nobody answered");
        assertEquals(21, r.functionalFrames(), "3E 80 on 700");
        assertEquals(17, r.sessions(new Link.Tp20(0x1F)).size(),
                "the list channel, then a short gateway channel before each of the 16 other modules VCDS scanned");
        assertTrue(r.sessions().stream().filter(s -> s.link() instanceof Link.Tp20).allMatch(Session::closed));

        List<Message> flagged = r.sessions().stream().flatMap(s -> s.messages().stream())
                .filter(m -> m.lengthFlags() != 0).toList();
        assertEquals(98, flagged.size());
        assertTrue(flagged.stream().allMatch(m -> m.lengthFlags() == 0x8000 && m.hex().matches("7F .. 78")));
        // VCDS's unexplained frames after the unreachable modules: 10x 200#54 C0 A0 and 10x 2D0#39 C0 E9.
        assertEquals(20, r.unassignedFrames().size());
    }

    @Test
    @DisplayName("the engine's DTC list and its 12 freeze-frame requests are in the Auto-Scan capture as the analysis says")
    void engineExchanges() {
        Session engine = CaptureExchanges.of(RealCaptures.autoScan()).sessions(new Link.Tp20(0x01)).getFirst();
        List<String> requests = engine.exchanges().stream().map(CaptureExchanges.Exchange::requestHex).toList();
        assertEquals(List.of("10 89", "1A 9B", "31 B8 00 00", "1A 91", "1A 97", "1A 86", "18 00 FF 00",
                "12 00 04 2B A6", "12 00 04 1A 58", "12 00 04 19 46", "12 00 04 04 01", "12 00 04 24 0F",
                "12 00 04 06 91", "12 00 04 05 44", "12 00 04 04 01", "12 00 04 05 44", "12 00 04 00 87",
                "12 00 04 00 87", "12 00 04 00 87", "21 11", "21 59"), requests);
        CaptureExchanges.Exchange id86 = engine.exchanges().get(5);
        assertEquals("7F 1A 78", id86.responses().getFirst().hex());
        assertTrue(id86.responses().get(1).hex().startsWith("5A 86 "));
        assertEquals(0x8003, id86.responses().getFirst().lengthField());
        assertEquals("A1 0F 8A FF 4A FF", Hex.format(engine.timingReply().orElseThrow().data()));
    }

    // ------------------------------------------------------------------ strict replays

    /** What one strict replay covered. */
    private record Replayed(Link link, int requests, boolean wholeSession, int keepAlives, int pending) {
    }

    @Test
    @DisplayName("strict: every TP 2.0 session up to the first refused request; our frames equal VCDS's, byte for byte")
    void strictTp20Replays() throws InterruptedException {
        List<Replayed> done = new ArrayList<>();
        for (Capture capture : List.of(RealCaptures.autoScan(), RealCaptures.freezeFrame01())) {
            for (Session s : CaptureExchanges.of(capture).sessions()) {
                if (s.link() instanceof Link.Tp20) {
                    done.add(replayStrictly(capture, s));
                }
            }
        }
        // Auto-Scan: 17 gateway + 01 02 03 2C 20 09; 01 fault-code read: 2 gateway + 01.
        assertEquals(26, done.size());
        assertEquals(17, done.stream().filter(Replayed::wholeSession).count(),
                "the short gateway channels (1A 9B, 22 F1 87, 1A 91, 22 F1 91, A8) from setup to A8");
        assertTrue(done.stream().filter(d -> !d.wholeSession()).allMatch(d -> d.requests() >= 2),
                "the others at least to 10 89 + 1A 9B (VCDS's 31 B8 00 00 comes next)");
        assertEquals(4, done.stream().mapToInt(Replayed::keepAlives).sum(), "A3 → A1 keep-alives in the prefixes");
        int requests = done.stream().mapToInt(Replayed::requests).sum();
        assertEquals(96, requests, "requests replayed frame for frame");
    }

    @Test
    @DisplayName("strict: the gateway installation list (22 04 A1, 21 packets): our ACK at the 15-packet block boundary matches VCDS's")
    void strictInstallationList() throws InterruptedException {
        Capture capture = RealCaptures.freezeFrame01();
        Session list = CaptureExchanges.of(capture).sessions(new Link.Tp20(0x1F)).getFirst();
        assertEquals("1A 9F", list.exchanges().get(0).requestHex());
        assertEquals("22 04 A1", list.exchanges().get(1).requestHex());
        Replayed r = replayStrictly(capture, list);
        assertTrue(r.wholeSession());
        assertEquals(6, r.requests());
    }

    @Test
    @DisplayName("strict: every ISO-TP session of the six UDS modules (incl. 15's 7F 19 78), up to VCDS's one-byte A8")
    void strictIsoTpReplays() throws InterruptedException {
        List<Replayed> done = new ArrayList<>();
        Capture capture = RealCaptures.autoScan();
        for (Session s : CaptureExchanges.of(capture).sessions()) {
            if (s.link() instanceof Link.IsoTp iso && iso.requestId() != 0x7E0) {
                done.add(replayStrictly(capture, s));
            }
        }
        assertEquals(6, done.size());
        Replayed airbag = done.stream().filter(d -> d.link().equals(new Link.IsoTp(0x715, 0x77F))).findFirst().orElseThrow();
        assertTrue(airbag.pending() >= 1, "15 answered 19 02 AE with 7F 19 78 first");
        assertTrue(done.stream().allMatch(d -> d.requests() >= 15), done.toString());
    }

    /**
     * Replays {@code s} through a real channel: VCDS's requests in VCDS's order (A3 where VCDS sent it), up to the first
     * request the guard refuses or the end of the session. Asserts every frame and message.
     */
    private static Replayed replayStrictly(Capture capture, Session s) throws InterruptedException {
        // Cut before the first request the guard refuses, or before VCDS's one-byte A8 on ISO-TP.
        List<Event> events = new ArrayList<>();
        int end = Integer.MAX_VALUE;
        for (Event e : s.events()) {
            if (e instanceof Message m && m.fromTester() && !RequestGuard.isAllowed(protocol(s), m.payload())) {
                end = m.firstFrame();
                break;
            }
            if (e instanceof Control c && c.fromTester() && s.link() instanceof Link.IsoTp && c.opcode() == 0xA8) {
                end = s.frameIndices().getLast();
                break;
            }
            events.add(e);
        }
        boolean whole = end == Integer.MAX_VALUE;
        FakeTimeSource time = FakeTimeSource.autoAdvancing();
        int testerTx = s.testerTxId();
        ReplayAdapter replay = ReplayAdapter.interactive(s.sliceBefore(capture, end),
                f -> f.id() == testerTx || (s.link() instanceof Link.Tp20 && f.id() == CaptureExchanges.TP20_SETUP_ID),
                VCDS_FRAMES, ReplayAdapter.Pacing.IMMEDIATE, time);
        replay.open(Bitrate.KBIT_500, AdapterMode.NORMAL);
        DiagnosticChannel channel = ChannelFactory.create(replay, time).open(route(s));
        String where = s.link() + " session at capture frame " + s.frameIndices().getFirst();
        Deque<Message> expected = new ArrayDeque<>();
        int requests = 0;
        int keepAlives = 0;
        int pending = 0;
        for (int i = 0; i < events.size(); i++) {
            Event e = events.get(i);
            if (e instanceof Message m && m.fromTester()) {
                drain(channel, expected, where);
                List<Message> answers = new ArrayList<>();
                for (int j = i + 1; j < events.size(); j++) {
                    if (events.get(j) instanceof Message x) {
                        if (x.fromTester()) {
                            break;
                        }
                        answers.add(x);
                    }
                }
                Response r = channel.request(m.payload(), TIMEOUT);
                requests++;
                String what = where + ", request " + m.hex();
                if (answers.isEmpty()) {
                    assertInstanceOf(Response.TimedOut.class, r, what);
                    continue;
                }
                assertEquals(answers.getFirst().hex(), assertInstanceOf(Response.Received.class, r, what).hex(), what);
                if (answers.getFirst().hex().matches("7F .. 78")) {
                    pending++;
                }
                expected.addAll(answers.subList(1, answers.size()));
            } else if (e instanceof Control c && c.fromTester() && c.opcode() == 0xA3) {
                drain(channel, expected, where);
                assertTrue(channel.keepAlive(), where + ": A3");
                keepAlives++;
            } else if (e instanceof Control c && c.fromTester() && c.opcode() == 0xA8) {
                drain(channel, expected, where);
                channel.close();
            }
        }
        drain(channel, expected, where);
        if (whole) {
            assertTrue(!channel.isOpen(), where + ": closed with A8");
            assertEquals(0, replay.remainingFrames(), where + ": every recorded frame played");
        } else {
            assertEquals(java.util.Optional.empty(), replay.nextExpectedFrame(), where + ": every tester frame before the cut sent");
            assertEquals(0, replay.remainingFrames(), where + ": every module frame before the cut received");
        }
        return new Replayed(s.link(), requests, whole, keepAlives, pending);
    }

    private static void drain(DiagnosticChannel channel, Deque<Message> expected, String where) throws InterruptedException {
        while (!expected.isEmpty()) {
            Message next = expected.poll();
            Response r = channel.awaitResponse(TIMEOUT);
            assertEquals(next.hex(), assertInstanceOf(Response.Received.class, r, where + " awaiting " + next.hex()).hex(), where);
        }
    }

    private static ApplicationProtocol protocol(Session s) {
        return s.link() instanceof Link.Tp20 ? ApplicationProtocol.KWP2000 : ApplicationProtocol.UDS;
    }

    private static ModuleRoute route(Session s) {
        return switch (s.link()) {
            case Link.Tp20 t -> new ModuleRoute.Tp20(t.address());
            case Link.IsoTp i -> new ModuleRoute.IsoTp(i.requestId(), i.responseId(), ApplicationProtocol.UDS);
        };
    }

    // ------------------------------------------------------------------ capture ECU

    private record Stack(SimulatedAdapter adapter, ChannelFactory factory, CaptureEcu ecu) {
    }

    private static Stack ecuStack() {
        FakeTimeSource time = FakeTimeSource.autoAdvancing();
        CaptureEcu ecu = CaptureEcu.of(RealCaptures.autoScan(), RealCaptures.freezeFrame01());
        SimulatedAdapter adapter = new SimulatedAdapter(time, ecu);
        adapter.open(Bitrate.KBIT_500, AdapterMode.NORMAL);
        return new Stack(adapter, ChannelFactory.create(adapter, time), ecu);
    }

    private static String received(Response r) {
        return assertInstanceOf(Response.Received.class, r, r.toString()).hex();
    }

    @Test
    @DisplayName("capture ECU, engine: setup, 10 89, 1A 9B, 18 00 FF 00, 1A 86 (78 with the length flag), A3, 12 00 04 twice")
    void engineFromTheCapture() throws InterruptedException {
        Stack st = ecuStack();
        try (DiagnosticChannel ch = st.factory().open(new ModuleRoute.Tp20(0x01))) {
            assertEquals("50 89", received(ch.request(Hex.parse("10 89"), TIMEOUT)));
            assertTrue(received(ch.request(Hex.parse("1A 9B"), TIMEOUT)).startsWith("5A 9B 30 33 4C 39 39 37 30 33 30 4D"));
            assertEquals("58 0C 2B A6 E0 1A 58 60 19 46 20 04 01 61 24 0F E0 06 91 20 05 44 20 04 01 22 05 44 21 00 87 "
                    + "21 00 87 62 00 87 26", received(ch.request(Hex.parse("18 00 FF 00"), TIMEOUT)));
            assertEquals("7F 1A 78", received(ch.request(Hex.parse("1A 86"), TIMEOUT)));
            assertTrue(received(ch.awaitResponse(TIMEOUT)).startsWith("5A 86 0F"), "the real answer after the 78");
            assertTrue(ch.keepAlive());
            String first = received(ch.request(Hex.parse("12 00 04 04 01"), TIMEOUT));
            String second = received(ch.request(Hex.parse("12 00 04 04 01"), TIMEOUT));
            assertTrue(first.startsWith("52 00 6C 01 61"), first);
            assertTrue(second.startsWith("52 00 6C 01 22"), "the second stored record of 04 01: " + second);
        }
        // The 78 went on the wire with the flag, as recorded: 1x 80 03 7F 1A 78.
        assertTrue(st.adapter().sentFrames().stream().anyMatch(f -> f.id() == 0x740 && Hex.format(f.data()).equals("A8")));
        assertEquals(List.of(), st.ecu().protocolErrors());
        assertEquals(List.of(), st.ecu().unseen());
    }

    @Test
    @DisplayName("capture ECU, gateway: 22 04 A1 (21 packets, ACK at the block boundary), then an unseen DID gets 7F 22 31")
    void gatewayFromTheCapture() throws InterruptedException {
        Stack st = ecuStack();
        try (DiagnosticChannel ch = st.factory().open(new ModuleRoute.Tp20(0x1F))) {
            assertEquals("50 89", received(ch.request(Hex.parse("10 89"), TIMEOUT)));
            String list = received(ch.request(Hex.parse("22 04 A1"), TIMEOUT));
            assertEquals(3 + 35 * 4, Hex.parse(list).length);
            assertTrue(list.startsWith("62 04 A1 01 01 05 10"));
            assertEquals("7F 22 31", received(ch.request(Hex.parse("22 F1 90"), TIMEOUT)));
        }
        assertEquals(1, st.ecu().unseen().size());
        assertEquals("22 F1 90", st.ecu().unseen().getFirst().request());
        assertEquals(List.of(), st.ecu().protocolErrors());
        long acks = st.adapter().sentFrames().stream().filter(f -> f.id() == 0x32E && (f.dataByte(0) >> 4) == 0xB).count();
        assertTrue(acks >= 4, "ACKs for 50 89, the block boundary, the list's last packet, 7F 22 31");
    }

    @Test
    @DisplayName("capture ECU, airbag over ISO-TP: 10 03, 19 02 AE answered 7F 19 78 then the list; frames padded 55")
    void airbagFromTheCapture() throws InterruptedException {
        Stack st = ecuStack();
        try (DiagnosticChannel ch = st.factory().open(new ModuleRoute.IsoTp(0x715, 0x77F, ApplicationProtocol.UDS))) {
            assertTrue(received(ch.request(Hex.parse("10 03"), TIMEOUT)).startsWith("50 03"));
            assertEquals("7F 19 78", received(ch.request(Hex.parse("19 02 AE"), TIMEOUT)));
            assertTrue(received(ch.awaitResponse(TIMEOUT)).startsWith("59 02"));
            String name = received(ch.request(Hex.parse("22 F1 9E"), TIMEOUT));
            assertTrue(name.startsWith("62 F1 9E"), "multi-frame answer after our 30 00 01");
        }
        for (CanFrame f : st.adapter().sentFrames()) {
            assertEquals(0x715, f.id());
            assertEquals(8, f.length());
        }
        assertNotEquals(0, st.adapter().sentFrames().stream().filter(f -> Hex.format(f.data()).equals("30 00 01 55 55 55 55 55")).count());
        assertEquals(List.of(), st.ecu().protocolErrors());
    }
}
