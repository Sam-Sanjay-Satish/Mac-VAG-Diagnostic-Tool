package io.github.samsanjaysatish.macvagdiag.transport;

import static io.github.samsanjaysatish.macvagdiag.transport.ApplicationProtocol.KWP2000;
import static io.github.samsanjaysatish.macvagdiag.transport.ApplicationProtocol.UDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.can.AdapterMode;
import io.github.samsanjaysatish.macvagdiag.can.Bitrate;
import io.github.samsanjaysatish.macvagdiag.can.FakeTimeSource;
import io.github.samsanjaysatish.macvagdiag.can.Hex;
import io.github.samsanjaysatish.macvagdiag.can.SimulatedAdapter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Root CLAUDE.md safety rules 1–3, end to end: through {@link ChannelFactory} on a {@link SimulatedAdapter},
 * every forbidden request is rejected and <b>zero frames reach the simulated wire</b>; allowed requests pass.
 */
class GuardRejectionTest {

    private static final Duration TIMEOUT = Duration.ofMillis(500);

    /** Root CLAUDE.md "never allowed", UDS, with several sub-functions each, plus programming sessions. */
    static final List<String> FORBIDDEN_UDS = List.of(
            "11 01", "11 02", "11 03", "11 81",                         // ECU reset
            "14 FF FF FF", "14 00 00 00",                               // clear DTCs
            "23 44 00 00 10 00 00 10", "23 12 80 00 10",                // read memory by address
            "27 01", "27 02 12 34 56 78", "27 03", "27 11",             // security access
            "28 00 01", "28 01 01", "28 03 01", "28 80 03",             // communication control
            "2C 01 F3 00 F1 90 01 01", "2C 03 F3 00",                   // dynamically define DID
            "2E F1 90 57 56 57", "2E 06 00 01",                         // write DID (coding)
            "2F 12 34 03 00", "2F F0 00 00",                            // IO control
            "31 01 FF 00", "31 01 02 03", "31 02 FF 00", "31 03 FF 00", // routine control
            "34 00 44 00 00 00 00 00 00 10 00",                         // request download
            "35 00 44 00 00 00 00 00 00 10 00",                         // request upload
            "36 01 00 11 22",                                           // transfer data
            "37",                                                       // request transfer exit
            "3D 14 00 00 10 00 01 AA",                                  // write memory by address
            "85 01", "85 02", "85 81",                                  // control DTC setting
            "10 02", "10 82");                                          // programming session (+ suppress bit)

    /** Root CLAUDE.md "never allowed", KWP2000, plus the programming session. */
    static final List<String> FORBIDDEN_KWP = List.of(
            "11 01", "11 81",                       // ECU reset
            "14 FF 00", "14 00 00",                 // clear diagnostic information
            "27 01", "27 02 12 34",                 // security access
            "2E F1 90 00", "2E 00 01 02",           // write data by common identifier
            "30 01 07 00", "30 0A 03 01",           // IO control by local identifier
            "31 B8 00", "31 01",                    // start routine by local identifier
            "34 00 00 00 01 00 10 00",              // request download
            "35 00 00 00 01 00 10 00",              // request upload
            "36 01 02 03",                          // transfer data
            "37",                                   // request transfer exit
            "3B 9A 00 00 00", "3B 07 01",           // write data by local identifier (coding)
            "3D 00 10 00 01 AA",                    // write memory by address
            "10 85");                               // programming session

