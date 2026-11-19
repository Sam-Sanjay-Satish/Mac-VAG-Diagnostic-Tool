package io.github.samsanjaysatish.macvagdiag.scan;

import static io.github.samsanjaysatish.macvagdiag.scan.Scripts.negative;
import static io.github.samsanjaysatish.macvagdiag.scan.Scripts.ok;
import static io.github.samsanjaysatish.macvagdiag.scan.Scripts.okHex;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.can.FakeTimeSource;
import io.github.samsanjaysatish.macvagdiag.diag.EcuIdentification;
import io.github.samsanjaysatish.macvagdiag.diag.FakeDiagnosticService;
import io.github.samsanjaysatish.macvagdiag.transport.ApplicationProtocol;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Live data in scan: catalog from labels or probing, checked reads, recordings, budgets and privacy. On a real
 * {@link ScanService} over {@link FakeModuleConnector}, with made-up label files.
 */
class LiveDataTest {

    /** Made-up labels in the VCDS format for a test engine (not from any real label file). */
    static final String ENGINE_LABELS = String.join("\r\n",
            "001,0,Test Values",
            "001,1,Engine Speed,(test),Range: 0...5000 /min\\nSpecification (Idle): 780...920 /min",
            "001,4,Coolant,Temperature (test),Range: -40.0...+130.0 °C",
            "007,0,Test Temperatures",
            "007,1,Fuel Temperature",
            "007,2,Oil Temperature",
            "007,3,Intake Air",
            "007,4,Coolant",
            "011,0,Test Boost",
            "011,2,Boost Pressure,(specified),Range: 0.0...1000.0 mbar",
            "012,0,Test 12",
            "013,0,Test 13",
            "014,0,Test 14",
            "081,0,Test Identification",
            "081,1,Vehicle Ident.,Number (VIN)",
            "B003,0,Basic Setting Not Offered",
            "B003,1,Not Offered Either");

    static final Map<String, String> LABEL_FILES = Map.of("03L-997-030.lbl", ENGINE_LABELS);
    static final String PART = "03L997030M";
    static final ModuleAddress ENGINE = ModuleAddress.ENGINE;

    // Real engine responses from the 2026-11-18 capture (see diag's MeasuringBlockTest).
    static final String G001 = "61 01 01 73 24 27 46 16 64 50 29 05 09 B3 25 02 7A 25 00 00 25 01 7D 25 00 00";
    static final String G007 = "61 07 05 09 8F 25 00 00 05 09 83 05 09 AF 25 00 00 25 00 00 25 00 00 25 00 00";
    static final String G011 = "61 0B 01 73 24 60 79 53 60 79 52 21 FF 25 08 0A 00 05 09 6C 10 FF 00 15 4B 00";

    private static FakeDiagnosticService engine(String partNumber) {
        return FakeDiagnosticService.kwp().onStartSession(okHex("50 89"))
                .onReadIdentification(ok(FakeDiagnosticService.identification(ApplicationProtocol.KWP2000,
                        Map.of(EcuIdentification.Field.PART_NUMBER, partNumber))));
    }

    private static io.github.samsanjaysatish.macvagdiag.diag.DiagResult<io.github.samsanjaysatish.macvagdiag.diag.MeasuringBlock>
            block(int group, String hex) {
        return ok(FakeDiagnosticService.measuringBlock(group, hex));
    }

    /** One scripted service per connection, in order. */
    private static Supplier<FakeDiagnosticService> connections(FakeDiagnosticService... perConnection) {
        Deque<FakeDiagnosticService> q = new ArrayDeque<>(List.of(perConnection));
        return () -> {
            FakeDiagnosticService next = q.poll();
            if (next == null) {
                throw new IllegalStateException("no more scripted connections");
            }
            return next;
        };
    }

