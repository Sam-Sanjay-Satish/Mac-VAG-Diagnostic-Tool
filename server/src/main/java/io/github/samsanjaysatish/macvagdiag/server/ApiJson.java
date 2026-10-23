package io.github.samsanjaysatish.macvagdiag.server;

import io.github.samsanjaysatish.macvagdiag.scan.AutoScanJob;
import io.github.samsanjaysatish.macvagdiag.scan.AutoScanResult;
import io.github.samsanjaysatish.macvagdiag.scan.EcuInfo;
import io.github.samsanjaysatish.macvagdiag.scan.FaultCode;
import io.github.samsanjaysatish.macvagdiag.scan.FreezeFrameData;
import io.github.samsanjaysatish.macvagdiag.scan.LimitHit;
import io.github.samsanjaysatish.macvagdiag.scan.ModuleAddress;
import io.github.samsanjaysatish.macvagdiag.scan.ModuleAvailability;
import io.github.samsanjaysatish.macvagdiag.scan.ModuleLink;
import io.github.samsanjaysatish.macvagdiag.scan.ModuleResult;
import io.github.samsanjaysatish.macvagdiag.scan.RawValue;
import io.github.samsanjaysatish.macvagdiag.scan.ScanEvent;
import io.github.samsanjaysatish.macvagdiag.scan.ScanRefusedException;
import io.github.samsanjaysatish.macvagdiag.scan.SessionStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The REST API's JSON shapes, mapped from scan's model. The API never serializes scan types directly, so its shape
 * is fixed here (and pinned by the controller tests) whatever scan's records look like. Every field is always
 * present; values that don't apply are {@code null}. Times are ISO-8601 UTC strings, raw bytes bus-style hex.
 */
final class ApiJson {

    private ApiJson() {
    }

    // ---- shapes -------------------------------------------------------------------------------------------------

    /** {@code GET /api/status}. */
    record Status(String adapter, boolean adapterConnected, String adapterDetail, Condition ignition, Condition engine,
            boolean demo, String checkedAt) {
    }

    record Condition(String state, String reason) {
    }

    /** {@code GET /api/modules}. */
    record Modules(List<Availability> modules) {
    }

    record Module(String address, String name) {
    }

    record Availability(String address, String name, String reachability, Link link, String message,
            String checkedAt) {
    }

    /** {@code type}: {@code TP20} or {@code ISOTP}; {@code protocol}: {@code KWP2000} or {@code UDS}. */
    record Link(String type, String protocol, String description) {
    }

    /** {@code GET /api/modules/{address}/faults}, and each module of a scan. */
    record ModuleReport(String address, String name, String status, String statusText, boolean responded, Link link,
            Identification identification, List<Fault> faults, boolean faultListComplete, List<String> notes,
            Limited limitHit, int requests, String startedAt, String finishedAt) {
    }

    record Identification(String protocol, String partNumber, String softwareVersion, String hardwareNumber,
            String systemName, String coding, String vin, List<Raw> raw, List<String> notes) {
    }

    /** An undecoded value with the ID of the request it came from, e.g. {@code 22 F1 90}. */
    record Raw(String id, String hex, String text) {
    }

    /** {@code status} is the status byte as a number; {@code statusHex} the same byte as on the bus. */
    record Fault(String code, String displayCode, String protocol, String record, int status, String statusHex,
            String statusText, List<String> statusFlags, String obdCode, String failureType, FreezeFrame freezeFrame) {
    }

    record FreezeFrame(String availability, String message, List<Raw> records, List<Field> fields,
            List<String> responses, List<String> notes) {
    }

    record Field(String kind, String value, String sourceRecordId) {
    }

    record Limited(String limit, String description, String message, long retryAfterSeconds) {
    }

    /** {@code POST /api/scans} (202). */
    record Started(String id, String href, String reportHref) {
    }

    /**
     * {@code GET /api/scans/{id}}. {@code state}: {@code RUNNING}, {@code FINISHED}, {@code CANCELLED} or
     * {@code FAILED}. {@code modules}: the modules finished so far (all of them once done). {@code result}: the
     * whole-scan summary once finished. {@code failure}: why it failed.
     */
    record ScanJob(String id, String state, boolean demo, String caller, String startedAt, int totalModules,
            int completedModules, Module currentModule, String lastMessage, List<Event> events,
            List<ModuleReport> modules, Summary result, Error failure) {
    }

    record Event(String type, String message, String at) {
    }

    record Summary(String finishedAt, long durationMs, boolean cancelled, boolean demo, String vin, String vinSource,
            int modulesChecked, int modulesResponding, int modulesWithFaults, int faultCodes, List<String> notes) {
    }

    /**
     * Every error body. {@code error} is a fixed code ({@code ADAPTER_NOT_CONNECTED}, {@code LIMIT_EXCEEDED},
     * {@code INVALID_MODULE}, ...); {@code message} is readable and says what to do next.
     */
    record Error(String error, String message, String limit, Long retryAfterSeconds, String module) {
    }

    // ---- mapping ------------------------------------------------------------------------------------------------

    static Status status(SessionStatus s) {
        return new Status(s.adapter().name(), s.adapterConnected(), s.adapterDetail(), condition(s.ignition()),
                condition(s.engine()), s.demo(), time(s.checkedAt()));
    }

    private static Condition condition(SessionStatus.VehicleCondition c) {
        return new Condition(c.state().name(), c.reason());
    }

