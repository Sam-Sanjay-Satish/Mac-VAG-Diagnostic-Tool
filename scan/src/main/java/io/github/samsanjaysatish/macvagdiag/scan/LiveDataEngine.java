package io.github.samsanjaysatish.macvagdiag.scan;

import io.github.samsanjaysatish.macvagdiag.diag.DiagResult;
import io.github.samsanjaysatish.macvagdiag.diag.DiagnosticService;
import io.github.samsanjaysatish.macvagdiag.diag.EcuIdentification;
import io.github.samsanjaysatish.macvagdiag.diag.MeasuringBlock;
import io.github.samsanjaysatish.macvagdiag.diag.MeasuringValue;
import io.github.samsanjaysatish.macvagdiag.labels.FieldLabel;
import io.github.samsanjaysatish.macvagdiag.labels.GroupLabel;
import io.github.samsanjaysatish.macvagdiag.labels.LabelLibrary;
import io.github.samsanjaysatish.macvagdiag.labels.LabelLookup;
import io.github.samsanjaysatish.macvagdiag.labels.MeasuringLabels;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Live data (v2): KWP measuring blocks {@code 21 gg}, named from the label files. The catalog, one-off reads and
 * recordings. Runs on the scan worker thread (through {@link RequestQueue}), with {@link ScanEngine}'s sessions,
 * pacing, keep-alive, budgets and audit, like every other read.
 *
 * <ul>
 *   <li><b>Catalog:</b> opens the module, reads its identification (part number), looks up its label file and
 *       offers the labelled groups. Without labels it asks the groups one by one, {@link #PROBE_GROUPS_PER_CALL}
 *       per call (the normal per-module budget), and remembers what answered until every group was asked.</li>
 *   <li><b>Reads and recordings</b> only accept IDs from the module's last catalog (checked before anything is
 *       sent), and re-read the identification first: a different part number (another car) drops the catalog and
 *       reads nothing else.</li>
 *   <li><b>Recordings</b> wait {@link #RECORDING_REQUEST_INTERVAL} after each answer before the next group
 *       request, so fewer than {@code 240} requests a minute, under the 400 a minute for everything; their own per-call budget is one
 *       request per interval of the duration plus {@link #RECORDING_OVERHEAD}, at most
 *       {@link #MAX_RECORDING_REQUESTS}.</li>
 *   <li><b>Privacy:</b> withheld label groups are never offered; a response that looks like a VIN
 *       ({@link VinShape}) is dropped.</li>
 * </ul>
 */
final class LiveDataEngine {

    private static final Logger log = LoggerFactory.getLogger(LiveDataEngine.class);

    /** Groups asked per catalog call without labels: fits the default 60-request per-module budget. */
    static final int PROBE_GROUPS_PER_CALL = 40;
    /** Values and distinct groups per read or recording. */
    static final int MAX_VALUES = 16;
    static final int MAX_GROUPS = 4;
    static final Duration MIN_RECORDING = Duration.ofSeconds(1);
    static final Duration MAX_RECORDING = Duration.ofSeconds(120);
    /**
     * At least 250 ms from one answer to the next {@code 21 gg} in a recording (under 240 a minute). VCDS polled one group about every
     * 140 ms on the 2026-11-18 capture; slower is safe and leaves room in the 400-a-minute window for other callers.
     */
    static final Duration RECORDING_REQUEST_INTERVAL = Duration.ofMillis(250);
    /** Session start + identification (up to 6 on TP 2.0) + a few retries. */
    static final int RECORDING_OVERHEAD = 10;
    static final int MAX_RECORDING_REQUESTS =
            (int) (MAX_RECORDING.toMillis() / RECORDING_REQUEST_INTERVAL.toMillis()) + RECORDING_OVERHEAD;

    /** What is known about a module's live data; worker thread only. */
    private static final class Known {
        final String partNumber;
        final LabelLookup lookup;
        final TreeMap<Integer, LiveDataCatalog.Group> probed = new TreeMap<>();
        final TreeSet<Integer> probedWithheld = new TreeSet<>();
        int probedThrough;

        Known(String partNumber, LabelLookup lookup) {
            this.partNumber = partNumber;
            this.lookup = lookup;
        }

        Optional<MeasuringLabels> labels() {
            return lookup.labels().filter(l -> !l.groups().isEmpty() || !l.withheldGroups().isEmpty());
        }
    }

    private final ScanEngine engine;
    private final LabelLibrary labels;
    private final Map<ModuleAddress, Known> known = new ConcurrentHashMap<>();
    /** The last catalog per module, for checking IDs on the caller's thread before anything is queued. */
    private final Map<ModuleAddress, LiveDataCatalog> catalogs = new ConcurrentHashMap<>();

    LiveDataEngine(ScanEngine engine, LabelLibrary labels) {
        this.engine = engine;
        this.labels = labels;
    }

    /** True if the module speaks KWP2000 over TP 2.0, the only kind with measuring blocks here. */
    static boolean supported(ModuleAddress m) {
        return m.hasLink() && m.links().stream().allMatch(l -> l instanceof ModuleLink.Tp20);
    }

    Optional<LiveDataCatalog> cachedCatalog(ModuleAddress m) {
        return Optional.ofNullable(catalogs.get(m));
    }

    // ------------------------------------------------------------------ validation (caller's thread)

    /**
     * Checks a read or recording request against the module's catalog; nothing is sent.
     *
     * @return the module and the distinct groups, in first-seen order
     * @throws InvalidLiveValueException with a message for the caller
     */
    Request validate(List<LiveValueId> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new InvalidLiveValueException("no values given: pass 1 to " + MAX_VALUES + " value IDs like "
                    + "01:007.3 from the module's catalog");
        }
        if (ids.size() > MAX_VALUES) {
            throw new InvalidLiveValueException("too many values: at most " + MAX_VALUES + " per call ("
                    + ids.size() + " given)");
        }
        ModuleAddress module = ids.getFirst().module();
        LinkedHashSet<LiveValueId> distinct = new LinkedHashSet<>();
        for (LiveValueId id : ids) {
            if (!id.module().equals(module)) {
                throw new InvalidLiveValueException("all values must be from one module: " + id + " is not from "
                        + module.hex());
            }
            if (!distinct.add(id)) {
                throw new InvalidLiveValueException("value " + id + " is listed twice");
            }
        }
        if (!supported(module)) {
            throw new InvalidLiveValueException("module " + module.hex() + " (" + module.name() + ") has no "
                    + "measuring blocks we can read (only KWP2000 modules on TP 2.0 have them)");
        }
        LiveDataCatalog catalog = catalogs.get(module);
        if (catalog == null) {
            throw new InvalidLiveValueException("no catalog for module " + module.hex() + " yet: list its available "
                    + "data first, then read values from that list");
        }
        for (LiveValueId id : distinct) {
            if (catalog.field(id).isEmpty()) {
                if (catalog.withheldGroups().contains(id.group())) {
                    throw new InvalidLiveValueException(String.format("group %03d of module %s is withheld for "
                            + "privacy (it holds identity data such as the VIN) and is never read", id.group(),
                            module.hex()));
                }
                throw new InvalidLiveValueException("value " + id + " is not in module " + module.hex()
                        + "'s catalog; use an ID from its list of available data");
            }
        }
        List<Integer> groups = distinct.stream().map(LiveValueId::group).distinct().toList();
        if (groups.size() > MAX_GROUPS) {
            throw new InvalidLiveValueException("too many groups: at most " + MAX_GROUPS + " groups per call ("
                    + groups.size() + " given: " + groups.stream().map(g -> String.format("%03d", g)).toList() + ")");
        }
        return new Request(module, List.copyOf(distinct), groups, catalog);
    }

    /** A checked request. */
    record Request(ModuleAddress module, List<LiveValueId> ids, List<Integer> groups, LiveDataCatalog catalog) {
    }

    /** Throws unless {@code duration} is within the recording bounds. */
    static void validateDuration(Duration duration) {
        if (duration == null || duration.compareTo(MIN_RECORDING) < 0 || duration.compareTo(MAX_RECORDING) > 0) {
            throw new InvalidLiveValueException("recording duration must be " + MIN_RECORDING.toSeconds() + " to "
                    + MAX_RECORDING.toSeconds() + " seconds" + (duration == null ? "" : " (" + duration.toSeconds()
                    + " s given)"));
        }
    }

    /** The per-call budget of a recording of {@code duration}. */
    static int recordingBudget(Duration duration) {
        long slots = (duration.toMillis() + RECORDING_REQUEST_INTERVAL.toMillis() - 1) / RECORDING_REQUEST_INTERVAL.toMillis();
        return (int) Math.min(MAX_RECORDING_REQUESTS, slots + RECORDING_OVERHEAD);
    }

    // ------------------------------------------------------------------ catalog (worker thread)

    LiveDataCatalog catalog(ScanContext ctx, ModuleAddress module) throws InterruptedException {
        Instant now = engine.time().now();
        List<String> notes = new ArrayList<>();
        if (!supported(module)) {
            String why = !module.hasLink() ? module.hex() + " " + ScanEngine.NO_LINK
                    : "module " + module.hex() + " speaks UDS: its live values are data identifiers (DIDs), which "
                    + "aren't readable here yet; only KWP2000 modules on TP 2.0 have measuring blocks";
            return new LiveDataCatalog(module, LiveDataCatalog.Source.NOT_SUPPORTED, Optional.empty(), Optional.empty(),
                    List.of(), List.of(), List.of(), 0, List.of(why), Optional.empty(), 0, now);
        }
        ScanEngine.Opened opened;
        try {
            opened = engine.open(ctx, module);
        } catch (LimitReachedException e) {
            return notResponding(module, LiveDataCatalog.Source.NOT_RESPONDING, List.of(e.getMessage()),
                    Optional.of(e.hit()), ctx.requests(module));
        }
        if (opened.session() == null) {
            String why = opened.failures().isEmpty() ? "no response"
                    : "no response (" + String.join("; ", new LinkedHashSet<>(opened.failures())) + ")";
            engine.health().markNotResponding(module, why);
            return notResponding(module, LiveDataCatalog.Source.NOT_RESPONDING, List.of(why), Optional.empty(),
                    ctx.requests(module));
        }
        ModuleSession s = opened.session();
        Optional<LimitHit> limitHit = Optional.empty();
        Known k = null;
        try {
            k = knownFor(s, module, notes);
            if (k.labels().isEmpty()) {
                probe(ctx, s, module, k, notes);
            }
        } catch (LimitReachedException e) {
            limitHit = Optional.of(e.hit());
            notes.add(e.getMessage());
        } catch (UncheckedIOException | ScanRefusedException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("Error while building the live-data catalog of {}", module, e);
            notes.add("internal error: " + e);
        } finally {
            s.close();
        }
        if (s.abandoned()) {
            engine.health().markNotResponding(module, s.abandonReason());
            notes.add("module " + s.abandonReason());
        }
        if (k == null) {
            return notResponding(module, LiveDataCatalog.Source.NOT_RESPONDING, notes, limitHit, ctx.requests(module));
        }
        LiveDataCatalog c = build(module, k, notes, limitHit, ctx.requests(module));
        catalogs.put(module, c);
        return c;
    }

    private LiveDataCatalog notResponding(ModuleAddress module, LiveDataCatalog.Source source, List<String> notes,
            Optional<LimitHit> hit, int requests) {
        return new LiveDataCatalog(module, source, Optional.empty(), Optional.empty(), List.of(), List.of(),
                List.of(), 0, notes, hit, requests, engine.time().now());
    }

    /** Reads the identification and returns what is known for that part number (a new entry if it changed). */
    private Known knownFor(ModuleSession s, ModuleAddress module, List<String> notes)
            throws InterruptedException, LimitReachedException {
        Identity id = identity(s, notes);
        Known k = known.get(module);
        if (k == null || !k.partNumber.equals(id.partNumber())) {
            if (k != null) {
                notes.add("part number changed from " + k.partNumber + " to " + id.partNumber()
                        + ": the catalog was rebuilt");
            }
            LabelLookup lookup = id.partNumber().isEmpty() ? LabelLookup.none(List.of("no part number read"))
                    : labels.lookup(Integer.parseInt(module.hex(), 16), id.partNumber(), id.hardwareNumber());
            k = new Known(id.partNumber(), lookup);
            known.put(module, k);
        }
        return k;
    }

    private record Identity(String partNumber, Optional<String> hardwareNumber) {
    }

    private Identity identity(ModuleSession s, List<String> notes) throws InterruptedException, LimitReachedException {
        DiagResult<EcuIdentification> r = s.call(DiagOp.READ_IDENTIFICATION, DiagnosticService::readIdentification);
        if (r instanceof DiagResult.Success<EcuIdentification> ok) {
            EcuInfo info = DiagMapper.ecuInfo(ok.value());
            return new Identity(info.partNumber().map(String::strip).orElse(""),
                    info.hardwareNumber().map(String::strip));
        }
        notes.add("identification not read (" + r.message() + "): no labels can be looked up");
        return new Identity("", Optional.empty());
    }

    /** Asks the next {@link #PROBE_GROUPS_PER_CALL} groups that weren't asked yet. */
    private void probe(ScanContext ctx, ModuleSession s, ModuleAddress module, Known k, List<String> notes)
            throws InterruptedException, LimitReachedException {
        int from = k.probedThrough + 1;
        int to = Math.min(LiveValueId.MAX_GROUP, k.probedThrough + PROBE_GROUPS_PER_CALL);
        for (int g = from; g <= to; g++) {
            if (ctx.cancelled() || s.abandoned()) {
                return;
            }
            int group = g;
            DiagResult<MeasuringBlock> r = s.call(DiagOp.READ_MEASURING_BLOCK, d -> d.readMeasuringBlock(group));
            k.probedThrough = group;
            if (r instanceof DiagResult.Success<MeasuringBlock> ok) {
                MeasuringBlock b = ok.value();
                if (VinShape.contains(b.response().bytes())) {
                    k.probedWithheld.add(group);
                    continue;
                }
                List<LiveDataCatalog.Field> fields = new ArrayList<>();
                for (MeasuringValue v : b.fields()) {
                    if (!v.empty()) {
                        fields.add(new LiveDataCatalog.Field(new LiveValueId(module, group, v.field()), "",
                                v.quantity(), v.unit(), List.of()));
                    }
                }
                if (!fields.isEmpty()) {
                    k.probed.put(group, new LiveDataCatalog.Group(group, "", fields));
                }
            } else if (!(r instanceof DiagResult.Negative<MeasuringBlock>)) {
                notes.add(String.format("group %03d: %s", group, r.message()));
            }
        }
    }

    private LiveDataCatalog build(ModuleAddress module, Known k, List<String> notes, Optional<LimitHit> hit,
            int requests) {
        Optional<MeasuringLabels> l = k.labels();
        Optional<String> part = k.partNumber.isEmpty() ? Optional.empty() : Optional.of(k.partNumber);
        Instant now = engine.time().now();
        if (l.isPresent()) {
            List<LiveDataCatalog.Group> groups = new ArrayList<>();
            for (GroupLabel g : l.get().groups().values()) {
                List<LiveDataCatalog.Field> fields = new ArrayList<>();
                if (g.fields().isEmpty()) {
                    for (int f = 1; f <= LiveValueId.MAX_FIELD; f++) {
                        fields.add(new LiveDataCatalog.Field(new LiveValueId(module, g.group(), f), "", "", "", List.of()));
                    }
                } else {
                    for (FieldLabel f : g.fields()) {
                        fields.add(new LiveDataCatalog.Field(new LiveValueId(module, g.group(), f.field()),
                                f.displayName(), "", "", f.specs()));
                    }
                }
                groups.add(new LiveDataCatalog.Group(g.group(), g.title(), fields));
            }
            return new LiveDataCatalog(module, LiveDataCatalog.Source.LABELS, part, Optional.of(l.get().source()),
                    k.lookup.trail(), groups, List.copyOf(l.get().withheldGroups()), 0, notes, hit, requests, now);
        }
        boolean complete = k.probedThrough >= LiveValueId.MAX_GROUP;
        List<String> all = new ArrayList<>(notes);
        all.add(complete ? "no label file: every group 001..255 was asked; these answered (names unknown, "
                + "quantity and unit from the module)"
                : String.format("no label file: groups 001..%03d asked so far; call again to ask the next %d",
                        k.probedThrough, PROBE_GROUPS_PER_CALL));
        return new LiveDataCatalog(module, complete ? LiveDataCatalog.Source.PROBED
                : LiveDataCatalog.Source.PROBE_INCOMPLETE, part, Optional.empty(), k.lookup.trail(),
                List.copyOf(k.probed.values()), List.copyOf(k.probedWithheld), k.probedThrough, all, hit, requests, now);
    }

    // ------------------------------------------------------------------ reads (worker thread)

    /** One group read: the block, or why not. */
    private sealed interface GroupRead permits Got, Missed {
        Instant at();
    }

    private record Got(MeasuringBlock block, Instant at) implements GroupRead {
    }

    private record Missed(LiveValue.State state, String why, Instant at) implements GroupRead {
    }

    /** Opens the module and checks its part number; null session means stop with {@code early}. */
    private record Prepared(ModuleSession session, LiveDataSnapshot.Status early, List<String> notes,
            Optional<LimitHit> hit) {
    }

    private Prepared prepare(ScanContext ctx, Request req) throws InterruptedException {
        List<String> notes = new ArrayList<>();
        ScanEngine.Opened opened;
        try {
            opened = engine.open(ctx, req.module());
        } catch (LimitReachedException e) {
            return new Prepared(null, LiveDataSnapshot.Status.ERROR, List.of(e.getMessage()), Optional.of(e.hit()));
        }
        if (opened.session() == null) {
            String why = opened.failures().isEmpty() ? "no response"
                    : "no response (" + String.join("; ", new LinkedHashSet<>(opened.failures())) + ")";
            engine.health().markNotResponding(req.module(), why);
            return new Prepared(null, LiveDataSnapshot.Status.NOT_RESPONDING, List.of(why), Optional.empty());
        }
        ModuleSession s = opened.session();
        try {
            Identity id = identity(s, notes);
            Known k = known.get(req.module());
            if (k == null || !k.partNumber.equals(id.partNumber())) {
                known.remove(req.module());
                catalogs.remove(req.module());
                s.close();
                notes.add("module " + req.module().hex() + " now reports part number '" + id.partNumber()
                        + "', not '" + (k == null ? "" : k.partNumber) + "' as when its catalog was built: nothing "
                        + "was read; list its available data again");
                return new Prepared(null, LiveDataSnapshot.Status.CATALOG_CHANGED, notes, Optional.empty());
            }
            return new Prepared(s, null, notes, Optional.empty());
        } catch (LimitReachedException e) {
            s.close();
            notes.add(e.getMessage());
            return new Prepared(null, LiveDataSnapshot.Status.ERROR, notes, Optional.of(e.hit()));
        } catch (RuntimeException | InterruptedException e) {
            s.close();
            throw e;
        }
    }

    private GroupRead readGroup(ModuleSession s, int group) throws InterruptedException, LimitReachedException {
        DiagResult<MeasuringBlock> r = s.call(DiagOp.READ_MEASURING_BLOCK, d -> d.readMeasuringBlock(group));
        Instant at = engine.time().now();
        if (r instanceof DiagResult.Success<MeasuringBlock> ok) {
            if (VinShape.contains(ok.value().response().bytes())) {
                return new Missed(LiveValue.State.WITHHELD, String.format("group %03d's answer looked like it "
                        + "contained a VIN, so it was dropped", group), at);
            }
            return new Got(ok.value(), at);
        }
        return new Missed(LiveValue.State.NOT_READ, String.format("group %03d: %s", group, r.message()), at);
    }

    LiveDataSnapshot read(ScanContext ctx, Request req) throws InterruptedException {
        Instant started = engine.time().now();
        Prepared p = prepare(ctx, req);
        if (p.session() == null) {
            return new LiveDataSnapshot(req.module(), p.early(), req.catalog().labelSource(),
                    notRead(req, p.notes().isEmpty() ? "not read" : p.notes().getLast(), started), p.notes(), p.hit(),
                    ctx.requests(req.module()), started, engine.time().now());
        }
        ModuleSession s = p.session();
        List<String> notes = new ArrayList<>(p.notes());
        Map<Integer, GroupRead> reads = new LinkedHashMap<>();
        Optional<LimitHit> hit = Optional.empty();
        try {
            for (int g : req.groups()) {
                if (ctx.cancelled() || s.abandoned()) {
                    reads.put(g, new Missed(LiveValue.State.NOT_READ, s.abandoned() ? "module " + s.abandonReason()
                            : "cancelled", engine.time().now()));
                    continue;
                }
                reads.put(g, readGroup(s, g));
            }
        } catch (LimitReachedException e) {
            hit = Optional.of(e.hit());
            notes.add(e.getMessage());
        } finally {
            s.close();
        }
        noteAbandoned(s, req.module(), notes);
        List<LiveValue> values = new ArrayList<>();
        for (LiveValueId id : req.ids()) {
            GroupRead gr = reads.get(id.group());
            values.add(gr == null ? notReadValue(req, id, hit.map(LimitHit::message).orElse("not read"), engine.time().now())
                    : value(req, id, gr));
        }
        return new LiveDataSnapshot(req.module(), status(values), req.catalog().labelSource(), values, notes, hit,
                ctx.requests(req.module()), started, engine.time().now());
    }

    private void noteAbandoned(ModuleSession s, ModuleAddress module, List<String> notes) {
        if (s.abandoned()) {
            engine.health().markNotResponding(module, s.abandonReason());
            notes.add("module " + s.abandonReason());
        }
    }

    private static LiveDataSnapshot.Status status(List<LiveValue> values) {
        long read = values.stream().filter(v -> v.state() != LiveValue.State.NOT_READ
                && v.state() != LiveValue.State.WITHHELD).count();
        if (read == values.size()) {
            return LiveDataSnapshot.Status.OK;
        }
        return read == 0 ? LiveDataSnapshot.Status.ERROR : LiveDataSnapshot.Status.PARTIAL;
    }

    private List<LiveValue> notRead(Request req, String why, Instant at) {
        return req.ids().stream().map(id -> notReadValue(req, id, why, at)).toList();
    }

    private static LiveValue notReadValue(Request req, LiveValueId id, String why, Instant at) {
        LiveDataCatalog.Field f = req.catalog().field(id).orElseThrow();
        return new LiveValue(id, f.name(), f.quantity(), f.unit(), Optional.empty(), "(not read)", "", f.specs(),
                LiveValue.State.NOT_READ, Optional.of(why), at);
    }

    private static LiveValue value(Request req, LiveValueId id, GroupRead gr) {
        LiveDataCatalog.Field f = req.catalog().field(id).orElseThrow();
        if (gr instanceof Missed m) {
            return new LiveValue(id, f.name(), f.quantity(), f.unit(), Optional.empty(),
                    m.state() == LiveValue.State.WITHHELD ? "(withheld)" : "(not read)", "", f.specs(), m.state(),
                    Optional.of(m.why()), m.at());
        }
        Got got = (Got) gr;
        Optional<MeasuringValue> mv = got.block().field(id.field());
        if (mv.isEmpty()) {
            return new LiveValue(id, f.name(), f.quantity(), f.unit(), Optional.empty(), "(not read)", "", f.specs(),
                    LiveValue.State.NOT_READ, Optional.of(String.format("group %03d has only %d field(s)", id.group(),
                            got.block().fields().size())), got.at());
        }
        MeasuringValue v = mv.get();
        LiveValue.State state = v.empty() ? LiveValue.State.EMPTY
                : v.decoded() ? LiveValue.State.OK : LiveValue.State.NOT_DECODED;
        Optional<String> problem = switch (state) {
            case EMPTY -> Optional.of("the module sent no value for this field (25 00 00); it may not measure it");
            case NOT_DECODED -> Optional.of(String.format("formula %02X is not decoded yet; see raw", v.formula()));
            default -> Optional.empty();
        };
        return new LiveValue(id, f.name(), v.quantity(), v.unit(), v.value(), v.text(), v.raw(), f.specs(), state,
                problem, got.at());
    }

    // ------------------------------------------------------------------ recordings (worker thread)

    /** A recording before its CSV is written. */
    record RecordingRun(LiveDataRecording recording, String csv) {
    }

    RecordingRun record(ScanContext ctx, Request req, Duration duration) throws InterruptedException {
        Instant started = engine.time().now();
        Prepared p = prepare(ctx, req);
        if (p.session() == null) {
            LiveDataRecording r = new LiveDataRecording(ctx.id, req.module(), p.early(), req.catalog().labelSource(),
                    List.of(), 0, duration, Duration.ZERO, Optional.empty(), p.notes(), p.hit(),
                    ctx.requests(req.module()), started, engine.time().now());
            return new RecordingRun(r, "");
        }
        ModuleSession s = p.session();
        List<String> notes = new ArrayList<>(p.notes());
        Optional<LimitHit> hit = Optional.empty();
        long t0 = engine.time().nanoTime();
        long end = t0 + duration.toNanos();
        long next = t0;
        List<Double> times = new ArrayList<>();
        List<Map<Integer, GroupRead>> rounds = new ArrayList<>();
        String stop = null;
        try {
            while (engine.time().nanoTime() < end) {
                Map<Integer, GroupRead> round = new LinkedHashMap<>();
                double at = (engine.time().nanoTime() - t0) / 1e9;
                for (int g : req.groups()) {
                    if (ctx.cancelled()) {
                        stop = "cancelled";
                        break;
                    }
                    if (s.abandoned()) {
                        stop = "module " + s.abandonReason();
                        break;
                    }
                    s.idleUntil(next);
                    round.put(g, readGroup(s, g));
                    // Counted from the end of this exchange: a keep-alive or the request gap can delay the
                    // send itself, so this is the rule that always holds.
                    next = engine.time().nanoTime() + RECORDING_REQUEST_INTERVAL.toNanos();
                }
                if (!round.isEmpty()) {
                    times.add(at);
                    rounds.add(round);
                }
                if (stop != null) {
                    break;
                }
            }
        } catch (LimitReachedException e) {
            hit = Optional.of(e.hit());
            stop = e.getMessage();
        } finally {
            s.close();
        }
        noteAbandoned(s, req.module(), notes);
        Duration recorded = Duration.ofNanos(engine.time().nanoTime() - t0);
        if (stop != null) {
            notes.add("recording ended early after " + LimitHit.seconds(recorded) + ": " + stop);
        }
        List<List<LiveValue>> perId = new ArrayList<>();
        for (LiveValueId id : req.ids()) {
            List<LiveValue> vs = new ArrayList<>();
            for (Map<Integer, GroupRead> round : rounds) {
                GroupRead gr = round.get(id.group());
                vs.add(gr == null ? notReadValue(req, id, stop == null ? "not read" : stop, engine.time().now())
                        : value(req, id, gr));
            }
            perId.add(vs);
        }
        List<LiveSeries> series = new ArrayList<>();
        for (int i = 0; i < req.ids().size(); i++) {
            series.add(series(req.catalog().field(req.ids().get(i)).orElseThrow(), perId.get(i), times));
        }
        LiveDataSnapshot.Status status = rounds.isEmpty() ? LiveDataSnapshot.Status.ERROR
                : stop != null || series.stream().anyMatch(x -> x.samples() < rounds.size())
                        ? LiveDataSnapshot.Status.PARTIAL : LiveDataSnapshot.Status.OK;
        LiveDataRecording r = new LiveDataRecording(ctx.id, req.module(), status, req.catalog().labelSource(), series,
                rounds.size(), duration, recorded, Optional.empty(), notes, hit, ctx.requests(req.module()), started,
                engine.time().now());
        return new RecordingRun(r, csv(req, times, perId));
    }

    private static final Pattern RANGE = Pattern.compile(
            "(?i)^range:\\s*([-+]?\\d+(?:\\.\\d+)?)\\s*\\.{2,}\\s*([-+]?\\d+(?:\\.\\d+)?)");

    /** The label's first {@code Range: a...b}, if it parses. */
    static Optional<BigDecimal[]> range(List<String> specs) {
        for (String spec : specs) {
            Matcher m = RANGE.matcher(spec.strip());
            if (m.find()) {
                BigDecimal lo = new BigDecimal(m.group(1));
                BigDecimal hi = new BigDecimal(m.group(2));
                if (lo.compareTo(hi) <= 0) {
                    return Optional.of(new BigDecimal[] {lo, hi});
                }
            }
        }
        return Optional.empty();
    }

    static LiveSeries series(LiveDataCatalog.Field f, List<LiveValue> values, List<Double> times) {
        BigDecimal min = null;
        BigDecimal max = null;
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal first = null;
        BigDecimal last = null;
        Double minAt = null;
        Double maxAt = null;
        int decoded = 0;
        int scale = 0;
        int empty = 0;
        int notRead = 0;
        String lastProblem = null;
        String quantity = f.quantity();
        String unit = f.unit();
        Optional<BigDecimal[]> range = range(f.specs());
        int outside = 0;
        BigDecimal outsideMin = null;
        BigDecimal outsideMax = null;
        for (int i = 0; i < values.size(); i++) {
            LiveValue v = values.get(i);
            if (!v.quantity().isEmpty()) {
                quantity = v.quantity();
            }
            if (!v.unit().isEmpty()) {
                unit = v.unit();
            }
            switch (v.state()) {
                case EMPTY -> empty++;
                case NOT_READ, WITHHELD -> {
                    notRead++;
                    lastProblem = v.problem().orElse(null);
                }
                default -> {
                }
            }
            if (v.value().isEmpty()) {
                continue;
            }
            BigDecimal x = v.value().get();
            decoded++;
            scale = Math.max(scale, x.scale());
            sum = sum.add(x);
            if (first == null) {
                first = x;
            }
            last = x;
            if (min == null || x.compareTo(min) < 0) {
                min = x;
                minAt = times.get(i);
            }
            if (max == null || x.compareTo(max) > 0) {
                max = x;
                maxAt = times.get(i);
            }
            if (range.isPresent() && (x.compareTo(range.get()[0]) < 0 || x.compareTo(range.get()[1]) > 0)) {
                outside++;
                outsideMin = outsideMin == null || x.compareTo(outsideMin) < 0 ? x : outsideMin;
                outsideMax = outsideMax == null || x.compareTo(outsideMax) > 0 ? x : outsideMax;
            }
        }
        List<String> events = new ArrayList<>();
        int n = values.size();
        if (outside > 0) {
            events.add(String.format("%d of %d samples outside the label's range %s...%s (seen %s to %s)", outside,
                    decoded, range.get()[0].toPlainString(), range.get()[1].toPlainString(), outsideMin.toPlainString(),
                    outsideMax.toPlainString()));
        }
        if (empty == n && n > 0) {
            events.add("always empty: the module sent no value for this field (it may not measure it)");
        } else if (empty > 0) {
            events.add(empty + " of " + n + " samples empty");
        }
        if (notRead > 0) {
            events.add(notRead + " of " + n + " samples not read" + (lastProblem == null ? "" : " (" + lastProblem + ")"));
        }
        if (decoded > 0 && min.compareTo(max) == 0 && n > 1) {
            events.add("constant at " + min.toPlainString() + (unit.isEmpty() ? "" : " " + unit));
        }
        Optional<BigDecimal> avg = decoded == 0 ? Optional.empty()
                : Optional.of(sum.divide(BigDecimal.valueOf(decoded), scale + 1, RoundingMode.HALF_UP));
        return new LiveSeries(f.id(), f.name(), quantity, unit, f.specs(), n, decoded, Optional.ofNullable(min),
                Optional.ofNullable(max), avg, Optional.ofNullable(first), Optional.ofNullable(last),
                Optional.ofNullable(minAt), Optional.ofNullable(maxAt), events);
    }

    /** {@code seconds,<id> <name> [unit],...} then one row per round; empty cell = no number. */
    static String csv(Request req, List<Double> times, List<List<LiveValue>> perId) {
        StringBuilder sb = new StringBuilder("seconds");
        for (int i = 0; i < req.ids().size(); i++) {
            LiveDataCatalog.Field f = req.catalog().field(req.ids().get(i)).orElseThrow();
            String unit = perId.get(i).stream().map(LiveValue::unit).filter(u -> !u.isEmpty()).findFirst()
                    .orElse(f.unit());
            String head = f.id() + (f.name().isEmpty() ? "" : " " + f.name()) + (unit.isEmpty() ? "" : " [" + unit + "]");
            sb.append(',').append(cell(head));
        }
        sb.append('\n');
        for (int r = 0; r < times.size(); r++) {
            sb.append(String.format(java.util.Locale.ROOT, "%.3f", times.get(r)));
            for (List<LiveValue> vs : perId) {
                sb.append(',').append(vs.get(r).value().map(BigDecimal::toPlainString).orElse(""));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String cell(String s) {
        String clean = s.replaceAll("[\\r\\n]", " ");
        if (clean.contains(",") || clean.contains("\"")) {
            return '"' + clean.replace("\"", "\"\"") + '"';
        }
        return clean;
    }
}
