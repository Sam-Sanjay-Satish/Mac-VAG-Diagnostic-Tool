package io.github.samsanjaysatish.macvagdiag.diag;

import io.github.samsanjaysatish.macvagdiag.transport.ApplicationProtocol;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * A scripted {@link DiagnosticService} for {@code scan} tests: canned {@link DiagResult}s, no channel, no bytes.
 *
 * <pre>{@code
 * FakeDiagnosticService engine = FakeDiagnosticService.uds()
 *         .onReadDtcs(FakeDiagnosticService.ok(FakeDiagnosticService.dtcList(new UdsDtc(0x040100, 0x2F))))
 *         .onReadFreezeFrame(new DiagResult.Busy<>(NegativeResponse.of(0x19, 0x21)));
 * }</pre>
 *
 * <p>Each {@code on...} call queues one result for that operation; results are used in order, and an operation
 * with nothing queued throws {@link IllegalStateException} (so a test notices an unexpected call). Every call is
 * recorded in {@link #calls()}. Scriptable operations: {@code onStartSession}, {@code onTesterPresent},
 * {@code onReadIdentification}, {@code onReadDtcs}, {@code onReadFreezeFrame}, {@code onReadInstallationList}, {@code onReadOdometer}. Like the real services, a session or DTC of the other protocol throws
 * {@link IllegalArgumentException}. Not thread-safe (the real services aren't either).
 *
 * <p>Get it with {@code testImplementation(testFixtures(project(":diag")))}.
 */
public final class FakeDiagnosticService implements DiagnosticService {

    public enum Operation { START_SESSION, TESTER_PRESENT, READ_IDENTIFICATION, READ_DTCS, READ_FREEZE_FRAME, READ_INSTALLATION_LIST,
        READ_ODOMETER }

    /** One recorded call; {@code argument} is the session or DTC, or null. */
    public record Call(Operation operation, Object argument) {
        public Call {
            Objects.requireNonNull(operation, "operation");
        }
    }

    private final ApplicationProtocol protocol;
    private final Deque<DiagResult<RawBytes>> sessions = new ArrayDeque<>();
    private final Deque<DiagResult<RawBytes>> testerPresents = new ArrayDeque<>();
    private final Deque<DiagResult<EcuIdentification>> identifications = new ArrayDeque<>();
    private final Deque<DiagResult<DtcList>> dtcLists = new ArrayDeque<>();
    private final Deque<DiagResult<FreezeFrame>> freezeFrames = new ArrayDeque<>();
    private final Deque<DiagResult<InstallationList>> installationLists = new ArrayDeque<>();
    private final Deque<DiagResult<Odometer>> odometers = new ArrayDeque<>();
    private final List<Call> calls = new ArrayList<>();

    public FakeDiagnosticService(ApplicationProtocol protocol) {
        this.protocol = Objects.requireNonNull(protocol, "protocol");
    }

    public static FakeDiagnosticService kwp() {
        return new FakeDiagnosticService(ApplicationProtocol.KWP2000);
    }

    public static FakeDiagnosticService uds() {
        return new FakeDiagnosticService(ApplicationProtocol.UDS);
    }

    // ---- convenience builders for results ----

    public static <T> DiagResult<T> ok(T value) {
        return new DiagResult.Success<>(value);
    }

    /** A clean DTC list (no raw response, no warnings). */
    public static DtcList dtcList(Dtc... dtcs) {
        return new DtcList(List.of(dtcs), RawBytes.EMPTY, RawBytes.EMPTY, OptionalInt.empty(), List.of());
    }

    /** An identification with just these decoded fields. */
    public static EcuIdentification identification(ApplicationProtocol protocol, Map<EcuIdentification.Field, String> fields) {
        return new EcuIdentification(protocol, fields, List.of(), List.of());
    }

    /** An installation list of {@code [address, tp20, status, group]} entries, with no raw response. */
    public static InstallationList installationList(InstallationList.Entry... entries) {
        return new InstallationList(List.of(entries), RawBytes.EMPTY, RawBytes.EMPTY, List.of());
    }

    /** An odometer reading of {@code kilometres}, with no raw response. */
    public static Odometer odometer(int kilometres) {
        return new Odometer(kilometres, RawBytes.EMPTY);
    }

    /** A freeze frame holding raw records only. */
    public static FreezeFrame freezeFrame(Dtc dtc, RawRecord... records) {
        return new FreezeFrame(dtc, List.of(records), List.of(), List.of(), List.of());
    }

    // ---- scripting ----

    public FakeDiagnosticService onStartSession(DiagResult<RawBytes> result) {
        sessions.add(Objects.requireNonNull(result));
        return this;
    }

    public FakeDiagnosticService onTesterPresent(DiagResult<RawBytes> result) {
        testerPresents.add(Objects.requireNonNull(result));
        return this;
    }

    public FakeDiagnosticService onReadIdentification(DiagResult<EcuIdentification> result) {
        identifications.add(Objects.requireNonNull(result));
        return this;
    }

    public FakeDiagnosticService onReadDtcs(DiagResult<DtcList> result) {
        dtcLists.add(Objects.requireNonNull(result));
        return this;
    }

    public FakeDiagnosticService onReadFreezeFrame(DiagResult<FreezeFrame> result) {
        freezeFrames.add(Objects.requireNonNull(result));
        return this;
    }

    public FakeDiagnosticService onReadInstallationList(DiagResult<InstallationList> result) {
        installationLists.add(Objects.requireNonNull(result));
        return this;
    }

    public FakeDiagnosticService onReadOdometer(DiagResult<Odometer> result) {
        odometers.add(Objects.requireNonNull(result));
        return this;
    }

    // ---- DiagnosticService ----

    @Override
    public ApplicationProtocol protocol() {
        return protocol;
    }

    @Override
    public DiagResult<RawBytes> startSession(DiagnosticSession session) {
        if (session.protocol() != protocol) {
            throw new IllegalArgumentException(session + " is not a " + protocol + " session");
        }
        return next(Operation.START_SESSION, session, sessions);
    }

    @Override
    public DiagResult<RawBytes> testerPresent() {
        return next(Operation.TESTER_PRESENT, null, testerPresents);
    }

    @Override
    public DiagResult<EcuIdentification> readIdentification() {
        return next(Operation.READ_IDENTIFICATION, null, identifications);
    }

    @Override
    public DiagResult<DtcList> readDtcs() {
        return next(Operation.READ_DTCS, null, dtcLists);
    }

    @Override
    public DiagResult<FreezeFrame> readFreezeFrame(Dtc dtc) {
        Objects.requireNonNull(dtc, "dtc");
        if (dtc.protocol() != protocol) {
            throw new IllegalArgumentException("Not a " + protocol + " DTC: " + dtc);
        }
        return next(Operation.READ_FREEZE_FRAME, dtc, freezeFrames);
    }

    @Override
    public DiagResult<InstallationList> readInstallationList() {
        return next(Operation.READ_INSTALLATION_LIST, null, installationLists);
    }

    @Override
    public DiagResult<Odometer> readOdometer() {
        return next(Operation.READ_ODOMETER, null, odometers);
    }

    private <T> DiagResult<T> next(Operation op, Object argument, Deque<DiagResult<T>> queue) {
        calls.add(new Call(op, argument));
        DiagResult<T> r = queue.poll();
        if (r == null) {
            throw new IllegalStateException("No scripted result for " + op + (argument == null ? "" : " " + argument));
        }
        return r;
    }

    /** Every call so far, in order. */
    public List<Call> calls() {
        return List.copyOf(calls);
    }

    /** True if every scripted result was used. */
    public boolean allScriptUsed() {
        return sessions.isEmpty() && testerPresents.isEmpty() && identifications.isEmpty() && dtcLists.isEmpty()
                && freezeFrames.isEmpty() && installationLists.isEmpty() && odometers.isEmpty();
    }
}