    private static TestScanServices service(Path recordings) {
        TestScanServices t = TestScanServices.withLabels(FakeTimeSource.autoAdvancing(), ScanLimits.DEFAULTS,
                LABEL_FILES, recordings);
        t.connector().sends("READ_IDENTIFICATION", 1); // 1A 9B answered
        return t;
    }

    private static LiveValueId id(String text) {
        return LiveValueId.tryParse(text).orElseThrow();
    }

    @Test
    @DisplayName("catalog from labels: names and specs only, B-lines never, the VIN group withheld; 2 requests")
    void catalogFromLabels() throws Exception {
        try (TestScanServices t = service(null)) {
            t.connector().kwp("01", connections(engine(PART)));
            LiveDataCatalog c = t.service().liveDataCatalog(Caller.mcp("list_available_data"), ENGINE);
            assertEquals(LiveDataCatalog.Source.LABELS, c.source());
            assertEquals(java.util.Optional.of("03L-997-030.lbl"), c.labelSource());
            assertEquals(java.util.Optional.of(PART), c.partNumber());
            assertEquals(List.of(1, 7, 11, 12, 13, 14), c.groups().stream().map(LiveDataCatalog.Group::group).toList());
            assertEquals(List.of(81), c.withheldGroups());
            LiveDataCatalog.Field rpm = c.field(id("01:001.1")).orElseThrow();
            assertEquals("Engine Speed (test)", rpm.name());
            assertEquals(List.of("Range: 0...5000 /min", "Specification (Idle): 780...920 /min"), rpm.specs());
            assertEquals(4, c.groups().stream().filter(g -> g.group() == 12).findFirst().orElseThrow().fields().size(),
                    "a labelled group without field labels offers fields 1-4 unnamed");
            assertTrue(c.field(id("01:003.1")).isEmpty(), "basic settings B003 is never offered");
            assertEquals(2, c.requests());
            assertEquals(List.of(), t.auditLines("request").stream().filter(l -> l.contains("MEASURING")).toList());
        }
    }

    @Test
    @DisplayName("reads: decoded values with names, units and specs; empty field; one request per group")
    void readValues() throws Exception {
        try (TestScanServices t = service(null)) {
            t.connector().kwp("01", connections(engine(PART),
                    engine(PART).onReadMeasuringBlock(block(7, G007)).onReadMeasuringBlock(block(1, G001))));
            t.service().liveDataCatalog(Caller.web(), ENGINE);
            LiveDataSnapshot s = t.service().readLiveData(Caller.mcp("read_live_data"),
                    List.of(id("01:007.1"), id("01:007.2"), id("01:001.1"), id("01:007.4")));
            assertEquals(LiveDataSnapshot.Status.OK, s.status());
            assertEquals(List.of("38.7 °C", "(empty)", "828 /min", "67.5 °C"), s.values().stream().map(LiveValue::text).toList());
            assertEquals(List.of(LiveValue.State.OK, LiveValue.State.EMPTY, LiveValue.State.OK, LiveValue.State.OK),
                    s.values().stream().map(LiveValue::state).toList());
            LiveValue rpm = s.values().get(2);
            assertEquals("Engine Speed (test)", rpm.name());
            assertEquals("/min", rpm.unit());
            assertEquals("RPM", rpm.quantity());
            assertEquals("01 73 24", rpm.raw());
            assertEquals(2, rpm.specs().size());
            assertEquals(4, s.requests(), "session + identification + 2 groups");
            assertEquals(2, t.auditLines("request").stream().filter(l -> l.contains("READ_MEASURING_BLOCK")).count());
        }
    }