    /** Not on the allowlist and not on the forbidden list: must still be refused (default reject). */
    static final List<Arguments> UNKNOWN = List.of(
            Arguments.of(KWP2000, "21 01"), Arguments.of(KWP2000, "21 00"),   // measuring blocks: excluded from v1
            Arguments.of(KWP2000, "10 81"), Arguments.of(KWP2000, "10 86"), Arguments.of(KWP2000, "10 03"),
            Arguments.of(KWP2000, "10 89 01"), Arguments.of(KWP2000, "10"),
            Arguments.of(KWP2000, "3E 01"), Arguments.of(KWP2000, "3E 02"),
            Arguments.of(KWP2000, "1A"), Arguments.of(KWP2000, "1A 9B 00"),
            Arguments.of(KWP2000, "18 02 FF 00"), Arguments.of(KWP2000, "12 00 01"),
            Arguments.of(KWP2000, "22 F1 90"), Arguments.of(KWP2000, "19 02 FF"),
            Arguments.of(KWP2000, "32 01"), Arguments.of(KWP2000, "33 01"), Arguments.of(KWP2000, "3C 01"),
            Arguments.of(KWP2000, "01 00"), Arguments.of(KWP2000, "00"), Arguments.of(KWP2000, "FF"),
            Arguments.of(UDS, "10 89"), Arguments.of(UDS, "10 04"), Arguments.of(UDS, "10 40"),
            Arguments.of(UDS, "10 60"), Arguments.of(UDS, "10 81"), Arguments.of(UDS, "10 83"),
            Arguments.of(UDS, "10 03 00"), Arguments.of(UDS, "10"),
            Arguments.of(UDS, "3E"), Arguments.of(UDS, "3E 01"), Arguments.of(UDS, "3E 81"),
            Arguments.of(UDS, "19 03"), Arguments.of(UDS, "19 0A"), Arguments.of(UDS, "19 02"),
            Arguments.of(UDS, "19 02 FF 00"), Arguments.of(UDS, "19 04 00 04 01"), Arguments.of(UDS, "19 06 00 04 01 FF 00"),
            Arguments.of(UDS, "19 82 FF"),
            Arguments.of(UDS, "22 F1"), Arguments.of(UDS, "22 F1 90 F1 87"),
            Arguments.of(UDS, "1A 9B"), Arguments.of(UDS, "18 02"), Arguments.of(UDS, "12 00"), Arguments.of(UDS, "21 01"),
            Arguments.of(UDS, "24 F1 90"), Arguments.of(UDS, "2A 01 F2 00"), Arguments.of(UDS, "3D"),
            Arguments.of(UDS, "83 01"), Arguments.of(UDS, "86 05 01"), Arguments.of(UDS, "87 01 13"),
            Arguments.of(UDS, "01 0C"), Arguments.of(UDS, "09 02"), Arguments.of(UDS, "00"), Arguments.of(UDS, "FF"));

    static final List<Arguments> ALLOWED = List.of(
            Arguments.of(KWP2000, "10 89"), Arguments.of(KWP2000, "1A 9B"), Arguments.of(KWP2000, "18 02"),
            Arguments.of(KWP2000, "12 00"), Arguments.of(KWP2000, "3E"),
            Arguments.of(UDS, "10 01"), Arguments.of(UDS, "10 03"), Arguments.of(UDS, "3E 00"),
            Arguments.of(UDS, "19 01 FF"), Arguments.of(UDS, "19 02 FF"), Arguments.of(UDS, "19 04 00 04 01 FF"),
            Arguments.of(UDS, "19 06 00 04 01 FF"), Arguments.of(UDS, "22 F1 90"));

    /** Every forbidden request under its own protocol, and under the other one too. */
    static Stream<Arguments> forbidden() {
        return Stream.of(
                FORBIDDEN_UDS.stream().map(hex -> Arguments.of(UDS, hex)),
                FORBIDDEN_KWP.stream().map(hex -> Arguments.of(KWP2000, hex)),
                FORBIDDEN_UDS.stream().map(hex -> Arguments.of(KWP2000, hex)),
                FORBIDDEN_KWP.stream().map(hex -> Arguments.of(UDS, hex)))
                .flatMap(s -> s);
    }

    static Stream<Arguments> unknown() {
        return UNKNOWN.stream();
    }

    static Stream<Arguments> allowed() {
        return ALLOWED.stream();
    }

    private final FakeTimeSource time = FakeTimeSource.autoAdvancing();
    private final List<ForbiddenRequestException> rejections = new ArrayList<>();
    private final List<String> sentAudit = new ArrayList<>();
    private SimulatedAdapter adapter;
    private DiagnosticChannel channel;