    static Modules modules(List<ModuleAvailability> list) {
        return new Modules(list.stream().map(a -> new Availability(a.module().hex(), a.module().name(),
                a.reachability().name(), a.link().map(ApiJson::link).orElse(null), a.message(),
                time(a.checkedAt()))).toList());
    }

    static Link link(ModuleLink l) {
        String type = switch (l) {
            case ModuleLink.Tp20 _ -> "TP20";
            case ModuleLink.IsoTp _ -> "ISOTP";
        };
        return new Link(type, l.protocol().name(), l.describe());
    }

    static ModuleReport module(ModuleResult r) {
        return new ModuleReport(r.module().hex(), r.module().name(), r.status().name(), statusText(r), r.responded(),
                r.link().map(ApiJson::link).orElse(null), r.identification().map(ApiJson::identification).orElse(null),
                r.faults().stream().map(ApiJson::fault).toList(), r.faultListComplete(), r.notes(),
                r.limitHit().map(ApiJson::limited).orElse(null), r.requests(), time(r.startedAt()),
                time(r.finishedAt()));
    }

    private static String statusText(ModuleResult r) {
        return switch (r.status()) {
            case FAULTS -> r.faults().size() + (r.faults().size() == 1 ? " fault code" : " fault codes");
            case NOT_RESPONDING -> "not reachable (no response: not installed, or not reachable)";
            default -> r.status().description();
        };
    }

    static Identification identification(EcuInfo e) {
        return new Identification(e.protocol().name(), e.partNumber().orElse(null), e.softwareVersion().orElse(null),
                e.hardwareNumber().orElse(null), e.systemName().orElse(null), e.coding().orElse(null),
                e.vin().orElse(null), raw(e.raw()), e.notes());
    }

    private static List<Raw> raw(List<RawValue> values) {
        return values.stream().map(v -> new Raw(v.id(), v.hex(), v.text().orElse(null))).toList();
    }

    static Fault fault(FaultCode f) {
        return new Fault(f.code(), f.displayCode(), f.protocol().name(), f.record(), f.status(),
                String.format("%02X", f.status()), f.statusText(), f.statusFlags(), f.obdCode().orElse(null),
                f.failureType().isPresent() ? String.format("%02X", f.failureType().getAsInt()) : null,
                freezeFrame(f.freezeFrame()));
    }

    private static FreezeFrame freezeFrame(FreezeFrameData d) {
        return new FreezeFrame(d.availability().name(), d.message(), raw(d.records()),
                d.fields().stream().map(x -> new Field(x.kind(), x.value(), x.sourceRecordId())).toList(),
                d.responses(), d.notes());
    }

    private static Limited limited(LimitHit h) {
        return new Limited(h.limit().name(), h.limit().description(), h.message(), seconds(h.retryAfter()));
    }

    static ScanJob job(AutoScanJob job, boolean demo) {
        Optional<AutoScanResult> result = job.result();
        Optional<RuntimeException> failure = job.failure();
        String state;
        if (!job.isDone()) {
            state = "RUNNING";
        } else if (failure.isPresent()) {
            state = "FAILED";
        } else if (result.map(AutoScanResult::cancelled).orElse(false)) {
            state = "CANCELLED";
        } else {
            state = "FINISHED";
        }
        List<ScanEvent> events = job.events();
        List<ModuleReport> modules = result.map(r -> r.modules().stream().map(ApiJson::module).toList())
                .orElseGet(() -> events.stream()
                        .flatMap(e -> e instanceof ScanEvent.ModuleFinished f ? java.util.stream.Stream.of(f)
                                : java.util.stream.Stream.empty())
                        .map(f -> module(f.result())).toList());
        return new ScanJob(job.id(), state, result.map(AutoScanResult::demo).orElse(demo), job.caller().label(),
                time(job.startedAt()), job.totalModules(), job.completedModules(),
                job.currentModule().map(m -> new Module(m.hex(), m.name())).orElse(null),
                job.lastEvent().map(ScanEvent::message).orElse(null),
                events.stream().map(ApiJson::event).toList(), modules, result.map(ApiJson::summary).orElse(null),
                failure.map(ApiJson::error).orElse(null));
    }

    private static Event event(ScanEvent e) {
        return new Event(e.getClass().getSimpleName(), e.message(), time(e.at()));
    }

    private static Summary summary(AutoScanResult r) {
        return new Summary(time(r.finishedAt()), Duration.between(r.startedAt(), r.finishedAt()).toMillis(),
                r.cancelled(), r.demo(), r.vin().orElse(null), r.vinSource().map(ModuleAddress::hex).orElse(null),
                r.modules().size(), r.respondingModules().size(),
                (int) r.modules().stream().filter(m -> !m.faults().isEmpty()).count(), r.faultCount(), r.notes());
    }

    /** A failed scan's cause; a refusal keeps its reason and retry time, anything else is reported generically. */
    static Error error(RuntimeException e) {
        if (e instanceof ScanRefusedException refused) {
            return error(refused);
        }
        return new Error("SCAN_FAILED", "the Auto-Scan failed: " + e.getMessage(), null, null, null);
    }

    static Error error(ScanRefusedException e) {
        return new Error(e.reason().name(), e.getMessage(), e.limit().map(Enum::name).orElse(null),
                e.retryAfter().map(ApiJson::seconds).orElse(null), e.module().map(ModuleAddress::hex).orElse(null));
    }

    /** Whole seconds, rounded up, as {@code Retry-After} wants them. */
    static long seconds(Duration d) {
        return d.toSeconds() + (d.toNanosPart() > 0 ? 1 : 0);
    }

    private static String time(Instant t) {
        return t == null ? null : t.toString();
    }
}
