package io.github.samsanjaysatish.macvagdiag.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.can.AbstractCanAdapter;
import io.github.samsanjaysatish.macvagdiag.scan.Caller;
import io.github.samsanjaysatish.macvagdiag.scan.LiveDataCatalog;
import io.github.samsanjaysatish.macvagdiag.scan.LiveDataRecording;
import io.github.samsanjaysatish.macvagdiag.scan.LiveDataSnapshot;
import io.github.samsanjaysatish.macvagdiag.scan.LiveSeries;
import io.github.samsanjaysatish.macvagdiag.scan.LiveValue;
import io.github.samsanjaysatish.macvagdiag.scan.LiveValueId;
import io.github.samsanjaysatish.macvagdiag.scan.ModuleAddress;
import io.github.samsanjaysatish.macvagdiag.scan.ScanService;
import io.github.samsanjaysatish.macvagdiag.scan.SessionStatus;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * <b>Manual only, and it transmits:</b> CANable plugged into the car, ignition on (engine idling for the
 * comparison to mean much), <b>VCDS unplugged</b>. Run it on its own:
 * {@code ./gradlew :server:hardwareTest --tests '*LiveDataHardwareTest'}. Never runs in {@code build} or CI.
 *
 * <p>Checks v2 live data on the real engine the way the fakes assume it works: the engine's catalog (label file
 * from {@code local/label-files-decoded} if present, else probed), one read of groups 001, 007 and 011
 * ({@code 21 01}, {@code 21 07}, {@code 21 0B}), then a 5 s recording of the same groups. Each field is compared
 * with the 2026-11-18 VCDS CSVs in {@code local/vcds-logs/} (group 001 and 011 from {@code step2}, 007 from
 * {@code step1}): same unit, blank in VCDS ⇔ empty for us (007 field 2, the oil temperature this engine doesn't
 * report). Values are another day's, so they are printed side by side, not compared; only plausibility is asserted.
 *
 * <p>Appends to the audit log and frame trace in the git-ignored {@code local/hardware-runs/} (the test runs in
 * {@code server/}); the recording's CSV goes to {@code recordings/} next to the audit log.
 */
@Tag("hardware")
@SpringBootTest(properties = {
        "macvagdiag.demo=false",
        "logging.file.name=../local/hardware-runs/server-liveDataHardwareTest-frames.log",
        "logging.level." + AbstractCanAdapter.FRAME_LOGGER + "=TRACE",
        "logging.level.io.github.samsanjaysatish.macvagdiag.transport=DEBUG"})
// Close the context (and the serial port) afterwards, so HardwareSmokeTest's context can open it in the same JVM.
@DirtiesContext
class LiveDataHardwareTest {

    static final Path LOCAL = Path.of("..", "local").toAbsolutePath().normalize();
    static final Path AUDIT = LOCAL.resolve("hardware-runs/server-liveDataHardwareTest-audit.jsonl");
    static final Path LABELS = LOCAL.resolve("label-files-decoded");
    static final Path VCDS_STEP1 = LOCAL.resolve("vcds-logs/2026-11-18-002922-step1.csv");
    static final Path VCDS_STEP2 = LOCAL.resolve("vcds-logs/2026-11-18-003345-step2.csv");

