package io.github.samsanjaysatish.macvagdiag.scan;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * VIN masking for reports and AI output. The model types carry the VIN raw; masking is a presentation option.
 * The first {@value #VISIBLE_CHARACTERS} characters (manufacturer, model, year, plant) stay visible; the last six,
 * the car's serial number, become {@code *}. Raw records and notes that contain a known VIN, as text or as hex,
 * are masked too.
 */
public final class VinMask {

    /** Characters kept: WMI (3) + VDS (6) + model year + plant. The serial number is masked. */
    public static final int VISIBLE_CHARACTERS = 11;
    static final String MASKED_HEX = "(masked: contains the VIN)";

    private VinMask() {
    }

    /** E.g. {@code WVWZZZ1KZDM123456} → {@code WVWZZZ1KZDM******}. */
    public static String mask(String vin) {
        if (vin.length() <= VISIBLE_CHARACTERS) {
            return "*".repeat(vin.length());
        }
        return vin.substring(0, VISIBLE_CHARACTERS) + "*".repeat(vin.length() - VISIBLE_CHARACTERS);
    }

    static Set<String> vinsOf(Collection<ModuleResult> modules) {
        Set<String> vins = new LinkedHashSet<>();
        for (ModuleResult m : modules) {
            m.identification().flatMap(EcuInfo::vin).ifPresent(vins::add);
        }
        return vins;
    }

    static AutoScanResult mask(AutoScanResult r) {
        Set<String> vins = vinsOf(r.modules());
        r.vin().ifPresent(vins::add);
        List<ModuleResult> modules = r.modules().stream().map(m -> mask(m, vins)).toList();
        return new AutoScanResult(r.scanId(), r.caller(), r.startedAt(), r.finishedAt(), r.vin().map(VinMask::mask),
                r.vinSource(), modules, r.cancelled(), r.demo(), maskAll(r.notes(), vins));
    }

    static VehicleInfo mask(VehicleInfo v) {
        Set<String> vins = new LinkedHashSet<>();
        v.vin().ifPresent(vins::add);
        v.modules().forEach(m -> m.identification().flatMap(EcuInfo::vin).ifPresent(vins::add));
        List<ModuleIdentity> modules = v.modules().stream()
                .map(m -> new ModuleIdentity(m.module(), m.reachability(), m.link(),
                        m.identification().map(e -> mask(e, vins)), maskAll(m.notes(), vins)))
                .toList();
        return new VehicleInfo(v.vin().map(VinMask::mask), v.vinSource(), modules, v.readAt(), v.demo());
    }

    static ModuleResult mask(ModuleResult m, Set<String> vins) {
        if (vins.isEmpty()) {
            return m;
        }
        List<FaultCode> faults = m.faults().stream().map(f -> new FaultCode(f.code(), f.protocol(), f.record(),
                f.status(), f.statusText(), f.statusFlags(), f.obdCode(), f.failureType(),
                mask(f.freezeFrame(), vins))).toList();
        return new ModuleResult(m.module(), m.status(), m.link(), m.identification().map(e -> mask(e, vins)), faults,
                m.faultListComplete(), maskAll(m.notes(), vins), m.limitHit(), m.requests(), m.startedAt(),
                m.finishedAt());
    }

    static EcuInfo mask(EcuInfo e, Set<String> vins) {
        return new EcuInfo(e.protocol(), e.partNumber().map(t -> maskText(t, vins)),
                e.softwareVersion().map(t -> maskText(t, vins)), e.hardwareNumber().map(t -> maskText(t, vins)),
                e.systemName().map(t -> maskText(t, vins)), e.coding(), e.vin().map(VinMask::mask),
                e.raw().stream().map(r -> mask(r, vins)).toList(), maskAll(e.notes(), vins));
    }

    static FreezeFrameData mask(FreezeFrameData f, Set<String> vins) {
        List<String> responses = new ArrayList<>();
        for (String hex : f.responses()) {
            responses.add(containsVinHex(hex, vins) ? MASKED_HEX : hex);
        }
        return new FreezeFrameData(f.availability(), maskText(f.message(), vins),
                f.records().stream().map(r -> mask(r, vins)).toList(), f.fields(), responses, maskAll(f.notes(), vins));
    }

    static RawValue mask(RawValue r, Set<String> vins) {
        boolean inText = r.text().map(t -> vins.stream().anyMatch(t::contains)).orElse(false);
        if (!inText && !containsVinHex(r.hex(), vins)) {
            return r;
        }
        return new RawValue(r.id(), MASKED_HEX, r.text().map(t -> maskText(t, vins)).or(() -> Optional.of("(VIN)")));
    }

    static String maskText(String text, Set<String> vins) {
        String out = text;
        for (String vin : vins) {
            out = out.replace(vin, mask(vin));
        }
        return out;
    }

    private static List<String> maskAll(List<String> texts, Set<String> vins) {
        return texts.stream().map(t -> maskText(t, vins)).toList();
    }

    private static boolean containsVinHex(String hex, Set<String> vins) {
        for (String vin : vins) {
            StringBuilder b = new StringBuilder();
            for (char c : vin.toCharArray()) {
                if (!b.isEmpty()) {
                    b.append(' ');
                }
                b.append(String.format("%02X", (int) c & 0xFF));
            }
            if (hex.toUpperCase(java.util.Locale.ROOT).contains(b)) {
                return true;
            }
        }
        return false;
    }
}
