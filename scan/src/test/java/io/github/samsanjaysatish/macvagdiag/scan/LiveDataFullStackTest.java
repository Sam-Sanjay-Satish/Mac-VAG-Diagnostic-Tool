package io.github.samsanjaysatish.macvagdiag.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.can.FakeTimeSource;
import io.github.samsanjaysatish.macvagdiag.can.Hex;
import io.github.samsanjaysatish.macvagdiag.transport.ApplicationProtocol;
import io.github.samsanjaysatish.macvagdiag.transport.RequestGuard;
import java.io.StringWriter;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Live data through the whole real stack: scan → diag → transport → {@code SimulatedAdapter} answering as
 * {@link DemoCar}, with the bundled made-up demo labels. What reaches the wire equals the audit log and is all
 * allowlisted; withheld and basic-settings groups never reach the wire.
 */
class LiveDataFullStackTest {

    private static LiveValueId id(String s) {
        return LiveValueId.tryParse(s).orElseThrow();
    }

    @Test
    @DisplayName("demo engine: catalog from the demo labels, read, record; wire == audit, all allowlisted, no 21 00 / 21 51")
    void engine() throws Exception {
        StringWriter log = new StringWriter();
        FakeTimeSource time = FakeTimeSource.autoAdvancing();
        ScanServiceFactory.DemoStack stack = ScanServiceFactory.demoStack(time, ScanLimits.DEFAULTS,
                AuditLog.to(log, time, true));
        try (ScanService service = stack.service()) {
            LiveDataCatalog c = service.liveDataCatalog(Caller.mcp("list_available_data"), ModuleAddress.ENGINE);
            assertEquals(LiveDataCatalog.Source.LABELS, c.source());
            assertEquals(java.util.Optional.of("03L-906-018.lbl"), c.labelSource());
            assertEquals(List.of(1, 7, 11), c.groups().stream().map(LiveDataCatalog.Group::group).toList());
            assertEquals(List.of(81), c.withheldGroups());
            assertEquals("Engine Speed (demo)", c.field(id("01:001.1")).orElseThrow().name());

            LiveDataSnapshot s = service.readLiveData(Caller.mcp("read_live_data"),
                    List.of(id("01:001.1"), id("01:001.4"), id("01:007.2"), id("01:011.3")));
            assertEquals(LiveDataSnapshot.Status.OK, s.status(), s.notes().toString());
            assertEquals("/min", s.values().get(0).unit());
            assertEquals("°C", s.values().get(1).unit());
            assertEquals("68.4 °C", s.values().get(1).text()); // 05 09 B0
            assertEquals(LiveValue.State.EMPTY, s.values().get(2).state());
            assertEquals("mbar", s.values().get(3).unit());

            assertThrows(InvalidLiveValueException.class,
                    () -> service.readLiveData(Caller.web(), List.of(id("01:081.1"))));

            LiveDataRecording r = service.recordLiveData(Caller.mcp("record_live_data"),
                    List.of(id("01:001.1"), id("01:011.3")), Duration.ofSeconds(3));
            assertTrue(r.rounds() >= 4, "rounds " + r.rounds());
            LiveSeries rpm = r.series().getFirst();
            assertTrue(rpm.min().orElseThrow().compareTo(rpm.max().orElseThrow()) < 0, "the demo RPM moves: " + rpm);
            assertEquals(r.rounds(), rpm.decoded());

            List<FullStackTest.WireRequest> wire = FullStackTest.requestsOnTheWire(stack.adapter().sentFrames());
            List<String> audited = log.toString().lines().filter(l -> l.contains("\"event\":\"request\""))
                    .map(l -> l.replaceAll(".*\"request\":\"([0-9A-F ]+)\".*", "$1")).toList();
            assertEquals(audited, wire.stream().map(FullStackTest.WireRequest::hex).toList());
            for (FullStackTest.WireRequest w : wire) {
                assertTrue(RequestGuard.isAllowed(w.protocol(), Hex.parse(w.hex())), w.toString());
                assertFalse(w.hex().equals("21 00") || w.hex().equals("21 51") || w.hex().equals("21 03")
                        || w.hex().equals("21 32"), "never group 000, the withheld 081 or a B-line: " + w);
                assertFalse(w.hex().equals("1A 90") || w.hex().equals("22 F1 90"), "the VIN is never read");
            }
            assertTrue(wire.stream().anyMatch(w -> w.protocol() == ApplicationProtocol.KWP2000 && w.hex().equals("21 0B")));
        }
    }

    @Test
    @DisplayName("demo ABS (no demo labels): probed over the real stack until complete; 001 and 005 answer")
    void probedModule() throws Exception {
        StringWriter log = new StringWriter();
        FakeTimeSource time = FakeTimeSource.autoAdvancing();
        ScanServiceFactory.DemoStack stack = ScanServiceFactory.demoStack(time, ScanLimits.DEFAULTS,
                AuditLog.to(log, time, true));
        try (ScanService service = stack.service()) {
            ModuleAddress abs = ModuleAddress.parse("03");
            LiveDataCatalog c = service.liveDataCatalog(Caller.web(), abs);
            int calls = 1;
            while (c.source() == LiveDataCatalog.Source.PROBE_INCOMPLETE && calls < 10) {
                time.advance(Duration.ofSeconds(61));
                c = service.liveDataCatalog(Caller.web(), abs);
                calls++;
            }
            assertEquals(LiveDataCatalog.Source.PROBED, c.source(), c.notes().toString());
            assertEquals(7, calls, "255 groups, 40 per call");
            assertEquals(List.of(1, 5), c.groups().stream().map(LiveDataCatalog.Group::group).toList());
            assertEquals("V", c.groups().get(1).fields().getFirst().unit());
            LiveDataSnapshot s = service.readLiveData(Caller.web(), List.of(id("03:005.1")));
            assertEquals("13.83 V", s.values().getFirst().text());
            long probes = FullStackTest.requestsOnTheWire(stack.adapter().sentFrames()).stream()
                    .filter(w -> w.hex().startsWith("21 ")).count();
            assertEquals(255 + 1, probes, "each group asked once, plus the read");
        }
    }
}