    private DiagnosticChannel openChannel(ApplicationProtocol protocol) throws InterruptedException {
        adapter = new SimulatedAdapter(time, protocol == KWP2000 ? FakeEcus.tp20() : FakeEcus.isoTp());
        adapter.open(Bitrate.KBIT_500, AdapterMode.NORMAL);
        ChannelFactory factory = ChannelFactory.create(adapter, time, new RequestAuditListener() {
            @Override
            public void sending(ModuleRoute route, ApplicationProtocol p, byte[] payload) {
                sentAudit.add(p + " " + Hex.format(payload));
            }

            @Override
            public void rejected(ModuleRoute route, ForbiddenRequestException rejection) {
                rejections.add(rejection);
            }
        });
        ModuleRoute route = protocol == KWP2000
                ? new ModuleRoute.Tp20(FakeEcus.TP20_ADDRESS)
                : new ModuleRoute.IsoTp(0x7E0, 0x7E8, UDS);
        channel = factory.open(route);
        adapter.clearSentFrames(); // TP 2.0 setup frames; from here on, nothing may reach the wire
        return channel;
    }

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.close();
        }
        if (adapter != null) {
            adapter.close();
        }
    }

    private void assertRejectedWithNothingSent(ApplicationProtocol protocol, String hex) throws Exception {
        DiagnosticChannel ch = openChannel(protocol);
        byte[] payload = Hex.parse(hex);

        ForbiddenRequestException e = assertThrows(ForbiddenRequestException.class, () -> ch.request(payload, TIMEOUT));

        assertEquals(List.of(), adapter.sentFrames(), "zero frames may reach the bus");
        assertEquals(List.of(e), rejections, "the rejection was passed to the audit listener");
        assertEquals(List.of(), sentAudit, "nothing was audited as sent");
        assertTrue(ch.isOpen(), "a rejected request does not disturb the channel");
    }

    @ParameterizedTest(name = "rejects {0} {1}")
    @MethodSource("forbidden")
    void rejectsForbidden(ApplicationProtocol protocol, String hex) throws Exception {
        assertRejectedWithNothingSent(protocol, hex);
    }

    @ParameterizedTest(name = "rejects unknown {0} {1}")
    @MethodSource("unknown")
    void rejectsUnknown(ApplicationProtocol protocol, String hex) throws Exception {
        assertRejectedWithNothingSent(protocol, hex);
    }

    @ParameterizedTest(name = "rejects empty {0} payload")
    @MethodSource("protocols")
    void rejectsEmpty(ApplicationProtocol protocol) throws Exception {
        DiagnosticChannel ch = openChannel(protocol);
        assertThrows(ForbiddenRequestException.class, () -> ch.request(new byte[0], TIMEOUT));
        assertThrows(ForbiddenRequestException.class, () -> ch.request(null, TIMEOUT));
        assertEquals(List.of(), adapter.sentFrames());
        assertEquals(2, rejections.size());
    }

    static Stream<ApplicationProtocol> protocols() {
        return Stream.of(ApplicationProtocol.values());
    }

    @ParameterizedTest(name = "allows {0} {1}")
    @MethodSource("allowed")
    void allowsAndSends(ApplicationProtocol protocol, String hex) throws Exception {
        DiagnosticChannel ch = openChannel(protocol);

        Response response = ch.request(Hex.parse(hex), TIMEOUT);

        Response.Received received = assertInstanceOf(Response.Received.class, response);
        assertEquals(Hex.format(FakeEcus.positiveResponse(Hex.parse(hex))), received.hex());
        assertTrue(!adapter.sentFrames().isEmpty(), "the request reached the bus");
        assertEquals(List.of(protocol + " " + hex), sentAudit);
        assertEquals(List.of(), rejections);
    }

    @Test
    void allowsUds3E80WhichHasNoResponse() throws Exception {
        DiagnosticChannel ch = openChannel(UDS);
        Response response = ch.request(Hex.parse("3E 80"), Duration.ofMillis(50));
        assertInstanceOf(Response.TimedOut.class, response);
        assertEquals(List.of("7E0 [8] 02 3E 80 AA AA AA AA AA"), adapter.sentFrames().stream().map(Object::toString).toList());
    }

    @Test
    void rejectionAfterAllowedRequestLeavesChannelUsable() throws Exception {
        DiagnosticChannel ch = openChannel(KWP2000);
        assertThrows(ForbiddenRequestException.class, () -> ch.request(Hex.parse("27 01"), TIMEOUT));
        assertThrows(ForbiddenRequestException.class, () -> ch.request(Hex.parse("21 01"), TIMEOUT));
        assertEquals(List.of(), adapter.sentFrames());
        assertInstanceOf(Response.Received.class, ch.request(Hex.parse("1A 9B"), TIMEOUT));
    }
}
