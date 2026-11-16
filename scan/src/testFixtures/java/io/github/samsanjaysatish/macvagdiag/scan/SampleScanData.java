package io.github.samsanjaysatish.macvagdiag.scan;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Canned scan results for front-end tests (JSON shapes, report snapshots). <b>Sample data, not a real car</b>:
 * the VIN is {@value #VIN}. Every value is fixed, so snapshots are stable.
 */
public final class SampleScanData {

    public static final String VIN = "SAMPLEVIN00000000";
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
                Optional.of("SAMPLE R4 2.0L TDI"), Optional.of("0001234"), Optional.of(VIN),
                List.of(new RawValue("1A 90", hexOf(VIN), Optional.of(VIN))), List.of());
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
                    List.of(), Optional.empty(), 4, AT, end);
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
        return new VehicleInfo(Optional.of(VIN), Optional.of(ModuleAddress.ENGINE),
                List.of(new ModuleIdentity(ModuleAddress.ENGINE, ModuleAvailability.Reachability.RESPONDING,
                        Optional.of(ModuleAddress.ENGINE.links().getFirst()), Optional.of(engineInfo()), List.of())),
                AT, false);
    }

    public static AutoScanResult autoScan() {
        List<ModuleResult> modules = ModuleAddress.known().stream().map(SampleScanData::moduleResult).toList();
        List<GatewayStatus> list = ModuleAddress.known().stream().map(SampleScanData::gatewayStatus).toList();
        return new AutoScanResult("scan-sample", Caller.web(), AT, AT.plus(Duration.ofSeconds(6)), Optional.of(VIN),
                Optional.of(ModuleAddress.ENGINE), modules, false, false,
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
}
