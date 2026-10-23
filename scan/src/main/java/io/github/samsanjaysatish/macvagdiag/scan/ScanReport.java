package io.github.samsanjaysatish.macvagdiag.scan;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * The plain-text Auto-Scan report. It follows the VCDS Auto-Scan layout ("Address 17: Instruments", part numbers,
 * "1 Fault Found:", one block per code) so the two can be compared side by side, with more whitespace and every
 * undecoded value shown raw with its request ID. Pure function of the result; sends nothing.
 */
public final class ScanReport {

    static final int WIDTH = 78;
    static final String RULE = "-".repeat(WIDTH);
    static final String DEMO_BANNER = "*** DEMO DATA: simulated car, not a real vehicle ***";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
            .withZone(ZoneOffset.UTC);
    private static final String INDENT = "   ";
    private static final String CODE_INDENT = "              ";

    private ScanReport() {
    }

    /**
     * @param maskVin true to mask the VIN everywhere (header, identification, raw records); see {@link VinMask}
     */
    public static String text(AutoScanResult result, boolean maskVin) {
        AutoScanResult r = maskVin ? result.maskVin() : result;
        StringBuilder b = new StringBuilder();
        b.append("macvagdiag Auto-Scan (read-only)\n").append("=".repeat(WIDTH)).append('\n');
        if (r.demo()) {
            b.append(DEMO_BANNER).append('\n');
        }
        field(b, "", "Date", DATE.format(r.startedAt()));
        field(b, "", "Duration", seconds(Duration.between(r.startedAt(), r.finishedAt())));
        field(b, "", "VIN", r.vin().map(v -> v + (maskVin ? "  (masked)" : "")
                + r.vinSource().map(m -> "  from module " + m.hex()).orElse("")).orElse("not read"));
        field(b, "", "Requested by", r.caller().label());
        field(b, "", "Modules", String.format("%d checked, %d responding, %d with faults, %d fault code(s)",
                r.modules().size(), r.respondingModules().size(),
                r.modules().stream().filter(m -> !m.faults().isEmpty()).count(), r.faultCount()));
        field(b, "", "Discovery", "fixed list of known addresses (gateway installation list not read yet)");
        if (r.cancelled()) {
            b.append("Cancelled before the end.\n");
        }
        for (String note : r.notes()) {
            field(b, "", "Note", note);
        }
        b.append('\n');
        for (ModuleResult m : r.modules()) {
            b.append(RULE).append('\n');
            module(b, m);
        }
        b.append("End ").append("-".repeat(WIDTH - 4)).append('\n');
        return b.toString();
    }

    /** The section for one module (used by the web page and for single-module reads). */
    public static String text(ModuleResult result, boolean maskVin) {
        StringBuilder b = new StringBuilder();
        module(b, maskVin ? result.maskVin() : result);
        return b.toString();
    }

    private static void module(StringBuilder b, ModuleResult m) {
        b.append(String.format("Address %s: %-30s Status: %s%n", m.module().hex(), m.module().name(), status(m)));
        m.link().ifPresent(l -> field(b, INDENT, "Protocol", l.describe()));
        m.identification().ifPresent(id -> identification(b, id));
        switch (m.status()) {
            case NOT_RESPONDING, SKIPPED -> {
            }
            default -> faults(b, m);
        }
        for (String note : m.notes()) {
            field(b, INDENT, "Note", note);
        }
        m.limitHit().ifPresent(h -> field(b, INDENT, "Limit", h.message()));
    }

    private static String status(ModuleResult m) {
        return switch (m.status()) {
            case OK -> "OK";
            case FAULTS -> m.faults().size() + (m.faults().size() == 1 ? " fault" : " faults");
            case NOT_RESPONDING -> "no response (not installed, or not reachable)";
            default -> m.status().description();
        };
    }

    private static void identification(StringBuilder b, EcuInfo id) {
        id.partNumber().ifPresent(v -> field(b, INDENT, "Part No", v));
        id.hardwareNumber().ifPresent(v -> field(b, INDENT, "Hardware", v));
        id.systemName().ifPresent(v -> field(b, INDENT, "Component", v));
        id.softwareVersion().ifPresent(v -> field(b, INDENT, "Software", v));
        id.coding().ifPresent(v -> field(b, INDENT, "Coding", v));
        id.vin().ifPresent(v -> field(b, INDENT, "VIN", v));
        boolean anyDecoded = id.partNumber().isPresent() || id.systemName().isPresent();
        for (RawValue raw : id.raw()) {
            if (anyDecoded) {
                continue; // decoded fields above already show these
            }
            field(b, INDENT, "Raw " + raw.id(), raw.text().map(t -> "\"" + t + "\"").orElse(raw.hex()));
        }
        for (String note : id.notes()) {
            field(b, INDENT, "Note", note);
        }
    }

    private static void faults(StringBuilder b, ModuleResult m) {
        if (m.faults().isEmpty()) {
            if (m.status() == ModuleStatus.OK) {
                b.append(INDENT).append("No fault code found.\n");
            }
            return;
        }
        b.append(INDENT).append(m.faults().size()).append(m.faults().size() == 1 ? " Fault Found:\n" : " Faults Found:\n");
        for (FaultCode f : m.faults()) {
            b.append(INDENT).append(f.displayCode()).append(f.obdCode().map(c -> " (" + c + ")").orElse(""))
                    .append(" - status ").append(f.statusText()).append('\n');
            b.append(CODE_INDENT).append("Record: ").append(f.record()).append('\n');
            freezeFrame(b, f.freezeFrame());
        }
        if (!m.faultListComplete()) {
            field(b, INDENT, "Note", "the fault list did not decode cleanly; see the notes");
        }
    }

    private static void freezeFrame(StringBuilder b, FreezeFrameData ff) {
        switch (ff.availability()) {
            case READ -> {
                b.append(CODE_INDENT).append("Freeze frame (raw, not decoded yet):\n");
                for (FreezeFrameData.Field f : ff.fields()) {
                    b.append(CODE_INDENT).append("   ").append(f.kind()).append(": ").append(f.value()).append('\n');
                }
                for (RawValue r : ff.records()) {
                    b.append(CODE_INDENT).append("   ").append(r.id()).append(": ").append(r.hex()).append('\n');
                }
                for (String note : ff.notes()) {
                    b.append(CODE_INDENT).append("   Note: ").append(note).append('\n');
                }
            }
            case NOT_AVAILABLE -> b.append(CODE_INDENT).append("Freeze frame: not available (")
                    .append(firstClause(ff.message())).append(")\n");
            case FAILED -> b.append(CODE_INDENT).append("Freeze frame: not read (").append(ff.message()).append(")\n");
            case SKIPPED -> b.append(CODE_INDENT).append("Freeze frame: skipped (").append(ff.message()).append(")\n");
        }
    }

    /** The text up to the first ';', so long explanations stay one line in the report. */
    private static String firstClause(String text) {
        int i = text.indexOf(';');
        return i < 0 ? text : text.substring(0, i);
    }

    private static void field(StringBuilder b, String indent, String name, String value) {
        b.append(indent).append(String.format("%-13s", name + ":")).append(' ').append(value).append('\n');
    }

    private static String seconds(Duration d) {
        return String.format("%.1f s", d.toMillis() / 1000.0);
    }

    /** For tests: the module section's first line. */
    static Optional<String> headerLine(String report, ModuleAddress module) {
        return report.lines().filter(l -> l.startsWith("Address " + module.hex() + ":")).findFirst();
    }
}
