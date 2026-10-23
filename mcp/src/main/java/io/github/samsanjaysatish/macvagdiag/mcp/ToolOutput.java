package io.github.samsanjaysatish.macvagdiag.mcp;

import io.github.samsanjaysatish.macvagdiag.scan.EcuInfo;
import io.github.samsanjaysatish.macvagdiag.scan.FaultCode;
import io.github.samsanjaysatish.macvagdiag.scan.FreezeFrameData;
import io.github.samsanjaysatish.macvagdiag.scan.LimitHit;
import io.github.samsanjaysatish.macvagdiag.scan.ModuleAddress;
import io.github.samsanjaysatish.macvagdiag.scan.ModuleAvailability;
import io.github.samsanjaysatish.macvagdiag.scan.ModuleIdentity;
import io.github.samsanjaysatish.macvagdiag.scan.ModuleLink;
import io.github.samsanjaysatish.macvagdiag.scan.ModuleResult;
import io.github.samsanjaysatish.macvagdiag.scan.RawValue;
import io.github.samsanjaysatish.macvagdiag.scan.SessionStatus;
import io.github.samsanjaysatish.macvagdiag.scan.VehicleInfo;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns scan's result records into the JSON the tools return ({@link ToolSchemas} has the matching output
 * schemas). Plain maps and lists, so the SDK's mapper writes them as they are, nulls included.
 *
 * <p>Output rules ({@code mcp/CLAUDE.md}):
 * <ul>
 *   <li>Every value read from the car is an object with an {@code id}, a {@code unit} ({@code null}: unknown or
 *       none) and a {@code timestamp}: a <b>value</b> {@code {id, value, unit, timestamp}}, a <b>byte</b>
 *       {@code {id, raw, value, unit, timestamp}} or a <b>raw</b> record {@code {id, raw, text, unit, timestamp}}.
 *       Never a bare number.</li>
 *   <li>Undecoded bytes keep the ID of the request they came from, e.g. {@code {"id": "22 F1 90", "raw": "57 56 ..."}}.</li>
 *   <li>Fault codes carry no labels or descriptions (out of scope).</li>
 *   <li>VIN masking happens before this, on the scan records ({@code maskVin()}).</li>
 * </ul>
 */
final class ToolOutput {

    static final String NO_UNIT = null;

    private ToolOutput() {
    }

    // ------------------------------------------------------------------ get_session_status

    static Map<String, Object> sessionStatus(SessionStatus s) {
        Instant at = s.checkedAt();
        Map<String, Object> out = obj();
        out.put("adapter", value("adapter", s.adapter().name(), at).detail(s.adapterDetail()).map);
        out.put("ignition", value("ignition", s.ignition().state().name(), at).detail(s.ignition().reason()).map);
        out.put("engine", value("engine", s.engine().state().name(), at).detail(s.engine().reason()).map);
        out.put("demo", s.demo());
        out.put("timestamp", ts(at));
        out.put("hint", s.adapterConnected()
                ? "Adapter ready. Ignition and engine state can't be read yet (UNKNOWN): if modules don't respond, "
                        + "ask the user whether the ignition is on."
                : "The adapter isn't connected: ask the user to plug the CANable into the Mac and the car's OBD port "
                        + "and switch the ignition on, then call get_session_status again.");
        return out;
    }

    // ------------------------------------------------------------------ list_modules

    static Map<String, Object> moduleList(List<ModuleAvailability> modules, boolean demo) {
        int responding = 0;
        int notResponding = 0;
        int notChecked = 0;
        List<Object> list = new ArrayList<>();
        for (ModuleAvailability m : modules) {
            switch (m.reachability()) {
                case RESPONDING -> responding++;
                case NOT_RESPONDING -> notResponding++;
                case NOT_CHECKED -> notChecked++;
            }
            Map<String, Object> e = module(m.module());
            e.put("reachability", m.reachability().name());
            link(e, m.link());
            e.put("message", m.message());
            e.put("timestamp", ts(m.checkedAt()));
            list.add(e);
        }
        Map<String, Object> counts = obj();
        counts.put("responding", responding);
        counts.put("notResponding", notResponding);
        counts.put("notChecked", notChecked);
        Map<String, Object> out = obj();
        out.put("demo", demo);
        out.put("modules", list);
        out.put("counts", counts);
        out.put("note", "Without the gateway's installation list, NOT_RESPONDING can mean not installed or not "
                + "reachable (ignition off). NOT_CHECKED means a limit stopped the check; see its message.");
        return out;
    }

    // ------------------------------------------------------------------ read_fault_codes

    static Map<String, Object> faultCodes(ModuleResult r, boolean demo, boolean vinMasked) {
        Instant at = r.finishedAt();
        Map<String, Object> out = obj();
        out.put("demo", demo);
        out.put("vinMasked", vinMasked);
        out.put("module", module(r.module()));
        out.put("status", r.status().name());
        out.put("statusDescription", r.status().description());
        link(out, r.link());
        out.put("faultCount", r.faults().size());
        out.put("faultListComplete", r.faultListComplete());
        List<Object> faults = new ArrayList<>();
        for (FaultCode f : r.faults()) {
            faults.add(fault(f, at));
        }
        out.put("faults", faults);
        out.put("identification", r.identification().map(e -> identification(e, at)).orElse(null));
        out.put("notes", List.copyOf(r.notes()));
        out.put("limitHit", r.limitHit().map(ToolOutput::limitHit).orElse(null));
        out.put("requests", r.requests());
        out.put("startedAt", ts(r.startedAt()));
        out.put("timestamp", ts(at));
        return out;
    }