    @Test
    @DisplayName("refused before anything is sent: no catalog, unknown, withheld, B-line group, duplicates, mixed modules, too many")
    void refusals() throws Exception {
        try (TestScanServices t = service(null)) {
            t.connector().kwp("01", connections(engine(PART)));
            ScanService s = t.service();
            InvalidLiveValueException none = assertThrows(InvalidLiveValueException.class,
                    () -> s.readLiveData(Caller.web(), List.of(id("01:001.1"))));
            assertTrue(none.getMessage().contains("list its available data first"), none.getMessage());
            s.liveDataCatalog(Caller.web(), ENGINE);
            int connections = t.connector().connectedModules().size();
            for (List<LiveValueId> bad : List.of(
                    List.<LiveValueId>of(),
                    List.of(id("01:002.1")),
                    List.of(id("01:003.1")),
                    List.of(id("01:081.1")),
                    List.of(id("01:001.1"), id("01:001.1")),
                    List.of(id("01:001.1"), id("03:001.1")),
                    List.of(id("01:001.1"), id("01:007.1"), id("01:011.2"), id("01:012.1"), id("01:013.1")))) {
                assertThrows(InvalidLiveValueException.class, () -> s.readLiveData(Caller.web(), bad), bad.toString());
                assertThrows(InvalidLiveValueException.class,
                        () -> s.recordLiveData(Caller.web(), bad, Duration.ofSeconds(5)), bad.toString());
            }
            InvalidLiveValueException withheld = assertThrows(InvalidLiveValueException.class,
                    () -> s.readLiveData(Caller.web(), List.of(id("01:081.1"))));
            assertTrue(withheld.getMessage().contains("withheld for privacy"), withheld.getMessage());
            for (Duration d : List.of(Duration.ZERO, Duration.ofMillis(999), Duration.ofSeconds(121))) {
                assertThrows(InvalidLiveValueException.class,
                        () -> s.recordLiveData(Caller.web(), List.of(id("01:001.1")), d), d.toString());
            }
            assertThrows(InvalidLiveValueException.class, () -> s.recordLiveData(Caller.web(), List.of(id("01:001.1")), null));
            assertEquals(connections, t.connector().connectedModules().size(), "nothing was sent");
            assertTrue(t.auditLines("refused").stream().filter(l -> l.contains("invalid-live-value")).count() >= 10);
            List<LiveValueId> sixteen = new java.util.ArrayList<>();
            for (int f = 1; f <= 4; f++) {
                for (int g : List.of(1, 7, 12, 13)) {
                    sixteen.add(new LiveValueId(ENGINE, g, f));
                }
            }
            sixteen.add(new LiveValueId(ENGINE, 14, 1));
            assertThrows(InvalidLiveValueException.class, () -> s.readLiveData(Caller.web(), sixteen));
        }
    }

    @Test
    @DisplayName("a different part number on read (another car): nothing else read, catalog dropped")
    void catalogChanged() throws Exception {
        try (TestScanServices t = service(null)) {
            t.connector().kwp("01", connections(engine(PART), engine("03L906018AB")));
            t.service().liveDataCatalog(Caller.web(), ENGINE);
            LiveDataSnapshot s = t.service().readLiveData(Caller.web(), List.of(id("01:001.1")));
            assertEquals(LiveDataSnapshot.Status.CATALOG_CHANGED, s.status());
            assertEquals(LiveValue.State.NOT_READ, s.values().getFirst().state());
            assertEquals(0, t.auditLines("request").stream().filter(l -> l.contains("READ_MEASURING_BLOCK")).count());
            assertThrows(InvalidLiveValueException.class, () -> t.service().readLiveData(Caller.web(), List.of(id("01:001.1"))));
        }
    }

    @Test
    @DisplayName("a group the module refuses: PARTIAL, the others are read")
    void partial() throws Exception {
        try (TestScanServices t = service(null)) {
            t.connector().kwp("01", connections(engine(PART), engine(PART)
                    .onReadMeasuringBlock(negative(0x21, 0x31)).onReadMeasuringBlock(block(7, G007))));
            t.service().liveDataCatalog(Caller.web(), ENGINE);
            LiveDataSnapshot s = t.service().readLiveData(Caller.web(), List.of(id("01:012.1"), id("01:007.1")));
            assertEquals(LiveDataSnapshot.Status.PARTIAL, s.status());
            assertEquals(LiveValue.State.NOT_READ, s.values().get(0).state());
            assertTrue(s.values().get(0).problem().orElseThrow().startsWith("group 012:"), s.values().get(0).problem().toString());
            assertEquals(LiveValue.State.OK, s.values().get(1).state());
        }
    }

