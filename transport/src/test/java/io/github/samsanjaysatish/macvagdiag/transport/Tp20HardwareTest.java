package io.github.samsanjaysatish.macvagdiag.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.can.AdapterMode;
import io.github.samsanjaysatish.macvagdiag.can.Bitrate;
import io.github.samsanjaysatish.macvagdiag.can.SlcanAdapter;
import io.github.samsanjaysatish.macvagdiag.can.SlcanPortDiscovery;
import io.github.samsanjaysatish.macvagdiag.can.TimeSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * <b>Manual only</b> ({@code ./gradlew :transport:hardwareTest}, CANable on the car's diagnostic CAN, ignition on).
 * Never run by Claude or CI.
 *
 * <p>Opens one TP 2.0 channel to the gateway, keeps it alive for 10 s with {@code A3}/{@code A1} channel tests,
 * then disconnects with {@code A8}. <b>It sends no diagnostic request</b>: the audit listener fails the test if
 * anything reaches {@link RequestAuditListener#sending}. What does go on the bus is the channel setup on
 * {@code 200}, the timing exchange, the channel tests and the disconnect, all through the normal guarded path.
 *
 * <p>Run it only after the sniffing capture has confirmed (or corrected) the TP 2.0 constants in
 * {@link Tp20Channel} and the gateway's logical address below; until then every byte it sends is UNCONFIRMED.
 * The adapter is opened in {@code NORMAL} mode, so it ACKs frames on the bus like any tester: don't run it while
 * VCDS is connected.
 *
 * <p>Environment (both optional): {@code MACVAGDIAG_PORT} picks the serial port (default: the only CANable
 * found); {@code MACVAGDIAG_TP20_ADDRESS} overrides the logical address, as two hex digits.
 */
@Tag("hardware")
class Tp20HardwareTest {

    /**
     * UNCONFIRMED: the gateway's TP 2.0 logical address. {@code 1F} is the value commonly given for the gateway
     * (from memory). Note that {@code scan}'s {@code ModuleAddress} currently assumes the logical address equals
     * the VCDS address ({@code 19}); the capture decides which is right.
     */
    static final int GATEWAY_TP20_ADDRESS = 0x1F;

    static final Duration KEEP_ALIVE_FOR = Duration.ofSeconds(10);

    /** UNCONFIRMED: how often VCDS sends {@code A3}; take it from the capture. */
    static final Duration KEEP_ALIVE_INTERVAL = Duration.ofSeconds(1);

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void opensAChannelToTheGatewayKeepsItAliveAndDisconnects() throws InterruptedException {
        TimeSource time = TimeSource.system();
        List<String> sent = new ArrayList<>();
        RequestAuditListener noRequests = new RequestAuditListener() {
            @Override
            public void sending(ModuleRoute route, ApplicationProtocol protocol, byte[] payload) {
                sent.add(HexFormat.ofDelimiter(" ").withUpperCase().formatHex(payload));
                throw new IllegalStateException("this test must not send diagnostic requests");
            }
        };

        try (SlcanAdapter adapter = SlcanAdapter.forPort(port(), time)) {
            adapter.open(Bitrate.KBIT_500, AdapterMode.NORMAL);
            ChannelFactory factory = ChannelFactory.create(adapter, time, noRequests);
            DiagnosticChannel channel = factory.open(new ModuleRoute.Tp20(address()));
            try {
                long end = time.deadlineAfter(KEEP_ALIVE_FOR);
                int tests = 0;
                while (time.nanoTime() < end) {
                    assertTrue(channel.keepAlive(), "the gateway stopped answering A3 after " + tests + " channel tests");
                    tests++;
                    time.sleepUntil(time.deadlineAfter(KEEP_ALIVE_INTERVAL));
                }
                assertTrue(channel.isOpen());
            } finally {
                channel.close();
            }
            assertFalse(channel.isOpen());
        }
        assertEquals(List.of(), sent, "no diagnostic request may be sent");
    }

    private static String port() {
        String configured = System.getenv("MACVAGDIAG_PORT");
        if (configured != null && !configured.isBlank()) {
            return configured.strip();
        }
        List<SlcanPortDiscovery.Candidate> canables = SlcanPortDiscovery.candidates().stream()
                .filter(SlcanPortDiscovery.Candidate::isCanable)
                .toList();
        assertEquals(1, canables.size(), "expected exactly one CANable, found " + canables
                + "; set MACVAGDIAG_PORT to choose one");
        return canables.getFirst().path();
    }

    private static int address() {
        String configured = System.getenv("MACVAGDIAG_TP20_ADDRESS");
        return configured == null || configured.isBlank()
                ? GATEWAY_TP20_ADDRESS
                : HexFormat.fromHexDigits(configured.strip());
    }
}