    static final ModuleAddress ENGINE = ModuleAddress.tryParse("01").orElseThrow();
    static final List<Integer> GROUPS = List.of(1, 7, 11);
    static final Duration RECORDING = Duration.ofSeconds(5);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("macvagdiag.audit-log", AUDIT::toString);
        registry.add("macvagdiag.labels", () -> Files.isDirectory(LABELS) ? LABELS.toString() : "");
    }

    @Autowired
    ScanService scan;

    @Test
    @Timeout(120)
    @DisplayName("real engine: catalog, read and 5 s recording of groups 001/007/011 match the 2026-11-18 VCDS CSVs")
    void engineMeasuringBlocks() throws Exception {
        SessionStatus status = scan.sessionStatus(Caller.web());
        assertEquals(SessionStatus.AdapterState.CONNECTED, status.adapter(), status.adapterDetail());
        long auditStart = Files.exists(AUDIT) ? Files.readAllLines(AUDIT).size() : 0;

        // 1. Catalog. Groups 001..040 are in the first probe call, so a probed catalog has ours too.
        LiveDataCatalog catalog = scan.liveDataCatalog(Caller.web(), ENGINE);
        System.out.printf("catalog: %s, label %s, %d groups, %d requests%n  trail: %s%n  notes: %s%n",
                catalog.source(), catalog.labelSource().orElse("(none)"), catalog.groups().size(),
                catalog.requests(), catalog.labelTrail(), catalog.notes());
        assertFalse(Set.of(LiveDataCatalog.Source.NOT_RESPONDING, LiveDataCatalog.Source.NOT_SUPPORTED)
                .contains(catalog.source()), "engine catalog: " + catalog.source() + " " + catalog.notes());
        List<LiveValueId> ids = new ArrayList<>();
        for (int group : GROUPS) {
            LiveDataCatalog.Group g = catalog.groups().stream().filter(x -> x.group() == group).findFirst()
                    .orElseThrow(() -> new AssertionError("group " + group + " not in the catalog"));
            g.fields().forEach(f -> ids.add(f.id()));
        }

        // 2. One read.
        LiveDataSnapshot snapshot = scan.readLiveData(Caller.web(), ids);
        System.out.printf("read: %s, %d requests, label %s, notes %s%n", snapshot.status(), snapshot.requests(),
                snapshot.labelSource().orElse("(none)"), snapshot.notes());
        assertEquals(LiveDataSnapshot.Status.OK, snapshot.status(), snapshot.notes().toString());
        assertEquals(ids.size(), snapshot.values().size());

        // 3. Compare with VCDS, field by field.
        Map<LiveValueId, VcdsColumn> vcds = new HashMap<>();
        vcds.putAll(VcdsColumn.read(VCDS_STEP1));
        vcds.putAll(VcdsColumn.read(VCDS_STEP2));
        if (vcds.isEmpty()) {
            System.out.println("VCDS CSVs not found in " + LOCAL.resolve("vcds-logs") + ": units not compared");
        }
        System.out.printf("%-9s %-40s %-14s %-10s | VCDS 2026-11-18, min..max%n", "id", "name", "ours", "raw");
        List<String> mismatches = new ArrayList<>();
        for (LiveValue v : snapshot.values()) {
            VcdsColumn c = vcds.get(v.id());
            System.out.printf("%-9s %-40s %-14s %-10s | %s%n", v.id(), v.name().isEmpty() ? v.quantity() : v.name(),
                    v.state() == LiveValue.State.OK ? v.value().orElseThrow().toPlainString() + v.unit()
                            : v.state().toString(),
                    v.raw(), c == null ? "(not in CSV)" : c);
            assertTrue(v.state() == LiveValue.State.OK || v.state() == LiveValue.State.EMPTY,
                    v.id() + " " + v.state() + " " + v.problem().orElse("") + " " + v.text());
            v.value().ifPresent(x -> assertPlausible(v, x));
            if (c == null) {
                continue;
            }
            if (c.blank() != (v.state() == LiveValue.State.EMPTY)) {
                mismatches.add(v.id() + ": VCDS " + (c.blank() ? "blank" : "a value") + ", ours " + v.state());
            } else if (!c.blank() && !c.unit().equals(v.unit().strip())) {
                mismatches.add(v.id() + ": unit VCDS '" + c.unit() + "', ours '" + v.unit().strip() + "'");
            }
        }
        assertTrue(mismatches.isEmpty(), "differences from VCDS: " + mismatches);

        // 4. A short recording of the same values.
        LiveDataRecording rec = scan.recordLiveData(Caller.web(), ids, RECORDING);
        System.out.printf("recording: %s, %d rounds in %s, %d requests, file %s, notes %s%n", rec.status(),
                rec.rounds(), rec.recorded(), rec.requests(), rec.file().map(Path::toString).orElse("(none)"),
                rec.notes());
        for (LiveSeries s : rec.series()) {
            System.out.printf("  %-9s samples %d decoded %d min %s max %s avg %s events %s%n", s.id(), s.samples(),
                    s.decoded(), s.min().map(BigDecimal::toPlainString).orElse("-"),
                    s.max().map(BigDecimal::toPlainString).orElse("-"),
                    s.average().map(BigDecimal::toPlainString).orElse("-"), s.events());
        }
        assertEquals(LiveDataSnapshot.Status.OK, rec.status(), rec.notes().toString());
        assertTrue(rec.rounds() >= 1, "no round recorded");
        // At least 250 ms from each answer to the next request: 3 groups a round, so at most ~7 rounds in 5 s.
        long maxRounds = RECORDING.toMillis() / (250L * GROUPS.size()) + 1;
        assertTrue(rec.rounds() <= maxRounds, rec.rounds() + " rounds is faster than the 250 ms pacing allows");
        assertTrue(rec.file().isPresent() && Files.exists(rec.file().get()), "recording CSV not written");
        rec.series().forEach(s -> assertEquals(rec.rounds(), s.samples(), s.id() + " samples"));

        // 5. What went to the car, from the audit log: measuring blocks only for our groups, never 21 00.
        List<String> newLines = Files.readAllLines(AUDIT).stream().skip(auditStart).toList();
        Pattern measuring = Pattern.compile("\"event\":\"request\".*\"module\":\"01\".*\"request\":\"21 ([0-9A-F]{2})\"");
        List<String> groupsSent = new ArrayList<>();
        for (String line : newLines) {
            Matcher m = measuring.matcher(line);
            if (m.find()) {
                groupsSent.add(m.group(1));
            }
        }
        System.out.println("21 xx sent: " + groupsSent.size() + " " + groupsSent.stream().distinct().toList());
        assertFalse(groupsSent.contains("00"), "21 00 was sent");
        if (catalog.source() == LiveDataCatalog.Source.LABELS) {
            assertTrue(Set.of("01", "07", "0B").containsAll(groupsSent), "unexpected groups: " + groupsSent);
            assertEquals(3 + 3 * rec.rounds(), groupsSent.size(), "one 21 per group per read / round");
        }
    }

    /** Loose physical limits: catches a wrong formula or field order, not a sick engine. */
    private static void assertPlausible(LiveValue v, BigDecimal value) {
        double x = value.doubleValue();
        String unit = v.unit().strip();
        boolean ok = switch (unit) {
            case "/min" -> x >= 0 && x <= 6000;
            case "°C" -> x >= -40 && x <= 150;
            case "mbar" -> x >= 0 && x <= 3500;
            case "bar" -> x >= 0 && x <= 2500;
            case "%" -> x >= 0 && x <= 100;
            case "mg/str" -> x >= 0 && x <= 100;
            default -> true;
        };
        assertTrue(ok, v.id() + " implausible: " + value.toPlainString() + " " + unit);
    }

    /**
     * One field's column in a VCDS measuring-blocks CSV: three groups side by side, each a TIME column and four
     * fields; rows 4–5 are the name, row 6 the unit (Latin-1, so {@code °C} reads right), then the samples.
     */
    record VcdsColumn(String name, String unit, boolean blank, Optional<Double> min, Optional<Double> max) {

        private static final Pattern GROUP = Pattern.compile("'(\\d{3})");

        static Map<LiveValueId, VcdsColumn> read(Path csv) throws IOException {
            Map<LiveValueId, VcdsColumn> out = new HashMap<>();
            if (!Files.exists(csv)) {
                return out;
            }
            List<String[]> rows = Files.readAllLines(csv, StandardCharsets.ISO_8859_1).stream()
                    .map(l -> l.split(",", -1)).toList();
            String[] groupRow = rows.get(3);
            for (int block = 0; 2 + 5 * block < groupRow.length; block++) {
                Matcher g = GROUP.matcher(groupRow[2 + 5 * block]);
                if (!g.find()) {
                    continue;
                }
                int group = Integer.parseInt(g.group(1));
                for (int field = 1; field <= 4; field++) {
                    int col = 1 + 5 * block + field;
                    double lo = Double.MAX_VALUE;
                    double hi = -Double.MAX_VALUE;
                    boolean any = false;
                    for (String[] row : rows.subList(7, rows.size())) {
                        String cell = col < row.length ? row[col].strip() : "";
                        if (!cell.isEmpty()) {
                            double d = Double.parseDouble(cell);
                            lo = Math.min(lo, d);
                            hi = Math.max(hi, d);
                            any = true;
                        }
                    }
                    String name = (cell(rows.get(4), col) + " " + cell(rows.get(5), col)).strip();
                    out.put(new LiveValueId(ENGINE, group, field), new VcdsColumn(name, cell(rows.get(6), col),
                            !any, any ? Optional.of(lo) : Optional.empty(), any ? Optional.of(hi) : Optional.empty()));
                }
            }
            return out;
        }

        private static String cell(String[] row, int col) {
            return col < row.length ? row[col].strip() : "";
        }

        @Override
        public String toString() {
            if (blank) {
                return name + ": blank";
            }
            return name + ": " + (min.equals(max) ? min.orElseThrow() : min.orElseThrow() + ".." + max.orElseThrow())
                    + " " + unit;
        }
    }
}