    @Test
    @DisplayName("privacy backstop: a VIN-shaped answer is dropped (WITHHELD), never returned")
    void vinShapedAnswerWithheld() throws Exception {
        // 17 VIN characters in a row: made up, "1VWAB12C3DE456789" in ASCII.
        String vinLike = "61 0C 31 56 57 41 42 31 32 43 33 44 45 34 35 36 37 38 39 00";
        try (TestScanServices t = service(null)) {
            t.connector().kwp("01", connections(engine(PART), engine(PART).onReadMeasuringBlock(
                    ok(FakeDiagnosticService.measuringBlock(12, vinLike.substring(0, vinLike.length() - 3)
                            + " 00 00 00 00")))));
            t.service().liveDataCatalog(Caller.web(), ENGINE);
            LiveDataSnapshot s = t.service().readLiveData(Caller.web(), List.of(id("01:012.1")));
            LiveValue v = s.values().getFirst();
            assertEquals(LiveValue.State.WITHHELD, v.state());
            assertEquals("", v.raw());
            assertTrue(v.value().isEmpty());
        }
        assertTrue(VinShape.contains("xx1VWAB12C3DE456789".getBytes(StandardCharsets.US_ASCII)));
        assertFalse(VinShape.contains("1VWAB12C3DE45678I9".getBytes(StandardCharsets.US_ASCII)), "I is not a VIN letter");
        assertFalse(VinShape.contains("1VWAB12C3DE45678".getBytes(StandardCharsets.US_ASCII)), "16 is too short");
    }

    @Test
    @DisplayName("UDS module and 04: NOT_SUPPORTED, nothing sent, no adapter needed")
    void notSupported() throws Exception {
        try (TestScanServices t = service(null)) {
            t.connector().adapterConnected(false, "unplugged");
            for (String m : List.of("17", "05", "04")) {
                LiveDataCatalog c = t.service().liveDataCatalog(Caller.web(), ModuleAddress.parse(m));
                assertEquals(LiveDataCatalog.Source.NOT_SUPPORTED, c.source(), m);
                assertEquals(0, c.requests());
            }
            assertEquals(List.of(), t.connector().connectedModules());
            assertThrows(InvalidLiveValueException.class, () -> t.service().readLiveData(Caller.web(),
                    List.of(id("17:001.1"))));
        }
    }

