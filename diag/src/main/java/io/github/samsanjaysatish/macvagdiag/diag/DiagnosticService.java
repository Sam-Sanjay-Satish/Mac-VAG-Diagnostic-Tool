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

    /** The service for {@code channel}'s protocol. */
    static DiagnosticService forChannel(DiagnosticChannel channel, TimeSource time, DiagTimings timings) {
        return switch (channel.protocol()) {
            case KWP2000 -> new KwpDiagnosticService(channel, time, timings, VagCodeMapping.NONE);
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
     * @throws IllegalArgumentException if {@code dtc} is from the other protocol
     */
    DiagResult<FreezeFrame> readFreezeFrame(Dtc dtc) throws InterruptedException;
}
