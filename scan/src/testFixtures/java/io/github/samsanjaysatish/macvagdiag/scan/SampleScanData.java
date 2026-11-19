package io.github.samsanjaysatish.macvagdiag.scan;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Canned scan results for front-end tests (JSON shapes, report snapshots). <b>Sample data, not a real car</b>:
 * the mileage is {@value #MILEAGE_KM} km. Every value is fixed, so snapshots are stable.
 */
public final class SampleScanData {

    public static final int MILEAGE_KM = 123450;
    public static final Instant AT = Instant.parse("2026-01-01T10:00:00Z");

    private SampleScanData() {
    }

    public static SessionStatus sessionStatus() {
        return new SessionStatus(SessionStatus.AdapterState.CONNECTED, "sample adapter",
                SessionStatus.VehicleCondition.unknown(DefaultScanService.IGNITION_UNKNOWN),
                SessionStatus.VehicleCondition.unknown(DefaultScanService.ENGINE_UNKNOWN), false, AT);
    }

    public static EcuInfo engineInfo() {
        return new EcuInfo(Protocol.KWP2000, Optional.of("03L906018XX"), Optional.of("9971"), Optional.empty(),
                Optional.of("SAMPLE R4 2.0L TDI"), Optional.of("0001234"),
                List.of(new RawValue("1A 9B", hexOf("03L906018XX"), Optional.of("03L906018XX"))), List.of());
    }

    /** 17's odometer: {@code 62 22 03 30 39} = 12345 × 10 km. */
    public static Mileage mileage() {
        return new Mileage(MILEAGE_KM, ModuleAddress.INSTRUMENTS, new RawValue("22 22 03", "62 22 03 30 39",
                Optional.empty()), AT.plusMillis(200));
    }

    /** 17 Instruments (UDS): U0115-00, confirmed. */
    public static FaultCode udsFault() {
        return new FaultCode("U0115", Protocol.UDS, "C1 15 00 08", 0x08, "08 (00001000): Intermittent - Confirmed - Tested Since Memory Clear", List.of("confirmedDTC"),
                Optional.empty(), OptionalInt.of(0),
                new FreezeFrameData(FreezeFrameData.Availability.READ, "read",
                        List.of(new RawValue("19 06 C1 15 00 FF", "01 05 02", Optional.empty())), List.of(),
                        List.of("59 06 C1 15 00 08 01 05 02"), List.of()));
    }

    /** 01 Engine (KWP2000): 001025 = P0401, MIL on, type 002, with a KWP freeze frame. */
    public static FaultCode kwpEngineFault() {
        return new FaultCode("001025", Protocol.KWP2000, "04 01 E2", 0xE2, "E2 (11100010): type 002, MIL ON",
                List.of("MIL ON"), Optional.of("P0401"), OptionalInt.empty(),
                new FreezeFrameData(FreezeFrameData.Availability.READ, "read",
                        List.of(new RawValue("12 00 04 04 01", "6C 01 E2 02 01 FF 00 30 39 00 01 FF BF 20 B1 04 04 01",
                                Optional.empty())),
                        List.of(new FreezeFrameData.Field("MILEAGE", "12345 km", "12 00 04 04 01")),
                        List.of("52 00 6C 01 E2 02 01 FF 00 30 39 00 01 FF BF 20 B1 04 04 01"), List.of()));
    }

    /** 03 ABS (KWP2000): 01314, intermittent, type 003, no freeze frame stored. */
    public static FaultCode kwpFault() {
        return new FaultCode("01314", Protocol.KWP2000, "05 22 23", 0x23, "23 (00100011): type 003, Intermittent",
                List.of("Intermittent"), Optional.empty(), OptionalInt.empty(),
                new FreezeFrameData(FreezeFrameData.Availability.NOT_AVAILABLE, "sample: no freeze frame stored",
                        List.of(), List.of(), List.of(), List.of()));
    }

    /** What the sample gateway says about {@code m}: faults on 01, 03, 17; 2E and 56 cannot be reached. */
    public static GatewayStatus gatewayStatus(ModuleAddress m) {
        int tp20 = m.links().stream().filter(l -> l instanceof ModuleLink.Tp20).map(l -> ((ModuleLink.Tp20) l)
                .logicalAddress()).findFirst().orElse(0);
        return switch (m.hex()) {
            case "01", "03", "17" -> new GatewayStatus(m.address(), Optional.of(m), tp20, GatewayStatus.Kind.MALFUNCTION, 0x05);
            case "2E", "56" -> new GatewayStatus(m.address(), Optional.of(m), tp20, GatewayStatus.Kind.CANNOT_BE_REACHED,
                    0x19);
            default -> new GatewayStatus(m.address(), Optional.of(m), tp20, GatewayStatus.Kind.OK, 0x01);
        };
    }

    /**
     * A plausible result for any known module: 01 and 03 with KWP faults, 17 with a UDS fault, 04 status from the
     * gateway only, 2E and 56 not reachable, else OK; each with the sample gateway status.
     */
    public static ModuleResult moduleResult(ModuleAddress m) {
        Instant end = AT.plusMillis(400);
        Optional<ModuleLink> link = m.links().stream().findFirst();
        ModuleResult r = switch (m.hex()) {
            case "01" -> new ModuleResult(m, ModuleStatus.FAULTS, link, Optional.of(engineInfo()),
                    List.of(kwpEngineFault()), true, List.of(), Optional.empty(), 5, AT, end);
            case "03" -> new ModuleResult(m, ModuleStatus.FAULTS, link, Optional.empty(), List.of(kwpFault()), true,
                    List.of(), Optional.empty(), 4, AT, end);
            case "17" -> new ModuleResult(m, ModuleStatus.FAULTS, link, Optional.empty(), List.of(udsFault()), true,
                    List.of(), Optional.empty(), 5, AT, end, Optional.empty(), Optional.of(mileage()));
            case "04" -> new ModuleResult(m, ModuleStatus.GATEWAY_STATUS_ONLY, Optional.empty(), Optional.empty(),
                    List.of(), false, List.of("04 " + ScanEngine.NO_LINK), Optional.empty(), 0, AT, AT);
            case "2E", "56" -> new ModuleResult(m, ModuleStatus.NOT_RESPONDING, Optional.empty(), Optional.empty(),
                    List.of(), false, List.of("no response"), Optional.empty(), 0, AT, end);
            default -> new ModuleResult(m, ModuleStatus.OK, link, Optional.empty(), List.of(), true, List.of(),
                    Optional.empty(), 2, AT, end);
        };
        return r.withGatewayStatus(gatewayStatus(m));
    }

    public static List<ModuleAvailability> moduleList() {
        List<ModuleAvailability> out = new ArrayList<>();
        for (ModuleAddress m : ModuleAddress.known()) {
            ModuleResult r = moduleResult(m);
            ModuleAvailability.Reachability reach = r.responded() ? ModuleAvailability.Reachability.RESPONDING
                    : m.hasLink() ? ModuleAvailability.Reachability.NOT_RESPONDING
                    : ModuleAvailability.Reachability.NOT_CHECKED;
            out.add(new ModuleAvailability(m, reach, r.link(), r.responded() ? "responds" : r.notes().getFirst(), AT,
                    r.gatewayStatus()));
        }
        return out;
    }

    public static VehicleInfo vehicleInfo() {
        return new VehicleInfo(Optional.of(mileage()),
                List.of(new ModuleIdentity(ModuleAddress.ENGINE, ModuleAvailability.Reachability.RESPONDING,
                        Optional.of(ModuleAddress.ENGINE.links().getFirst()), Optional.of(engineInfo()), List.of()),
                        new ModuleIdentity(ModuleAddress.INSTRUMENTS, ModuleAvailability.Reachability.RESPONDING,
                                Optional.of(ModuleAddress.INSTRUMENTS.links().getFirst()), Optional.empty(),
                                List.of("identification not read: sample"), Optional.of(mileage()))),
                AT, false);
    }

    public static AutoScanResult autoScan() {
        List<ModuleResult> modules = ModuleAddress.known().stream().map(SampleScanData::moduleResult).toList();
        List<GatewayStatus> list = ModuleAddress.known().stream().map(SampleScanData::gatewayStatus).toList();
        return new AutoScanResult("scan-sample", Caller.web(), AT, AT.plus(Duration.ofSeconds(6)),
                Optional.of(mileage()), modules, false, false,
                List.of("module list: from the gateway's installation list (" + list.size() + " installed)"), list);
    }

    private static String hexOf(String text) {
        StringBuilder b = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (!b.isEmpty()) {
                b.append(' ');
            }
            b.append(String.format("%02X", (int) c));
        }
        return b.toString();
    }

    // ---- live data (v2): made-up values, labels written for the sample, not from a real label file ----

    /** The engine's sample catalog: groups 001 and 007 labelled; group 081 withheld. */
    public static LiveDataCatalog liveDataCatalog(ModuleAddress m) {
        if (!LiveDataEngine.supported(m)) {
            return new LiveDataCatalog(m, LiveDataCatalog.Source.NOT_SUPPORTED, Optional.empty(), Optional.empty(),
                    List.of(), List.of(), List.of(), 0, List.of("module " + m.hex() + " speaks UDS: not readable yet"),
                    Optional.empty(), 0, AT);
        }
        if (!m.equals(ModuleAddress.ENGINE)) {
            return new LiveDataCatalog(m, LiveDataCatalog.Source.PROBED, Optional.of("1K0907379AC"), Optional.empty(),
                    List.of("no label file for 1K0-907-379-AC at " + m.hex()), List.of(), List.of(), 255,
                    List.of("no label file: every group 001..255 was asked; none answered"), Optional.empty(), 7, AT);
        }
        LiveDataCatalog.Group g1 = new LiveDataCatalog.Group(1, "Sample Basic Values", List.of(
                field(m, 1, 1, "Engine Speed (sample)", "Range: 0...5000 /min", "Specification (Idle): 780...920 /min"),
                field(m, 1, 4, "Coolant Temperature (sample)", "Range: -40.0...+130.0 °C")));
        LiveDataCatalog.Group g7 = new LiveDataCatalog.Group(7, "Sample Temperatures", List.of(
                field(m, 7, 1, "Fuel Temperature (sample)"),
                field(m, 7, 2, "Oil Temperature (sample)")));
        return new LiveDataCatalog(m, LiveDataCatalog.Source.LABELS, Optional.of("03L906018XX"),
                Optional.of("03L-906-018.lbl"), List.of("03L-906-018.lbl"), List.of(g1, g7), List.of(81), 0,
                List.of(), Optional.empty(), 2, AT);
    }

    private static LiveDataCatalog.Field field(ModuleAddress m, int group, int field, String name, String... specs) {
        return new LiveDataCatalog.Field(new LiveValueId(m, group, field), name, "", "", List.of(specs));
    }

    /** One sample value per ID: engine speed 828 /min, coolant 87.3 °C, fuel 38.7 °C, oil empty, else not read. */
    public static LiveValue liveValue(LiveValueId id) {
        LiveDataCatalog.Field f = liveDataCatalog(id.module()).field(id)
                .orElse(new LiveDataCatalog.Field(id, "", "", "", List.of()));
        String key = String.format("%03d.%d", id.group(), id.field());
        return switch (key) {
            case "001.1" -> ok(f, "RPM", "/min", "828", "01 73 24");
            case "001.4" -> ok(f, "Temperature", "°C", "87.3", "05 09 C1");
            case "007.1" -> ok(f, "Temperature", "°C", "38.7", "05 09 8F");
            case "007.2" -> new LiveValue(id, f.name(), "", "", Optional.empty(), "(empty)", "25 00 00", f.specs(),
                    LiveValue.State.EMPTY, Optional.of("the module sent no value for this field (25 00 00)"), AT);
            default -> new LiveValue(id, f.name(), "", "", Optional.empty(), "(not read)", "", f.specs(),
                    LiveValue.State.NOT_READ, Optional.of("not in the sample"), AT);
        };
    }

    private static LiveValue ok(LiveDataCatalog.Field f, String quantity, String unit, String value, String raw) {
        return new LiveValue(f.id(), f.name(), quantity, unit, Optional.of(new java.math.BigDecimal(value)),
                value + " " + unit, raw, f.specs(), LiveValue.State.OK, Optional.empty(), AT);
    }

    public static LiveDataSnapshot liveDataSnapshot(List<LiveValueId> ids) {
        ModuleAddress m = ids.getFirst().module();
        List<LiveValue> values = ids.stream().map(SampleScanData::liveValue).toList();
        boolean all = values.stream().allMatch(v -> v.state() != LiveValue.State.NOT_READ);
        return new LiveDataSnapshot(m, all ? LiveDataSnapshot.Status.OK : LiveDataSnapshot.Status.PARTIAL,
                liveDataCatalog(m).labelSource(), values, List.of(), Optional.empty(), 2, AT, AT.plusMillis(150));
    }

    /** A 10-sample recording summary of each ID (engine speed 820..900 /min; the rest as {@link #liveValue}). */
    public static LiveDataRecording liveDataRecording(List<LiveValueId> ids, java.time.Duration duration) {
        ModuleAddress m = ids.getFirst().module();
        List<LiveSeries> series = new ArrayList<>();
        for (LiveValueId id : ids) {
            LiveValue v = liveValue(id);
            if (id.group() == 1 && id.field() == 1) {
                series.add(new LiveSeries(id, v.name(), "RPM", "/min", v.specs(), 10, 10,
                        Optional.of(new java.math.BigDecimal("820")), Optional.of(new java.math.BigDecimal("900")),
                        Optional.of(new java.math.BigDecimal("851.6")), Optional.of(new java.math.BigDecimal("828")),
                        Optional.of(new java.math.BigDecimal("900")), Optional.of(1.0), Optional.of(9.0), List.of()));
            } else {
                Optional<java.math.BigDecimal> x = v.value();
                series.add(new LiveSeries(id, v.name(), v.quantity(), v.unit(), v.specs(), 10, x.isPresent() ? 10 : 0,
                        x, x, x, x, x, x.map(y -> 0.0), x.map(y -> 0.0),
                        x.isPresent() ? List.of("constant at " + v.text()) : List.of("always empty: the module sent no value")));
            }
        }
        return new LiveDataRecording("record-live-data-1", m, LiveDataSnapshot.Status.OK, liveDataCatalog(m).labelSource(),
                series, 10, duration, duration, Optional.of(java.nio.file.Path.of("/tmp/macvagdiag/record-live-data-1.csv")),
                List.of(), Optional.empty(), 12, AT, AT.plus(duration));
    }
}