    @Test
    @DisplayName("no label file: probed 40 groups per call within the budget until all 255 are asked")
    void probe() throws Exception {
        try (TestScanServices t = service(null)) {
            Supplier<FakeDiagnosticService> abs = () -> {
                FakeDiagnosticService f = FakeDiagnosticService.kwp().onStartSession(okHex("50 89"))
                        .onReadIdentification(ok(FakeDiagnosticService.identification(ApplicationProtocol.KWP2000,
                                Map.of(EcuIdentification.Field.PART_NUMBER, "1K0907379BL"))));
                for (int i = 0; i < LiveDataEngine.PROBE_GROUPS_PER_CALL; i++) {
                    f.onReadMeasuringBlock(negative(0x21, 0x31));
                }
                return f;
            };
            // First call: group 001 and 005 answer.
            FakeDiagnosticService first = FakeDiagnosticService.kwp().onStartSession(okHex("50 89"))
                    .onReadIdentification(ok(FakeDiagnosticService.identification(ApplicationProtocol.KWP2000,
                            Map.of(EcuIdentification.Field.PART_NUMBER, "1K0907379BL"))));
            for (int g = 1; g <= LiveDataEngine.PROBE_GROUPS_PER_CALL; g++) {
                first.onReadMeasuringBlock(g == 1 ? block(1, "61 01 07 64 00 07 64 00 25 00 00 07 64 00")
                        : g == 5 ? block(5, "61 05 06 4C B6") : negative(0x21, 0x31));
            }
            Deque<FakeDiagnosticService> q = new ArrayDeque<>(List.of(first));
            t.connector().kwp("03", () -> q.isEmpty() ? abs.get() : q.poll());
            ModuleAddress m = ModuleAddress.parse("03");
            LiveDataCatalog c = t.service().liveDataCatalog(Caller.web(), m);
            assertEquals(LiveDataCatalog.Source.PROBE_INCOMPLETE, c.source());
            assertEquals(40, c.probedThrough());
            assertEquals(List.of(1, 5), c.groups().stream().map(LiveDataCatalog.Group::group).toList());
            LiveDataCatalog.Group g1 = c.groups().getFirst();
            assertEquals(List.of(1, 2, 4), g1.fields().stream().map(f -> f.id().field()).toList(), "empty field 3 not offered");
            assertEquals("Speed", g1.fields().getFirst().quantity());
            assertEquals("km/h", g1.fields().getFirst().unit());
            assertEquals("", g1.fields().getFirst().name());
            assertTrue(c.requests() <= ScanLimits.DEFAULTS.maxRequestsPerModulePerScan(), "within the budget: " + c.requests());
            LiveDataCatalog last = c;
            for (int i = 0; i < 6; i++) {
                t.time().advance(Duration.ofSeconds(60)); // stay inside the one-minute window
                last = t.service().liveDataCatalog(Caller.web(), m);
            }
            assertEquals(LiveDataCatalog.Source.PROBED, last.source());
            assertEquals(255, last.probedThrough());
            assertEquals(List.of(1, 5), last.groups().stream().map(LiveDataCatalog.Group::group).toList());
        }
    }

    @Test
    @DisplayName("recording: paced at one group per 250 ms, own bounded budget, summary and CSV file")
    void recording(@TempDir Path dir) throws Exception {
        Path recordings = dir.resolve("recordings");
        try (TestScanServices t = service(recordings)) {
            FakeDiagnosticService rec = engine(PART);
            for (int i = 0; i < 40; i++) {
                int b = 0x22 + (i % 5); // 0.2 * 0x73 * b: 782..874 /min
                rec.onReadMeasuringBlock(block(1, String.format("61 01 01 73 %02X 27 46 16 64 50 29 05 09 B3", b)));
                rec.onReadMeasuringBlock(block(11, G011));
            }
            t.connector().kwp("01", connections(engine(PART), rec));
            t.service().liveDataCatalog(Caller.web(), ENGINE);
            LiveDataRecording r = t.service().recordLiveData(Caller.mcp("record_live_data"),
                    List.of(id("01:001.1"), id("01:011.2")), Duration.ofSeconds(5));
            assertEquals(LiveDataSnapshot.Status.OK, r.status(), r.notes().toString());
            assertTrue(r.rounds() >= 9 && r.rounds() <= 11, "two groups at 250 ms each over 5 s: " + r.rounds());
            LiveSeries rpm = r.series().getFirst();
            assertEquals(r.rounds(), rpm.samples());
            assertEquals("782", rpm.min().orElseThrow().toPlainString());
            assertEquals("874", rpm.max().orElseThrow().toPlainString());
            assertEquals("/min", rpm.unit());
            LiveSeries boost = r.series().get(1);
            assertTrue(boost.events().stream().anyMatch(e -> e.startsWith(r.rounds() + " of " + r.rounds()
                    + " samples outside the label's range 0.0...1000.0")), boost.events().toString());
            // Pacing: consecutive measuring-block reads at least 250 ms apart on the fake clock.
            List<Long> at = t.connector().events("01").stream().filter(e -> e.what().equals("READ_MEASURING_BLOCK"))
                    .map(FakeModuleConnector.Event::atNanos).toList();
            for (int i = 1; i < at.size(); i++) {
                assertTrue(at.get(i) - at.get(i - 1) >= LiveDataEngine.RECORDING_REQUEST_INTERVAL.toNanos(),
                        "gap " + (at.get(i) - at.get(i - 1)));
            }
            assertTrue(r.requests() <= LiveDataEngine.recordingBudget(Duration.ofSeconds(5)));
            Path file = r.file().orElseThrow();
            assertEquals(recordings.resolve(r.id() + ".csv"), file);
            List<String> csv = Files.readAllLines(file);
            assertEquals("seconds,01:001.1 Engine Speed (test) [/min],01:011.2 Boost Pressure (specified) [mbar]",
                    csv.getFirst());
            assertEquals(r.rounds() + 1, csv.size());
            assertTrue(csv.get(1).startsWith("0.000,782,1004.3"), csv.get(1));
            assertEquals(2, t.auditLines("scan").stream().filter(l -> l.contains("record-")).count());
        }
    }

