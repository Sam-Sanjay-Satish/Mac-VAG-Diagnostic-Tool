package io.github.samsanjaysatish.macvagdiag.diag;

import io.github.samsanjaysatish.macvagdiag.can.TimeSource;
import io.github.samsanjaysatish.macvagdiag.transport.ApplicationProtocol;
import io.github.samsanjaysatish.macvagdiag.transport.DiagnosticChannel;

/**
 * Read-only diagnostics for one module over one open {@link DiagnosticChannel}: KWP2000
 * ({@link KwpDiagnosticService}) or UDS ({@link UdsDiagnosticService}).
 *
 * <p>Every method returns a {@link DiagResult}; none throws for anything the module or transport does. The only
 * exceptions are {@link InterruptedException}, {@link IllegalArgumentException} for a wrong argument (e.g. a UDS
 * DTC on a KWP service), and, if the impossible happens, the channel's {@code ForbiddenRequestException}, which
 * propagates unchanged.
 *
 * <p>This layer never decides <i>when</i> to do anything: opening and closing channels and sessions, Tester
 * Present timing, retries, back-off and budgets all belong to {@code scan}. Not thread-safe: {@code scan}'s
 * single request queue serialises calls.
 */
public interface DiagnosticService {

    /** The service for {@code channel}'s protocol, with default timings. */
    static DiagnosticService forChannel(DiagnosticChannel channel, TimeSource time) {
        return forChannel(channel, time, DiagTimings.DEFAULTS);
    }

    /** The service for {@code channel}'s protocol, with {@link KwpOptions#DEFAULT} for KWP2000. */
    static DiagnosticService forChannel(DiagnosticChannel channel, TimeSource time, DiagTimings timings) {
        return forChannel(channel, time, timings, KwpOptions.DEFAULT);
    }

    /**
     * The service for {@code channel}'s protocol.
     *
     * @param kwpOptions used if the channel is KWP2000 ({@link KwpOptions#ENGINE} for 01 Engine), ignored for UDS
     */
    static DiagnosticService forChannel(DiagnosticChannel channel, TimeSource time, DiagTimings timings,
            KwpOptions kwpOptions) {
        return switch (channel.protocol()) {
            case KWP2000 -> new KwpDiagnosticService(channel, time, timings, kwpOptions);
            case UDS -> new UdsDiagnosticService(channel, time, timings);
        };
    }

    ApplicationProtocol protocol();

    /**
     * Requests a diagnostic session: KWP {@code 10 89}, UDS {@code 10 01} / {@code 10 03}. Called by
     * {@code scan}'s session layer only. Returns the positive response as received (UDS {@code 50 03 ...}
     * includes the module's P2 timings, not decoded yet).
     *
     * @throws IllegalArgumentException if {@code session} belongs to the other protocol; nothing is sent
     */
    DiagResult<RawBytes> startSession(DiagnosticSession session) throws InterruptedException;

    /**
     * One Tester Present: KWP {@code 3E} ({@code 7E}), UDS {@code 3E 00} ({@code 7E 00}). Called by
     * {@code scan}'s session layer on its schedule, never by front ends.
     */
    DiagResult<RawBytes> testerPresent() throws InterruptedException;

    /** Part number, software version, etc.; see {@link EcuIdentification}. */
    DiagResult<EcuIdentification> readIdentification() throws InterruptedException;

    /** The module's stored fault codes. */
    DiagResult<DtcList> readDtcs() throws InterruptedException;

    /**
     * The freeze frame / environment data stored with {@code dtc}.
     *
     * <p><b>KWP2000:</b> one {@code 12 00 04 [hi] [lo]} per call. A module that stores the same code several times
     * returns the next stored record for each repeat of the request, so call this once per entry of the DTC list,
     * in list order, on the channel the list was read on (the 2026-11-13 capture: engine codes {@code 04 01},
     * {@code 05 44} twice and {@code 00 87} three times). Nothing is cached here.
     *
     * @throws IllegalArgumentException if {@code dtc} is from the other protocol
     */
    DiagResult<FreezeFrame> readFreezeFrame(Dtc dtc) throws InterruptedException;

    /**
     * The gateway's installation list, {@code 22 04 A1} (one request). Only the gateway (19, TP 2.0 logical
     * address {@code 1F} on the target car) has it; other modules answer negatively ({@code 7F 22 31}), which is
     * returned as {@link DiagResult.Negative}.
     */
    DiagResult<InstallationList> readInstallationList() throws InterruptedException;

    /**
     * The odometer, {@code 22 22 03} (one request). On the target car 17 Instruments has it (UDS, 2026-11-13
     * capture); a module without it answers negatively ({@code 7F 22 31} expected), returned as
     * {@link DiagResult.Negative}.
     */
    DiagResult<Odometer> readOdometer() throws InterruptedException;

    /**
     * One measuring block, KWP {@code 21 [group]} → {@code 61 [group]} + triplets (one request). Fields 1..4 are
     * decoded with the KWP measuring-value formulas; see {@link MeasuringBlock}.
     *
     * <p><b>UDS:</b> UDS modules have no measuring groups (their live values are DIDs, not built yet): returns
     * {@link DiagResult.NotAvailable} and sends nothing.
     *
     * @throws IllegalArgumentException if {@code group} is not 1..255; nothing is sent
     */
    DiagResult<MeasuringBlock> readMeasuringBlock(int group) throws InterruptedException;
}
