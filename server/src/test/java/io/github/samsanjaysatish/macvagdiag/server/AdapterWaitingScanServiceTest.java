package io.github.samsanjaysatish.macvagdiag.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.can.FakeTimeSource;
import io.github.samsanjaysatish.macvagdiag.can.SlcanPortDiscovery.Candidate;
import io.github.samsanjaysatish.macvagdiag.scan.Caller;
import io.github.samsanjaysatish.macvagdiag.scan.FakeScanService;
import io.github.samsanjaysatish.macvagdiag.scan.ModuleAddress;
import io.github.samsanjaysatish.macvagdiag.scan.ScanRefusedException;
import io.github.samsanjaysatish.macvagdiag.scan.SessionStatus;
import io.github.samsanjaysatish.macvagdiag.scan.ToolCallRecord;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Auto-detect without a CANable at startup: refuse until one appears, then bind once and pass through. */
class AdapterWaitingScanServiceTest {

    static final Candidate CANABLE = new Candidate("/dev/cu.usbmodem14101", "CANable2 b158aa7 github.com/normaldotcom/canable2-fw.git", 0x16D0, 0x117E);
    static final Candidate ARDUINO = new Candidate("/dev/cu.usbmodem2101", "Arduino Uno", 0x2341, 0x0043);

    final FakeTimeSource time = new FakeTimeSource();
    final AtomicReference<List<Candidate>> ports = new AtomicReference<>(List.of());
    final List<String> connected = new ArrayList<>();
    final List<FakeScanService> services = new ArrayList<>();
    int discoveries;

    AdapterWaitingScanService service() {
        return new AdapterWaitingScanService(() -> {
            discoveries++;
            return ports.get();
        }, path -> {
            connected.add(path);
            FakeScanService s = new FakeScanService();
            services.add(s);
            return s;
        }, time);
    }

    static ToolCallRecord toolCall() {
        return new ToolCallRecord("list_modules", Map.of(), "ok", "summary");
    }

    @Test
    @DisplayName("no CANable: status DISCONNECTED with why, every car call refused, nothing connected")
    void nothingPluggedIn() throws Exception {
        AdapterWaitingScanService s = service();
        SessionStatus status = s.sessionStatus(Caller.web());
        assertEquals(SessionStatus.AdapterState.DISCONNECTED, status.adapter());
        assertTrue(status.adapterDetail().contains("no CANable found"), status.adapterDetail());
        assertFalse(status.demo());
        assertEquals(ScanRefusedException.Reason.ADAPTER_NOT_CONNECTED,
                assertThrows(ScanRefusedException.class, () -> s.listModules(Caller.web())).reason());
        assertThrows(ScanRefusedException.class, () -> s.readFaultCodes(Caller.web(), ModuleAddress.ENGINE));
        assertThrows(ScanRefusedException.class, () -> s.vehicleInfo(Caller.web()));
        assertThrows(ScanRefusedException.class, () -> s.startAutoScan(Caller.web(), null));
        assertTrue(s.findAutoScan("scan-1").isEmpty());
        assertTrue(connected.isEmpty());
        assertFalse(s.isBound());
    }

    @Test
    @DisplayName("a serial device that isn't a CANable is never picked; the status names it and how to use it")
    void otherDeviceNotPicked() throws Exception {
        ports.set(List.of(ARDUINO));
        AdapterWaitingScanService s = service();
        String detail = s.sessionStatus(Caller.web()).adapterDetail();
        assertTrue(detail.contains("/dev/cu.usbmodem2101") && detail.contains("macvagdiag.port"), detail);
        assertTrue(connected.isEmpty());
    }

    @Test
    @DisplayName("a CANable at startup is bound at once; calls pass straight through")
    void boundAtStartup() throws Exception {
        ports.set(List.of(ARDUINO, CANABLE));
        AdapterWaitingScanService s = service();
        assertEquals(List.of(CANABLE.path()), connected);
        assertTrue(s.isBound());
        assertEquals(SessionStatus.AdapterState.CONNECTED, s.sessionStatus(Caller.web()).adapter());
        s.readFaultCodes(Caller.web(), ModuleAddress.INSTRUMENTS);
        assertEquals("readFaultCodes", services.getFirst().calls().get(1).method());
    }

    @Test
    @DisplayName("plugged in later: found on a call after the re-discovery interval, bound once")
    void pluggedInLater() throws Exception {
        AdapterWaitingScanService s = service();
        assertEquals(1, discoveries);
        ports.set(List.of(CANABLE));
        s.sessionStatus(Caller.web());
        assertEquals(1, discoveries, "not looked again before the interval");
        time.advance(AdapterWaitingScanService.REDISCOVERY_INTERVAL);
        assertEquals(SessionStatus.AdapterState.CONNECTED, s.sessionStatus(Caller.web()).adapter());
        assertEquals(List.of(CANABLE.path()), connected);
        time.advance(Duration.ofMinutes(1));
        s.listModules(Caller.web());
        assertEquals(2, discoveries, "never looked again once bound");
        assertEquals(1, connected.size());
    }

    @Test
    @DisplayName("MCP tool calls are audit-logged before an adapter exists (audit-only service), and after")
    void toolCallsAlwaysAudited() throws Exception {
        AdapterWaitingScanService s = service();
        s.recordToolCall(toolCall());
        assertEquals(List.of(AdapterWaitingScanService.AUDIT_ONLY_PORT), connected);
        FakeScanService auditOnly = services.getFirst();
        assertEquals(1, auditOnly.toolCalls().size());
        assertEquals(1, auditOnly.calls().size(), "nothing but the tool call reaches the audit-only service");

        ports.set(List.of(CANABLE));
        time.advance(AdapterWaitingScanService.REDISCOVERY_INTERVAL);
        s.sessionStatus(Caller.web());
        assertTrue(auditOnly.isClosed(), "closed before the real service is built");
        s.recordToolCall(toolCall());
        assertEquals(1, services.get(1).toolCalls().size());
        assertSame(services.get(1), services.getLast());
    }

    @Test
    @DisplayName("close closes the bound service; later calls are refused as closed")
    void close() throws Exception {
        ports.set(List.of(CANABLE));
        AdapterWaitingScanService s = service();
        s.close();
        assertTrue(services.getFirst().isClosed());

        ports.set(List.of());
        AdapterWaitingScanService unbound = service();
        unbound.close();
        assertEquals(ScanRefusedException.Reason.SERVICE_CLOSED,
                assertThrows(ScanRefusedException.class, () -> unbound.listModules(Caller.web())).reason());
    }

    @Test
    @DisplayName("a failing port listing is reported, not thrown")
    void discoveryFails() throws Exception {
        AdapterWaitingScanService s = new AdapterWaitingScanService(() -> {
            throw new IllegalStateException("native library missing");
        }, path -> new FakeScanService(), time);
        assertTrue(s.sessionStatus(Caller.web()).adapterDetail().contains("native library missing"));
    }
}