    @Test
    @DisplayName("recording budget: one request per 250 ms plus overhead, never above the hard cap")
    void recordingBudget() {
        assertEquals(4 + LiveDataEngine.RECORDING_OVERHEAD, LiveDataEngine.recordingBudget(Duration.ofSeconds(1)));
        assertEquals(480 + LiveDataEngine.RECORDING_OVERHEAD, LiveDataEngine.recordingBudget(Duration.ofSeconds(120)));
        assertEquals(LiveDataEngine.MAX_RECORDING_REQUESTS, LiveDataEngine.recordingBudget(Duration.ofSeconds(500)));
        assertTrue(240 < ScanLimits.DEFAULTS.maxRequestsPerMinute(), "a recording alone stays under the minute window");
    }

    @Test
    @DisplayName("value IDs: exact 01:007.3 form only")
    void ids() {
        assertEquals("01:007.3", id("01:007.3").toString());
        assertEquals(ModuleAddress.parse("2B"), id("2b:255.4").module());
        for (String bad : List.of("01:7.3", "01:007.5", "01:007.0", "01:000.1", "01:256.1", "99:001.1", "01-007.3",
                "01:007.3 ", " 01:007.3", "01:0007.3", "", "01:007")) {
            assertTrue(LiveValueId.tryParse(bad).isEmpty(), bad);
        }
        assertTrue(LiveValueId.tryParse(null).isEmpty());
    }

    @Test
    @DisplayName("series: min/max/avg, range events, empty and not-read counts")
    void series() {
        LiveDataCatalog.Field f = new LiveDataCatalog.Field(id("01:001.1"), "x", "", "", List.of("Range: 0...100 /min"));
        java.time.Instant at = java.time.Instant.EPOCH;
        List<LiveValue> vs = List.of(
                new LiveValue(f.id(), "x", "RPM", "/min", java.util.Optional.of(new java.math.BigDecimal("90")), "", "",
                        List.of(), LiveValue.State.OK, java.util.Optional.empty(), at),
                new LiveValue(f.id(), "x", "RPM", "/min", java.util.Optional.of(new java.math.BigDecimal("120")), "", "",
                        List.of(), LiveValue.State.OK, java.util.Optional.empty(), at),
                new LiveValue(f.id(), "x", "", "", java.util.Optional.empty(), "", "", List.of(), LiveValue.State.NOT_READ,
                        java.util.Optional.of("group 001: timeout"), at));
        LiveSeries s = LiveDataEngine.series(f, vs, List.of(0.0, 0.5, 1.0));
        assertEquals("90", s.min().orElseThrow().toPlainString());
        assertEquals("120", s.max().orElseThrow().toPlainString());
        assertEquals("105.0", s.average().orElseThrow().toPlainString());
        assertEquals(0.5, s.maxAtSeconds().orElseThrow());
        assertEquals(List.of("1 of 2 samples outside the label's range 0...100 (seen 120 to 120)",
                "1 of 3 samples not read (group 001: timeout)"), s.events());
    }
}
