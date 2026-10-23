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
        return new EcuInfo(Protocol.UDS, Optional.of("03L906018XX"), Optional.of("9971"), Optional.of("03L907309X"),
                Optional.of("SAMPLE R4 2.0L TDI"), Optional.of("0000172A0000"), Optional.of(VIN),
                List.of(new RawValue("22 F1 90", hexOf(VIN), Optional.of(VIN))), List.of());
    }

    public static FaultCode udsFault() {
        return new FaultCode("P0401", Protocol.UDS, "04 01 00 2F", 0x2F,
                "2F: testFailed, testFailedThisOperationCycle, pendingDTC, confirmedDTC, testFailedSinceLastClear",
                List.of("testFailed", "testFailedThisOperationCycle", "pendingDTC", "confirmedDTC",
                        "testFailedSinceLastClear"),
                Optional.empty(), OptionalInt.of(0),
                new FreezeFrameData(FreezeFrameData.Availability.READ, "read",
                        List.of(new RawValue("19 04 04 01 00 FF", "01 01 F4 0D 2A", Optional.empty())), List.of(),
                        List.of("59 04 04 01 00 2F 01 01 F4 0D 2A"), List.of()));
    }

    public static FaultCode kwpFault() {
        return new FaultCode("01314", Protocol.KWP2000, "05 22 23", 0x23,
                "23: symptom 35, static (KWP status layout UNCONFIRMED)", List.of(), Optional.empty(),
                OptionalInt.empty(), new FreezeFrameData(FreezeFrameData.Availability.NOT_AVAILABLE,
                        "KWP2000 freeze frames need a request that names the DTC; not on the allowlist", List.of(),
                        List.of(), List.of(), List.of()));
    }

    /** A plausible result for any known module: 01 with a UDS fault, 03 with a KWP fault, 08 not responding, else OK. */
    public static ModuleResult moduleResult(ModuleAddress m) {
        Instant end = AT.plusMillis(400);
        return switch (m.hex()) {
            case "01" -> new ModuleResult(m, ModuleStatus.FAULTS, Optional.of(m.links().getLast()),
                    Optional.of(engineInfo()), List.of(udsFault()), true, List.of(), Optional.empty(), 6, AT, end);
            case "03" -> new ModuleResult(m, ModuleStatus.FAULTS, Optional.of(m.links().getFirst()), Optional.empty(),
                    List.of(kwpFault()), true, List.of(), Optional.empty(), 4, AT, end);
            case "08", "16", "25", "44", "46", "56" -> new ModuleResult(m, ModuleStatus.NOT_RESPONDING,
                    Optional.empty(), Optional.empty(), List.of(), false, List.of("no response"), Optional.empty(), 0,
                    AT, end);
            default -> new ModuleResult(m, ModuleStatus.OK, Optional.of(m.links().getFirst()), Optional.empty(),
                    List.of(), true, List.of(), Optional.empty(), 2, AT, end);
        };
    }

    public static List<ModuleAvailability> moduleList() {
        List<ModuleAvailability> out = new ArrayList<>();
        for (ModuleAddress m : ModuleAddress.known()) {
            ModuleResult r = moduleResult(m);
            out.add(new ModuleAvailability(m, r.responded() ? ModuleAvailability.Reachability.RESPONDING
                    : ModuleAvailability.Reachability.NOT_RESPONDING, r.link(), r.responded() ? "responds" : "no response", AT));
        }
        return out;
    }

    public static VehicleInfo vehicleInfo() {
        return new VehicleInfo(Optional.of(VIN), Optional.of(ModuleAddress.ENGINE),
                List.of(new ModuleIdentity(ModuleAddress.ENGINE, ModuleAvailability.Reachability.RESPONDING,
                        Optional.of(ModuleAddress.ENGINE.links().getLast()), Optional.of(engineInfo()), List.of())),
                AT, false);
    }

    public static AutoScanResult autoScan() {
        List<ModuleResult> modules = ModuleAddress.known().stream().map(SampleScanData::moduleResult).toList();
        return new AutoScanResult("scan-sample", Caller.web(), AT, AT.plus(Duration.ofSeconds(6)), Optional.of(VIN),
                Optional.of(ModuleAddress.ENGINE), modules, false, false, List.of());
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
