package io.github.samsanjaysatish.macvagdiag.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.can.FakeTimeSource;
import io.github.samsanjaysatish.macvagdiag.can.Hex;
import io.github.samsanjaysatish.macvagdiag.transport.ApplicationProtocol;
import io.github.samsanjaysatish.macvagdiag.transport.DiagnosticChannel;
import io.github.samsanjaysatish.macvagdiag.transport.FakeDiagnosticChannel;
import io.github.samsanjaysatish.macvagdiag.transport.FakeDiagnosticChannel.Call;
import io.github.samsanjaysatish.macvagdiag.transport.ForbiddenRequestException;
import io.github.samsanjaysatish.macvagdiag.transport.ModuleRoute;
import io.github.samsanjaysatish.macvagdiag.transport.RequestGuard;
import io.github.samsanjaysatish.macvagdiag.transport.Response;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** {@code 7F [SID] [NRC]} handling in {@link Exchange}, through the services, on fake channels and fake time. */
class NegativeResponseTest {

    private final FakeTimeSource time = new FakeTimeSource();

    private static List<Call.Kind> kinds(FakeDiagnosticChannel ch) {
        return ch.calls().stream().map(Call::kind).toList();
    }

    // ---- plain NRCs ----

    @ParameterizedTest(name = "UDS 19 02 FF -> 7F 19 {0}: {1}")
    @CsvSource({
            "11, SERVICE_NOT_SUPPORTED, serviceNotSupported",
            "12, SUB_FUNCTION_NOT_SUPPORTED, subFunctionNotSupported",
            "22, CONDITIONS_NOT_CORRECT, conditionsNotCorrect",
            "31, REQUEST_OUT_OF_RANGE, requestOutOfRange",
    })
    @DisplayName("UDS: 7F 19 11 / 12 / 22 / 31 give a typed Negative with a readable message, no retry")
    void udsNegatives(String nrcHex, Nrc nrc, String isoName) throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.uds().on("19 02 FF", "7F 19 " + nrcHex);
        DiagResult<DtcList> r = new UdsDiagnosticService(ch, time).readDtcs();
        DiagResult.Negative<?> n = assertInstanceOf(DiagResult.Negative.class, r);
        assertEquals(0x19, n.response().serviceId());
        assertEquals(Optional.of(nrc), n.response().nrc());
        assertEquals(RawBytes.ofHex("7F 19 " + nrcHex), n.response().raw());
        assertTrue(r.message().contains(isoName), r.message());
        assertTrue(r.message().contains("7F 19 " + nrcHex), r.message());
        assertFalse(r.retryable());
        assertEquals(List.of(Call.Kind.REQUEST), kinds(ch));
    }

    @ParameterizedTest(name = "KWP 18 02 -> 7F 18 {0}: {1}")
    @CsvSource({
            "11, SERVICE_NOT_SUPPORTED",
            "12, SUB_FUNCTION_NOT_SUPPORTED",
            "22, CONDITIONS_NOT_CORRECT",
            "31, REQUEST_OUT_OF_RANGE",
    })
    @DisplayName("KWP: 7F 18 11 / 12 / 22 / 31 give a typed Negative, no retry")
    void kwpNegatives(String nrcHex, Nrc nrc) throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.kwp().on("18 02", "7F 18 " + nrcHex);
        DiagResult<DtcList> r = new KwpDiagnosticService(ch, time).readDtcs();
        DiagResult.Negative<?> n = assertInstanceOf(DiagResult.Negative.class, r);
        assertEquals(Optional.of(nrc), n.response().nrc());
        assertTrue(r.message().contains(nrc.isoName()));
        assertEquals(List.of(Call.Kind.REQUEST), kinds(ch));
    }

    @Test
    @DisplayName("unknown NRC 7F 19 99 is kept as its raw code with an 'unknown' message")
    void unknownNrc() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.uds().on("19 02 FF", "7F 19 99");
        DiagResult.Negative<?> n = assertInstanceOf(DiagResult.Negative.class, new UdsDiagnosticService(ch, time).readDtcs());
        assertEquals(0x99, n.response().code());
        assertEquals(Optional.empty(), n.response().nrc());
        assertTrue(n.message().contains("unknown negative response code 0x99"), n.message());
    }

    @Test
    @DisplayName("extra bytes after the NRC (7F 19 31 00 AA) are kept in the raw negative response")
    void extraBytesKept() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.uds().on("19 02 FF", "7F 19 31 00 AA");
        DiagResult.Negative<?> n = assertInstanceOf(DiagResult.Negative.class, new UdsDiagnosticService(ch, time).readDtcs());
        assertEquals(RawBytes.ofHex("7F 19 31 00 AA"), n.response().raw());
    }

    // ---- 21 busy ----

    @Test
    @DisplayName("7F 19 21 busy gives a retryable Busy and is NOT retried here")
    void busy() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.uds().on("19 02 FF", "7F 19 21");
        DiagResult<DtcList> r = new UdsDiagnosticService(ch, time).readDtcs();
        DiagResult.Busy<?> b = assertInstanceOf(DiagResult.Busy.class, r);
        assertEquals(Optional.of(Nrc.BUSY_REPEAT_REQUEST), b.response().nrc());
        assertTrue(r.retryable());
        assertTrue(r.message().contains("busyRepeatRequest"));
        assertEquals(List.of(Call.Kind.REQUEST), kinds(ch), "one request, no resend, no wait");
    }

    @Test
    @DisplayName("KWP 7F 18 21 busy is Busy and retryable too")
    void kwpBusy() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.kwp().on("18 02", "7F 18 21");
        DiagResult<DtcList> r = new KwpDiagnosticService(ch, time).readDtcs();
        assertInstanceOf(DiagResult.Busy.class, r);
        assertTrue(r.retryable());
        assertEquals(List.of("18 02"), ch.requestsHex());
    }

    // ---- 78 pending ----

    @Test
    @DisplayName("19 02 FF -> 7F 19 78 then 59 02 FF 04 01 00 2F: waits with awaitResponse and the pending timeout, no resend")
    void pendingThenAnswer() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.uds().on("19 02 FF", "7F 19 78", "59 02 FF 04 01 00 2F");
        DiagResult<DtcList> r = new UdsDiagnosticService(ch, time).readDtcs();
        assertTrue(r.isSuccess(), r.message());
        assertEquals(List.of(new UdsDtc(0x040100, 0x2F)), r.valueIfSuccess().orElseThrow().dtcs());
        assertEquals(List.of(
                new Call(Call.Kind.REQUEST, Hex.parse("19 02 FF"), DiagTimings.DEFAULT_REQUEST_TIMEOUT),
                new Call(Call.Kind.AWAIT, new byte[0], DiagTimings.RESPONSE_PENDING_TIMEOUT)), ch.calls());
        assertTrue(ch.allScriptUsed());
    }

    @Test
    @DisplayName("KWP 18 02 -> 7F 18 78, 7F 18 78, 58 01 41 2C A3: two waits, one request")
    void kwpPendingTwice() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.kwp().on("18 02", "7F 18 78", "7F 18 78", "58 01 41 2C A3");
        DiagResult<DtcList> r = new KwpDiagnosticService(ch, time).readDtcs();
        assertTrue(r.isSuccess(), r.message());
        assertEquals(List.of(Call.Kind.REQUEST, Call.Kind.AWAIT, Call.Kind.AWAIT), kinds(ch));
    }

    @Test
    @DisplayName("7F 19 78 then 7F 19 31: the final negative answer is returned")
    void pendingThenNegative() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.uds().on("19 02 FF", "7F 19 78", "7F 19 31");
        DiagResult.Negative<?> n = assertInstanceOf(DiagResult.Negative.class, new UdsDiagnosticService(ch, time).readDtcs());
        assertEquals(0x31, n.response().code());
    }

    @Test
    @DisplayName("exactly the cap of 78s (3) then the answer still succeeds")
    void pendingUpToCap() throws InterruptedException {
        DiagTimings timings = new DiagTimings(Duration.ofSeconds(1), Duration.ofSeconds(5), 3, Duration.ofSeconds(30));
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.uds()
                .on("19 02 FF", "7F 19 78", "7F 19 78", "7F 19 78", "59 02 FF");
        assertTrue(new UdsDiagnosticService(ch, time, timings).readDtcs().isSuccess());
        assertEquals(List.of(Call.Kind.REQUEST, Call.Kind.AWAIT, Call.Kind.AWAIT, Call.Kind.AWAIT), kinds(ch));
    }

    @Test
    @DisplayName("7F 19 78 repeated past the cap (4 with cap 3) gives PendingLimitExceeded without resending")
    void pendingPastCountCap() throws InterruptedException {
        DiagTimings timings = new DiagTimings(Duration.ofSeconds(1), Duration.ofSeconds(5), 3, Duration.ofSeconds(30));
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.uds()
                .on("19 02 FF", "7F 19 78", "7F 19 78", "7F 19 78", "7F 19 78", "59 02 FF");
        DiagResult<DtcList> r = new UdsDiagnosticService(ch, time, timings).readDtcs();
        DiagResult.PendingLimitExceeded<?> p = assertInstanceOf(DiagResult.PendingLimitExceeded.class, r);
        assertEquals(4, p.pendingResponses());
        assertTrue(p.limit().contains("more than 3"), p.limit());
        assertFalse(r.retryable());
        assertEquals(List.of(Call.Kind.REQUEST, Call.Kind.AWAIT, Call.Kind.AWAIT, Call.Kind.AWAIT), kinds(ch));
    }

    @Test
    @DisplayName("default cap: an ECU sending 7F 19 78 forever stops after MAX_RESPONSE_PENDING waits")
    void pendingForeverDefaultCap() throws InterruptedException {
        String[] answers = new String[DiagTimings.MAX_RESPONSE_PENDING + 50];
        java.util.Arrays.fill(answers, "7F 19 78");
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.uds().on("19 02 FF", answers);
        DiagResult<DtcList> r = new UdsDiagnosticService(ch, time).readDtcs();
        assertInstanceOf(DiagResult.PendingLimitExceeded.class, r);
        assertEquals(1, ch.requestsHex().size());
        assertEquals(DiagTimings.MAX_RESPONSE_PENDING,
                ch.calls().stream().filter(c -> c.kind() == Call.Kind.AWAIT).count());
    }

    @Test
    @DisplayName("78s every 4 s with a 10 s total cap: waits are 5 s, 5 s, then clipped to 2 s, then PendingLimitExceeded")
    void pendingPastTotalTimeCap() throws InterruptedException {
        DiagTimings timings = new DiagTimings(Duration.ofSeconds(1), Duration.ofSeconds(5), 100, Duration.ofSeconds(10));
        String[] answers = new String[20];
        java.util.Arrays.fill(answers, "7F 19 78");
        FakeDiagnosticChannel fake = FakeDiagnosticChannel.uds().on("19 02 FF", answers);
        ClockAdvancingChannel ch = new ClockAdvancingChannel(fake, time, Duration.ofSeconds(4));
        DiagResult<DtcList> r = new UdsDiagnosticService(ch, time, timings).readDtcs();
        DiagResult.PendingLimitExceeded<?> p = assertInstanceOf(DiagResult.PendingLimitExceeded.class, r, r.message());
        assertEquals(3, p.pendingResponses());
        assertTrue(p.limit().contains("10000 ms"), p.limit());
        List<Duration> waits = fake.calls().stream().filter(c -> c.kind() == Call.Kind.AWAIT).map(Call::timeout).toList();
        assertEquals(List.of(Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(2)), waits);
        assertEquals(Duration.ofSeconds(10), time.elapsed());
        assertEquals(1, fake.requestsHex().size());
    }

    @Test
    @DisplayName("78s every 5 s with a 10 s total cap: the 78 arriving at the deadline ends it without another wait")
    void pendingAtTotalDeadline() throws InterruptedException {
        DiagTimings timings = new DiagTimings(Duration.ofSeconds(1), Duration.ofSeconds(5), 100, Duration.ofSeconds(10));
        String[] answers = new String[20];
        java.util.Arrays.fill(answers, "7F 19 78");
        FakeDiagnosticChannel fake = FakeDiagnosticChannel.uds().on("19 02 FF", answers);
        DiagResult<DtcList> r = new UdsDiagnosticService(new ClockAdvancingChannel(fake, time, Duration.ofSeconds(5)), time, timings)
                .readDtcs();
        assertInstanceOf(DiagResult.PendingLimitExceeded.class, r);
        assertEquals(2, fake.calls().stream().filter(c -> c.kind() == Call.Kind.AWAIT).count());
    }

    @Test
    @DisplayName("7F 19 78 then silence gives TimedOut after the pending timeout, counting the 78")
    void pendingThenSilence() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.uds().on("19 02 FF", "7F 19 78");
        DiagResult<DtcList> r = new UdsDiagnosticService(ch, time).readDtcs();
        DiagResult.TimedOut<?> t = assertInstanceOf(DiagResult.TimedOut.class, r);
        assertEquals(DiagTimings.RESPONSE_PENDING_TIMEOUT, t.waited());
        assertEquals(1, t.pendingResponses());
        assertEquals(List.of(Call.Kind.REQUEST, Call.Kind.AWAIT), kinds(ch));
    }

    @Test
    @DisplayName("a 78 for another service (7F 22 78 to 19 02 FF) is not waited on: malformed")
    void pendingForOtherService() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.uds().on("19 02 FF", "7F 22 78", "59 02 FF");
        DiagResult<DtcList> r = new UdsDiagnosticService(ch, time).readDtcs();
        assertInstanceOf(DiagResult.Malformed.class, r);
        assertEquals(List.of(Call.Kind.REQUEST), kinds(ch));
    }

    // ---- other outcomes ----

    @Test
    @DisplayName("no answer to 19 02 FF gives TimedOut with the request timeout")
    void timeout() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.uds().onTimeout("19 02 FF");
        DiagResult.TimedOut<?> t = assertInstanceOf(DiagResult.TimedOut.class, new UdsDiagnosticService(ch, time).readDtcs());
        assertEquals(DiagTimings.DEFAULT_REQUEST_TIMEOUT, t.waited());
        assertEquals(0, t.pendingResponses());
    }

    @Test
    @DisplayName("a transport failure (module sent A8) gives TransportFailure with the kind and detail")
    void transportFailure() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.kwp()
                .on("18 02", new Response.Failed(Response.Failed.Kind.DISCONNECTED_BY_MODULE, "module sent A8"));
        DiagResult.TransportFailure<?> f = assertInstanceOf(DiagResult.TransportFailure.class,
                new KwpDiagnosticService(ch, time).readDtcs());
        assertEquals(Response.Failed.Kind.DISCONNECTED_BY_MODULE, f.kind());
        assertEquals("module sent A8", f.detail());
    }

    @Test
    @DisplayName("a transport failure while waiting after 7F 19 78 is reported as TransportFailure")
    void failureDuringPending() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.uds().on("19 02 FF",
                new Response.Received(Hex.parse("7F 19 78")),
                new Response.Failed(Response.Failed.Kind.ADAPTER_ERROR, "unplugged"));
        assertInstanceOf(DiagResult.TransportFailure.class, new UdsDiagnosticService(ch, time).readDtcs());
    }

    @Test
    @DisplayName("empty, 7F, 7F 19 (too short) and 7F 22 31 (other service) answers are malformed with raw kept")
    void malformedNegatives() throws InterruptedException {
        for (String bad : new String[] {"", "7F", "7F 19", "7F 22 31"}) {
            FakeDiagnosticChannel ch = FakeDiagnosticChannel.uds()
                    .on("19 02 FF", new Response.Received(Hex.parse(bad)));
            DiagResult.Malformed<?> m = assertInstanceOf(DiagResult.Malformed.class,
                    new UdsDiagnosticService(ch, time).readDtcs(), "for '" + bad + "'");
            assertEquals(RawBytes.ofHex(bad), m.response());
        }
    }

    @Test
    @DisplayName("closed channel gives TransportFailure CHANNEL_CLOSED")
    void closedChannel() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.uds();
        ch.close();
        DiagResult.TransportFailure<?> f = assertInstanceOf(DiagResult.TransportFailure.class,
                new UdsDiagnosticService(ch, time).readDtcs());
        assertEquals(Response.Failed.Kind.CHANNEL_CLOSED, f.kind());
    }

    // ---- ForbiddenRequestException ----

    /** A channel whose guard refuses everything (simulating the impossible), remembering what it threw. */
    private static final class RefusingChannel implements DiagnosticChannel {
        private final ModuleRoute route;
        final List<ForbiddenRequestException> thrown = new ArrayList<>();

        RefusingChannel(ModuleRoute route) {
            this.route = route;
        }

        @Override
        public ModuleRoute route() {
            return route;
        }

        @Override
        public ApplicationProtocol protocol() {
            return route.protocol();
        }

        @Override
        public Response request(byte[] payload, Duration timeout) {
            try {
                RequestGuard.check(protocol(), Hex.parse("27 01")); // always refused: security access
            } catch (ForbiddenRequestException e) {
                thrown.add(e);
                throw e;
            }
            throw new AssertionError("guard let 27 01 through");
        }

        @Override
        public Response awaitResponse(Duration timeout) {
            throw new AssertionError("must not wait after a refusal");
        }

        @Override
        public boolean keepAlive() {
            return true;
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void close() {
        }
    }

    @Test
    @DisplayName("ForbiddenRequestException from the channel propagates unchanged from every operation (never swallowed)")
    void forbiddenPropagates() {
        RefusingChannel uds = new RefusingChannel(new ModuleRoute.IsoTp(0x7E0, 0x7E8, ApplicationProtocol.UDS));
        RefusingChannel kwp = new RefusingChannel(new ModuleRoute.Tp20(0x01));
        List<DiagnosticService> services = List.of(new UdsDiagnosticService(uds, new FakeTimeSource()),
                new KwpDiagnosticService(kwp, new FakeTimeSource()));
        for (DiagnosticService s : services) {
            RefusingChannel ch = s.protocol() == ApplicationProtocol.UDS ? uds : kwp;
            List<org.junit.jupiter.api.function.Executable> ops = new ArrayList<>(List.of(
                    s::readDtcs, s::readIdentification, s::testerPresent,
                    () -> s.startSession(s.protocol() == ApplicationProtocol.UDS
                            ? DiagnosticSession.UDS_EXTENDED : DiagnosticSession.KWP_VW_DIAGNOSTIC)));
            if (s.protocol() == ApplicationProtocol.UDS) {
                ops.add(() -> s.readFreezeFrame(new UdsDtc(0x040100, 0x2F)));
            }
            for (org.junit.jupiter.api.function.Executable op : ops) {
                ForbiddenRequestException e = assertThrows(ForbiddenRequestException.class, op);
                assertSame(ch.thrown.getLast(), e, "the very exception the channel threw");
            }
        }
    }

    // ---- result type rules ----

    @Test
    @DisplayName("Negative refuses 21 and 78; Busy needs 21")
    void resultInvariants() {
        assertThrows(IllegalArgumentException.class, () -> new DiagResult.Negative<RawBytes>(NegativeResponse.of(0x19, 0x21)));
        assertThrows(IllegalArgumentException.class, () -> new DiagResult.Negative<RawBytes>(NegativeResponse.of(0x19, 0x78)));
        assertThrows(IllegalArgumentException.class, () -> new DiagResult.Busy<RawBytes>(NegativeResponse.of(0x19, 0x31)));
        assertThrows(IllegalArgumentException.class, () -> NegativeResponse.of(0x100, 0x31));
        assertEquals(RawBytes.ofHex("7F 19 31"), NegativeResponse.of(0x19, 0x31).raw());
    }

    @Test
    @DisplayName("map converts successes and passes every failure kind on unchanged")
    void mapAndFailure() {
        DiagResult<Integer> ok = new DiagResult.Success<>(2);
        assertEquals(new DiagResult.Success<>("2"), ok.map(String::valueOf));
        assertThrows(IllegalStateException.class, ok::failure);
        List<DiagResult<Integer>> failures = List.of(
                new DiagResult.Negative<>(NegativeResponse.of(0x22, 0x31)),
                new DiagResult.Busy<>(NegativeResponse.of(0x22, 0x21)),
                new DiagResult.TimedOut<>(Duration.ofSeconds(1), 2),
                new DiagResult.PendingLimitExceeded<>(21, "more than 20 allowed"),
                new DiagResult.TransportFailure<>(Response.Failed.Kind.PROTOCOL_ERROR, "seq"),
                new DiagResult.Malformed<>("short", RawBytes.ofHex("62")),
                new DiagResult.NotAvailable<>("why"));
        for (DiagResult<Integer> f : failures) {
            DiagResult<String> mapped = f.map(String::valueOf);
            assertEquals(f.getClass(), mapped.getClass());
            assertEquals(f.message(), mapped.message());
            assertEquals(f, mapped, "records with the same components are equal regardless of T");
            assertFalse(f.isSuccess());
            assertTrue(f.valueIfSuccess().isEmpty());
            assertEquals(f instanceof DiagResult.Busy<?>, f.retryable());
        }
    }
}