    private static Map<String, Object> fault(FaultCode f, Instant at) {
        Map<String, Object> out = obj();
        out.put("id", f.displayCode());
        out.put("code", f.code());
        out.put("protocol", f.protocol().name());
        out.put("record", f.record());
        out.put("status", byteValue("status byte", f.status(), at));
        out.put("statusText", f.statusText());
        out.put("statusFlags", List.copyOf(f.statusFlags()));
        out.put("failureType", f.failureType().isPresent() ? byteValue("failure type", f.failureType().getAsInt(), at)
                : null);
        out.put("obdCode", f.obdCode().orElse(null));
        out.put("freezeFrame", freezeFrame(f.freezeFrame(), at));
        out.put("unit", NO_UNIT);
        out.put("timestamp", ts(at));
        return out;
    }

    private static Map<String, Object> freezeFrame(FreezeFrameData ff, Instant at) {
        Map<String, Object> out = obj();
        out.put("availability", ff.availability().name());
        out.put("message", ff.message());
        out.put("records", raws(ff.records(), at));
        List<Object> fields = new ArrayList<>();
        for (FreezeFrameData.Field field : ff.fields()) {
            fields.add(value(field.kind(), field.value(), at).source(field.sourceRecordId()).map);
        }
        out.put("fields", fields);
        out.put("responses", List.copyOf(ff.responses()));
        out.put("notes", List.copyOf(ff.notes()));
        return out;
    }

    private static Map<String, Object> limitHit(LimitHit hit) {
        Map<String, Object> out = obj();
        out.put("limit", hit.limit().name());
        out.put("description", hit.limit().description());
        out.put("retryAfterSeconds", ceilSeconds(hit.retryAfter()));
        out.put("message", hit.message());
        return out;
    }

    // ------------------------------------------------------------------ get_vehicle_info

    static Map<String, Object> vehicleInfo(VehicleInfo v, boolean vinMasked) {
        Instant at = v.readAt();
        Map<String, Object> out = obj();
        out.put("demo", v.demo());
        out.put("vinMasked", vinMasked);
        out.put("vin", value("vin", v.vin().orElse(null), at).source(v.vinSource().map(ModuleAddress::hex).orElse(null))
                .map);
        List<Object> modules = new ArrayList<>();
        for (ModuleIdentity m : v.modules()) {
            Map<String, Object> e = module(m.module());
            e.put("reachability", m.reachability().name());
            link(e, m.link());
            e.put("identification", m.identification().map(i -> identification(i, at)).orElse(null));
            e.put("notes", List.copyOf(m.notes()));
            e.put("timestamp", ts(at));
            modules.add(e);
        }
        out.put("modules", modules);
        out.put("timestamp", ts(at));
        return out;
    }

    // ------------------------------------------------------------------ shared shapes

    private static Map<String, Object> identification(EcuInfo e, Instant at) {
        List<Object> values = new ArrayList<>();
        addIfPresent(values, "partNumber", e.partNumber(), at);
        addIfPresent(values, "softwareVersion", e.softwareVersion(), at);
        addIfPresent(values, "hardwareNumber", e.hardwareNumber(), at);
        addIfPresent(values, "systemName", e.systemName(), at);
        addIfPresent(values, "coding", e.coding(), at);
        addIfPresent(values, "vin", e.vin(), at);
        Map<String, Object> out = obj();
        out.put("protocol", e.protocol().name());
        out.put("values", values);
        out.put("raw", raws(e.raw(), at));
        out.put("notes", List.copyOf(e.notes()));
        return out;
    }

    private static void addIfPresent(List<Object> values, String id, Optional<String> v, Instant at) {
        v.ifPresent(s -> values.add(value(id, s, at).map));
    }

    private static Map<String, Object> module(ModuleAddress m) {
        Map<String, Object> out = obj();
        out.put("address", m.hex());
        out.put("name", m.name());
        return out;
    }

    private static void link(Map<String, Object> out, Optional<ModuleLink> link) {
        out.put("protocol", link.map(l -> l.protocol().name()).orElse(null));
        out.put("link", link.map(ModuleLink::describe).orElse(null));
    }

    private static List<Object> raws(List<RawValue> raws, Instant at) {
        List<Object> out = new ArrayList<>();
        for (RawValue r : raws) {
            Map<String, Object> m = obj();
            m.put("id", r.id());
            m.put("raw", r.hex());
            m.put("text", r.text().orElse(null));
            m.put("unit", NO_UNIT);
            m.put("timestamp", ts(at));
            out.add(m);
        }
        return out;
    }

    /** A byte from the car: its hex as on the bus, and its value. */
    private static Map<String, Object> byteValue(String id, int b, Instant at) {
        Map<String, Object> out = obj();
        out.put("id", id);
        out.put("raw", String.format("%02X", b));
        out.put("value", b);
        out.put("unit", NO_UNIT);
        out.put("timestamp", ts(at));
        return out;
    }

    private static Value value(String id, Object v, Instant at) {
        Value out = new Value(obj());
        out.map.put("id", id);
        out.map.put("value", v);
        out.map.put("unit", NO_UNIT);
        out.map.put("timestamp", ts(at));
        return out;
    }

    /** A value object with optional extras. */
    private record Value(Map<String, Object> map) {
        Value detail(String detail) {
            map.put("detail", detail);
            return this;
        }

        Value source(String source) {
            map.put("source", source);
            return this;
        }
    }

    static long ceilSeconds(Duration d) {
        return d.toSeconds() + (d.toNanosPart() > 0 ? 1 : 0);
    }

    private static String ts(Instant at) {
        return at.toString();
    }

    private static Map<String, Object> obj() {
        return new LinkedHashMap<>();
    }
}
