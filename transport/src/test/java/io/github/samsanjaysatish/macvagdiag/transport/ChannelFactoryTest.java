package io.github.samsanjaysatish.macvagdiag.transport;

import static io.github.samsanjaysatish.macvagdiag.transport.ScriptedBus.reply;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.can.AdapterMode;
import io.github.samsanjaysatish.macvagdiag.can.Bitrate;
import io.github.samsanjaysatish.macvagdiag.can.CanFrame;
import io.github.samsanjaysatish.macvagdiag.can.FakeTimeSource;
import io.github.samsanjaysatish.macvagdiag.can.Hex;
import io.github.samsanjaysatish.macvagdiag.can.SimulatedAdapter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** {@link ChannelFactory}, {@link GuardedChannel} and the CAN ID defense in depth. */
class ChannelFactoryTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(1);
    private static final ModuleRoute ENGINE_UDS = new ModuleRoute.IsoTp(0x7E0, 0x7E8, ApplicationProtocol.UDS);

    private final FakeTimeSource time = FakeTimeSource.autoAdvancing();
    private SimulatedAdapter adapter;

    private SimulatedAdapter adapter(SimulatedAdapter.Responder responder, AdapterMode mode) {
        adapter = new SimulatedAdapter(time, responder);
        adapter.open(Bitrate.KBIT_500, mode);
        return adapter;
    }

    @AfterEach
    void tearDown() {
        if (adapter != null) {
            adapter.close();
        }
    }

    @Test
    @DisplayName("TP 2.0 end to end through the factory: setup, 10 89 → 50 89, close with A8")
    void tp20EndToEnd() throws Exception {
        ScriptedBus script = new ScriptedBus(time)
                .expect(0x200, "01 C0 00 10 00 03 01", reply(0x201, "00 D0 00 03 40 07 01"))
                .expect(0x740, "A0 0F 8A FF 32 FF", reply(0x300, "A1 0F 8A FF 4A FF"))
                .expect(0x740, "10 00 02 10 89", reply(0x300, "B1"), reply(0x300, "10 00 02 50 89"))
                .expect(0x740, "B1")
                .expect(0x740, "A8", reply(0x300, "A8"));
        ChannelFactory factory = ChannelFactory.create(adapter(script, AdapterMode.NORMAL), time);

        try (DiagnosticChannel ch = factory.open(new ModuleRoute.Tp20(0x01))) {
            assertInstanceOf(GuardedChannel.class, ch);
            assertEquals(ApplicationProtocol.KWP2000, ch.protocol());
            assertEquals(new ModuleRoute.Tp20(0x01), ch.route());
            assertEquals(new Response.Received(Hex.parse("50 89")), ch.request(Hex.parse("10 89"), TIMEOUT));
        }
        script.assertComplete();
    }

    @Test
    @DisplayName("ISO-TP end to end through the factory: 03 22 F1 87 55 55 55 55 → 62 F1 87 ...")
    void isoTpEndToEnd() throws Exception {
        ScriptedBus script = new ScriptedBus(time)
                .expect(0x7E0, "03 22 F1 87 55 55 55 55", reply(0x7E8, "05 62 F1 87 30 33 AA AA"));
        ChannelFactory factory = ChannelFactory.create(adapter(script, AdapterMode.NORMAL), time);

        DiagnosticChannel ch = factory.open(ENGINE_UDS);
        assertInstanceOf(GuardedChannel.class, ch);
        assertEquals(ApplicationProtocol.UDS, ch.protocol());
        assertEquals(new Response.Received(Hex.parse("62 F1 87 30 33")), ch.request(Hex.parse("22 F1 87"), TIMEOUT));
        script.assertComplete();
    }

    @Test
    @DisplayName("KWP2000 over ISO-TP: the guard uses the route's protocol (10 89 allowed, 10 03 refused)")
    void kwpOverIsoTp() throws Exception {
        ScriptedBus script = new ScriptedBus(time)
                .expect(0x7E0, "02 10 89 55 55 55 55 55", reply(0x7E8, "02 50 89 AA AA AA AA AA"));
        ChannelFactory factory = ChannelFactory.create(adapter(script, AdapterMode.NORMAL), time);
        DiagnosticChannel ch = factory.open(new ModuleRoute.IsoTp(0x7E0, 0x7E8, ApplicationProtocol.KWP2000));
        assertThrows(ForbiddenRequestException.class, () -> ch.request(Hex.parse("10 03"), TIMEOUT));
        assertEquals(new Response.Received(Hex.parse("50 89")), ch.request(Hex.parse("10 89"), TIMEOUT));
        script.assertComplete();
    }

    @Test
    @DisplayName("only one channel at a time: a second open throws CHANNEL_ALREADY_OPEN until the first is closed")
    void oneChannelAtATime() throws Exception {
        ChannelFactory factory = ChannelFactory.create(adapter(FakeEcus.isoTp(), AdapterMode.NORMAL), time);
        DiagnosticChannel first = factory.open(ENGINE_UDS);
        assertEquals(TransportException.Reason.CHANNEL_ALREADY_OPEN,
                assertThrows(TransportException.class, () -> factory.open(ENGINE_UDS)).reason());
        first.close();
        DiagnosticChannel second = factory.open(ENGINE_UDS);
        assertTrue(second.isOpen());
    }

    @Test
    @DisplayName("adapter closed or in LISTEN_ONLY: ADAPTER_NOT_READY and nothing sent")
    void adapterNotReady() throws Exception {
        SimulatedAdapter listenOnly = adapter(FakeEcus.tp20(), AdapterMode.LISTEN_ONLY);
        ChannelFactory factory = ChannelFactory.create(listenOnly, time);
        assertEquals(TransportException.Reason.ADAPTER_NOT_READY,
                assertThrows(TransportException.class, () -> factory.open(new ModuleRoute.Tp20(0x01))).reason());
        listenOnly.close();
        assertEquals(TransportException.Reason.ADAPTER_NOT_READY,
                assertThrows(TransportException.class, () -> factory.open(ENGINE_UDS)).reason());
        assertEquals(List.of(), listenOnly.sentFrames());
    }

    @ParameterizedTest(name = "ISO-TP route {0}/{1} is refused: DISALLOWED_CAN_ID, nothing sent")
    @CsvSource({"7DF, 7E8", "7E0, 7E9", "123, 12B", "710, 77A", "200, 201", "740, 300", "7E8, 7E0"})
    void unknownIsoTpRoutesRefused(String request, String response) {
        ChannelFactory factory = ChannelFactory.create(adapter(FakeEcus.isoTp(), AdapterMode.NORMAL), time);
        ModuleRoute route = new ModuleRoute.IsoTp(Integer.parseInt(request, 16), Integer.parseInt(response, 16),
                ApplicationProtocol.UDS);
        assertEquals(TransportException.Reason.DISALLOWED_CAN_ID,
                assertThrows(TransportException.class, () -> factory.open(route)).reason());
        assertEquals(List.of(), adapter.sentFrames());
    }

    @Test
    @DisplayName("route validation: TP 2.0 address 00 / 80, ISO-TP IDs above 7FF or equal are rejected")
    void routeValidation() {
        assertThrows(IllegalArgumentException.class, () -> new ModuleRoute.Tp20(0x00));
        assertThrows(IllegalArgumentException.class, () -> new ModuleRoute.Tp20(0x80));
        assertThrows(IllegalArgumentException.class, () -> new ModuleRoute.IsoTp(0x800, 0x7E8, ApplicationProtocol.UDS));
        assertThrows(IllegalArgumentException.class, () -> new ModuleRoute.IsoTp(0x7E0, 0x7E0, ApplicationProtocol.UDS));
        assertThrows(NullPointerException.class, () -> new ModuleRoute.IsoTp(0x7E0, 0x7E8, null));
        assertEquals(ApplicationProtocol.KWP2000, new ModuleRoute.Tp20(0x1F).protocol());
    }

    @Test
    @DisplayName("audit listener sees each request before it is sent, and each rejection")
    void auditListener() throws Exception {
        List<String> events = new ArrayList<>();
        ChannelFactory factory = ChannelFactory.create(adapter(FakeEcus.isoTp(), AdapterMode.NORMAL), time,
                new RequestAuditListener() {
                    @Override
                    public void sending(ModuleRoute route, ApplicationProtocol protocol, byte[] payload) {
                        events.add("sending " + route + " " + Hex.format(payload) + " (frames so far: "
                                + adapter.sentFrames().size() + ")");
                    }

                    @Override
                    public void rejected(ModuleRoute route, ForbiddenRequestException rejection) {
                        events.add("rejected " + route + " " + Hex.format(rejection.payload()));
                    }
                });
        DiagnosticChannel ch = factory.open(ENGINE_UDS);
        ch.request(Hex.parse("22 F1 90"), TIMEOUT);
        assertThrows(ForbiddenRequestException.class, () -> ch.request(Hex.parse("2E F1 90 00"), TIMEOUT));
        assertEquals(List.of(
                "sending ISO-TP 7E0/7E8 UDS 22 F1 90 (frames so far: 0)",
                "rejected ISO-TP 7E0/7E8 UDS 2E F1 90 00"), events);
    }

    @Test
    @DisplayName("if the audit listener fails, the request is not sent")
    void auditFailureBlocksSend() throws Exception {
        RuntimeException auditDown = new IllegalStateException("audit log unavailable");
        ChannelFactory factory = ChannelFactory.create(adapter(FakeEcus.isoTp(), AdapterMode.NORMAL), time,
                new RequestAuditListener() {
                    @Override
                    public void sending(ModuleRoute route, ApplicationProtocol protocol, byte[] payload) {
                        throw auditDown;
                    }
                });
        DiagnosticChannel ch = factory.open(ENGINE_UDS);
        assertSame(auditDown, assertThrows(IllegalStateException.class, () -> ch.request(Hex.parse("22 F1 90"), TIMEOUT)));
        assertEquals(List.of(), adapter.sentFrames());
    }

    @Test
    @DisplayName("a failing rejection listener doesn't hide the rejection")
    void rejectionListenerFailure() throws Exception {
        RuntimeException listenerBug = new IllegalStateException("listener bug");
        ChannelFactory factory = ChannelFactory.create(adapter(FakeEcus.isoTp(), AdapterMode.NORMAL), time,
                new RequestAuditListener() {
                    @Override
                    public void rejected(ModuleRoute route, ForbiddenRequestException rejection) {
                        throw listenerBug;
                    }
                });
        DiagnosticChannel ch = factory.open(ENGINE_UDS);
        ForbiddenRequestException e = assertThrows(ForbiddenRequestException.class,
                () -> ch.request(Hex.parse("11 01"), TIMEOUT));
        assertEquals(List.of(listenerBug), List.of(e.getSuppressed()));
        assertEquals(List.of(), adapter.sentFrames());
    }

    @Test
    @DisplayName("the guarded channel copies the payload: changing the caller's array afterwards changes nothing")
    void payloadCopied() throws Exception {
        List<String> audited = new ArrayList<>();
        ChannelFactory factory = ChannelFactory.create(adapter(FakeEcus.isoTp(), AdapterMode.NORMAL), time,
                new RequestAuditListener() {
                    @Override
                    public void sending(ModuleRoute route, ApplicationProtocol protocol, byte[] payload) {
                        audited.add(Hex.format(payload));
                        payload[0] = 0x2E; // a listener scribbling on its copy can't change what is sent
                    }
                });
        DiagnosticChannel ch = factory.open(ENGINE_UDS);
        byte[] payload = Hex.parse("22 F1 90");
        ch.request(payload, TIMEOUT);
        assertEquals(List.of("22 F1 90"), audited);
        assertEquals(List.of(CanFrame.standardHex(0x7E0, "03 22 F1 90 55 55 55 55")), adapter.sentFrames());
    }

    @Test
    @DisplayName("negative or null timeout is refused; a closed channel answers CHANNEL_CLOSED without sending")
    void timeoutAndClosed() throws Exception {
        ChannelFactory factory = ChannelFactory.create(adapter(FakeEcus.isoTp(), AdapterMode.NORMAL), time);
        DiagnosticChannel ch = factory.open(ENGINE_UDS);
        assertThrows(IllegalArgumentException.class, () -> ch.request(Hex.parse("22 F1 90"), Duration.ofMillis(-1)));
        assertThrows(NullPointerException.class, () -> ch.request(Hex.parse("22 F1 90"), null));
        ch.close();
        assertEquals(Response.Failed.Kind.CHANNEL_CLOSED,
                assertInstanceOf(Response.Failed.class, ch.request(Hex.parse("22 F1 90"), TIMEOUT)).kind());
        assertEquals(Response.Failed.Kind.CHANNEL_CLOSED,
                assertInstanceOf(Response.Failed.class, ch.awaitResponse(TIMEOUT)).kind());
        assertThrows(ForbiddenRequestException.class, () -> ch.request(Hex.parse("27 01"), TIMEOUT),
                "the guard still runs first on a closed channel");
        assertEquals(List.of(), adapter.sentFrames());
    }

    // ------------------------------------------------------------------ BusGate: CAN ID defense in depth

    @ParameterizedTest(name = "BusGate refuses to permit {0}")
    @CsvSource({"000", "100", "1FF", "201", "300", "7DF", "7E8", "73F", "7C0", "7FF",
            // functional 700 (not approved) and its neighbours
            "6FF", "700", "701",
            // neighbours of the TP 2.0 tx IDs approved 2026-11-15 (32E, 338, 33C)
            "32D", "32F", "337", "339", "33B", "33D",
            // neighbours of the ISO-TP request IDs approved 2026-11-15 (711, 714, 715, 731, 732; 76B is in 740..7BF)
            "710", "712", "713", "716", "730", "733",
            // 7D5 (telephone's response) is receive-only. The other new response IDs (77B 77E 77F 79B 79C) lie
            // inside the TP 2.0 range 740..7BF and so were already permitted transmit IDs; pairing is enforced at open.
            "7D5", "7D4", "7D6"})
    void busGateRefusesIdsOutsideTheSet(String id) {
        BusGate gate = new BusGate(adapter(FakeEcus.isoTp(), AdapterMode.NORMAL), time);
        TransportException e = assertThrows(TransportException.class, () -> gate.permit(Integer.parseInt(id, 16)));
        assertEquals(TransportException.Reason.DISALLOWED_CAN_ID, e.reason());
    }

    @Test
    @DisplayName("BusGate sends only on the one permitted ID, never on 29-bit IDs")
    void busGateSendsOnlyOnPermittedId() {
        BusGate gate = new BusGate(adapter(FakeEcus.isoTp(), AdapterMode.NORMAL), time);
        assertThrows(TransportException.class, () -> gate.send(CanFrame.standardHex(0x7E0, "02 3E 00")),
                "nothing permitted yet");
        gate.permit(0x7E0);
        assertThrows(TransportException.class, () -> gate.send(CanFrame.standardHex(0x7E1, "02 3E 00")),
                "7E1 is in the set but not this channel's ID");
        assertThrows(TransportException.class, () -> gate.send(CanFrame.extendedHex(0x7E0, "02 3E 00")));
        gate.permit(0x740);
        assertThrows(TransportException.class, () -> gate.send(CanFrame.standardHex(0x7E0, "02 3E 00")),
                "a new permit replaces the old one");
        assertEquals(List.of(), adapter.sentFrames());
        gate.send(CanFrame.standardHex(0x740, "A3"));
        assertEquals(List.of(CanFrame.standardHex(0x740, "A3")), adapter.sentFrames());
    }

    @Test
    @DisplayName("the fixed transmit set: 200, 32E 338 33C, 711 714 715 731 732, 740..7BF, 7E0, 7E1 and nothing else")
    void fixedIdSet() {
        List<Integer> permitted = new ArrayList<>();
        for (int id = 0; id <= 0x7FF; id++) {
            if (DiagnosticCanIds.isPermittedTransmitId(id)) {
                permitted.add(id);
            }
        }
        List<Integer> expected = new ArrayList<>(List.of(0x200, 0x32E, 0x338, 0x33C, 0x711, 0x714, 0x715, 0x731, 0x732));
        for (int id = 0x740; id <= 0x7BF; id++) {
            expected.add(id); // includes 76B
        }
        expected.add(0x7E0);
        expected.add(0x7E1);
        assertEquals(expected, permitted);
    }

    @Test
    @DisplayName("TP 2.0 negotiable tx IDs: 32E 338 33C (exact) and 740..7BF, nothing else")
    void negotiableTp20TxIds() {
        List<Integer> negotiable = new ArrayList<>();
        for (int id = 0; id <= 0x7FF; id++) {
            if (DiagnosticCanIds.isNegotiableTp20TxId(id)) {
                negotiable.add(id);
            }
        }
        List<Integer> expected = new ArrayList<>(List.of(0x32E, 0x338, 0x33C));
        for (int id = 0x740; id <= 0x7BF; id++) {
            expected.add(id);
        }
        assertEquals(expected, negotiable);
    }

    @Test
    @DisplayName("ISO-TP pairs are exactly 711/77B 714/77E 715/77F 731/79B 732/79C 76B/7D5 7E0/7E8 7E1/7E9")
    void isoTpPairsPinned() {
        assertEquals(java.util.Map.of(0x711, 0x77B, 0x714, 0x77E, 0x715, 0x77F, 0x731, 0x79B, 0x732, 0x79C,
                0x76B, 0x7D5, 0x7E0, 0x7E8, 0x7E1, 0x7E9), DiagnosticCanIds.ISO_TP_REQUEST_TO_RESPONSE);
    }

    @ParameterizedTest(name = "ISO-TP pair {0} → {1} from the capture opens and sends on {0} only")
    @CsvSource({"714, 77E", "732, 79C", "715, 77F", "711, 77B", "731, 79B", "76B, 7D5"})
    void capturedIsoTpPairsOpen(String request, String response) throws Exception {
        int req = Integer.parseInt(request, 16);
        int resp = Integer.parseInt(response, 16);
        ScriptedBus script = new ScriptedBus(time)
                .expect(req, "03 22 F1 87 55 55 55 55", reply(resp, "05 62 F1 87 30 33 AA AA"));
        ChannelFactory factory = ChannelFactory.create(adapter(script, AdapterMode.NORMAL), time);

        try (DiagnosticChannel ch = factory.open(new ModuleRoute.IsoTp(req, resp, ApplicationProtocol.UDS))) {
            assertEquals(new Response.Received(Hex.parse("62 F1 87 30 33")), ch.request(Hex.parse("22 F1 87"), TIMEOUT));
        }
        script.assertComplete();
        assertEquals(List.of(CanFrame.standardHex(req, "03 22 F1 87 55 55 55 55")), adapter.sentFrames());
    }

    @Test
    @DisplayName("mismatched pairs (e.g. 714 → 79C), ±1 neighbours of every pair, 700 and 7DF: all refused, nothing sent")
    void mismatchedNeighbouringAndFunctionalRoutesRefused() {
        java.util.Map<Integer, Integer> pairs = DiagnosticCanIds.ISO_TP_REQUEST_TO_RESPONSE;
        List<int[]> routes = new ArrayList<>();
        for (var a : pairs.entrySet()) {
            for (var b : pairs.entrySet()) {
                if (!a.getKey().equals(b.getKey())) {
                    routes.add(new int[] {a.getKey(), b.getValue()}); // another module's response ID
                }
            }
            int req = a.getKey();
            int resp = a.getValue();
            routes.add(new int[] {req - 1, resp});
            routes.add(new int[] {req + 1, resp});
            routes.add(new int[] {req, resp - 1});
            routes.add(new int[] {req, resp + 1});
            routes.add(new int[] {0x700, resp});
            routes.add(new int[] {0x7DF, resp});
            routes.add(new int[] {resp, req}); // reversed
        }
        int refused = 0;
        for (int[] r : routes) {
            if (pairs.containsKey(r[0]) && pairs.get(r[0]) == r[1]) {
                continue; // a neighbour that happens to be another valid pair (none today)
            }
            ChannelFactory factory = ChannelFactory.create(adapter(FakeEcus.isoTp(), AdapterMode.NORMAL), time);
            ModuleRoute route = new ModuleRoute.IsoTp(r[0], r[1], ApplicationProtocol.UDS);
            TransportException e = assertThrows(TransportException.class, () -> factory.open(route),
                    String.format("%03X/%03X", r[0], r[1]));
            assertEquals(TransportException.Reason.DISALLOWED_CAN_ID, e.reason());
            assertEquals(List.of(), adapter.sentFrames());
            adapter.close();
            refused++;
        }
        assertEquals(routes.size(), refused, "no neighbour is accidentally a valid pair");
    }
}
