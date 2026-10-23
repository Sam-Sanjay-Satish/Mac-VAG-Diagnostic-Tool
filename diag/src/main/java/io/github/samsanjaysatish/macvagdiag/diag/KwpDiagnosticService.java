package io.github.samsanjaysatish.macvagdiag.diag;

import io.github.samsanjaysatish.macvagdiag.can.TimeSource;
import io.github.samsanjaysatish.macvagdiag.transport.ApplicationProtocol;
import io.github.samsanjaysatish.macvagdiag.transport.DiagnosticChannel;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * VW KWP2000 (ISO 14230-3 as VW uses it) over a KWP2000 channel, normally TP 2.0.
 *
 * <p><b>Built before the sniffing capture: every request parameter and response layout here is from memory and
 * UNCONFIRMED</b>, kept in the named constants below so the capture can correct it in one place.
 *
 * <ul>
 *   <li>DTCs: {@code 18 02} → {@code 58 [count] ([code hi] [code lo] [status])...}. The ISO 14230 long form
 *       {@code 18 02 FF 00} (status + group) is refused by the approved allowlist, so only the one-byte form is
 *       sent; whether VW modules accept it is an open question for the user.</li>
 *   <li>Identification: {@code 1A 9B} (VW identification block, kept raw: the layout isn't decoded yet) and
 *       {@code 1A 90} (VIN, 17 ASCII characters).</li>
 *   <li>Freeze frame: not available. The allowlisted {@code 12 xx} can't name a DTC (ISO 14230 needs a record
 *       access method after the frame number), so {@link #readFreezeFrame} sends nothing and says so.</li>
 * </ul>
 */
public final class KwpDiagnosticService implements DiagnosticService {

    /** UNCONFIRMED: the one status byte sent with {@code 18}. */
    public static final int DTC_STATUS_PARAMETER = 0x02;
    /** UNCONFIRMED: {@code 1A 9B}, VW ECU identification block (part number, component, coding, workshop code). */
    public static final int ID_OPTION_VW_ECU_IDENTIFICATION = 0x9B;
    /** UNCONFIRMED: {@code 1A 90}, VIN (ISO 14230 identification option). */
    public static final int ID_OPTION_VIN = 0x90;
    /** The identification options read, in order. UNCONFIRMED which ones VCDS reads. */
    public static final List<Integer> IDENTIFICATION_OPTIONS = List.of(ID_OPTION_VW_ECU_IDENTIFICATION, ID_OPTION_VIN);

    /** {@code 58 [count]}. */
    static final int DTC_HEADER_LENGTH = 2;
    /** UNCONFIRMED: {@code [code hi] [code lo] [status]}. */
    static final int DTC_RECORD_LENGTH = 3;
    static final int VIN_LENGTH = 17;

    static final String FREEZE_FRAME_NOT_AVAILABLE = "KWP2000 freeze frames need a request that names the DTC "
            + "(ISO 14230 '12 [frame] [record access method] ...'); the approved allowlist only has the one-byte "
            + "'12 xx' form. Open question for the user, pending the capture.";

    private final Exchange exchange;
    private final VagCodeMapping codeMapping;

    public KwpDiagnosticService(DiagnosticChannel channel, TimeSource time) {
        this(channel, time, DiagTimings.DEFAULTS, VagCodeMapping.NONE);
    }

    /**
     * @param codeMapping VAG → OBD code mapping for {@link KwpDtc#obdCode()}; {@link VagCodeMapping#NONE} until
     *     it is derived from the VCDS log
     * @throws IllegalArgumentException if the channel isn't KWP2000
     */
    public KwpDiagnosticService(DiagnosticChannel channel, TimeSource time, DiagTimings timings, VagCodeMapping codeMapping) {
        if (channel.protocol() != ApplicationProtocol.KWP2000) {
            throw new IllegalArgumentException("Not a KWP2000 channel: " + channel.route());
        }
        this.exchange = new Exchange(channel, time, timings);
        this.codeMapping = Objects.requireNonNull(codeMapping, "codeMapping");
    }

    @Override
    public ApplicationProtocol protocol() {
        return ApplicationProtocol.KWP2000;
    }

    @Override
    public DiagResult<RawBytes> startSession(DiagnosticSession session) throws InterruptedException {
        if (session != DiagnosticSession.KWP_VW_DIAGNOSTIC) {
            throw new IllegalArgumentException(session + " is not a KWP2000 session");
        }
        DiagResult<RawBytes> r = exchange.run(DiagRequest.kwpStartVwDiagnosticSession());
        if (r instanceof DiagResult.Success<RawBytes> s && !Reads.echoes(s.value(), 1, DiagRequest.KWP_SESSION_VW_DIAGNOSTIC)) {
            return new DiagResult.Malformed<>("session response doesn't echo 89", s.value());
        }
        return r;
    }

    @Override
    public DiagResult<RawBytes> testerPresent() throws InterruptedException {
        return exchange.run(DiagRequest.kwpTesterPresent());
    }

    @Override
    public DiagResult<EcuIdentification> readIdentification() throws InterruptedException {
        Map<EcuIdentification.Field, String> fields = new EnumMap<>(EcuIdentification.Field.class);
        List<RawRecord> records = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        DiagResult<EcuIdentification> firstFailure = null;
        for (int option : IDENTIFICATION_OPTIONS) {
            DiagRequest request = DiagRequest.kwpReadEcuIdentification(option);
            DiagResult<RawBytes> r = exchange.run(request);
            if (r instanceof DiagResult.Success<RawBytes> s) {
                RawBytes response = s.value();
                if (!Reads.echoes(response, 1, option)) {
                    records.add(new RawRecord(request.hex() + " (whole response, option not echoed)", response));
                    notes.add(request.hex() + ": answer doesn't echo the option, kept raw");
                    continue;
                }
                RawBytes data = RawBytes.slice(response.bytes(), 2, response.length());
                records.add(new RawRecord(request.hex(), data));
                if (option == ID_OPTION_VIN) {
                    decodeVin(data, request, fields, notes);
                }
            } else if (Reads.partFailureContinues(r)) {
                notes.add(request.hex() + ": " + r.message());
                if (firstFailure == null) {
                    firstFailure = r.failure();
                }
            } else {
                return r.failure();
            }
        }
        if (records.isEmpty()) {
            return firstFailure;
        }
        return new DiagResult.Success<>(new EcuIdentification(ApplicationProtocol.KWP2000, fields, records, notes));
    }

    private static void decodeVin(RawBytes data, DiagRequest request, Map<EcuIdentification.Field, String> fields,
            List<String> notes) {
        data.asciiText().filter(t -> t.length() == VIN_LENGTH).ifPresentOrElse(
                vin -> fields.put(EcuIdentification.Field.VIN, vin),
                () -> notes.add(request.hex() + ": not a 17-character VIN, kept raw"));
    }

    @Override
    public DiagResult<DtcList> readDtcs() throws InterruptedException {
        DiagResult<RawBytes> r = exchange.run(DiagRequest.kwpReadDtcsByStatus(DTC_STATUS_PARAMETER));
        if (r instanceof DiagResult.Success<RawBytes> s) {
            return parseDtcs(s.value(), codeMapping);
        }
        return r.failure();
    }

    /**
     * Parses {@code 58 [count] ([hi] [lo] [status])...}. Whole records are decoded; left-over bytes are kept in
     * {@link DtcList#undecoded()}; a count that doesn't match the records is a warning, not a failure.
     */
    static DiagResult<DtcList> parseDtcs(RawBytes response, VagCodeMapping codeMapping) {
        if (response.length() < DTC_HEADER_LENGTH) {
            return new DiagResult.Malformed<>("too short for '58 [count]'", response);
        }
        int count = response.byteAt(1);
        byte[] bytes = response.bytes();
        int bodyLength = bytes.length - DTC_HEADER_LENGTH;
        int whole = bodyLength / DTC_RECORD_LENGTH;
        List<Dtc> dtcs = new ArrayList<>();
        for (int i = 0; i < whole; i++) {
            int at = DTC_HEADER_LENGTH + i * DTC_RECORD_LENGTH;
            int code = (Byte.toUnsignedInt(bytes[at]) << 8) | Byte.toUnsignedInt(bytes[at + 1]);
            dtcs.add(new KwpDtc(code, new KwpDtcStatus(Byte.toUnsignedInt(bytes[at + 2])), codeMapping.obdCode(code)));
        }
        int leftoverStart = DTC_HEADER_LENGTH + whole * DTC_RECORD_LENGTH;
        RawBytes undecoded = RawBytes.slice(bytes, leftoverStart, bytes.length);
        List<String> warnings = new ArrayList<>();
        if (!undecoded.isEmpty()) {
            warnings.add(undecoded.length() + " byte(s) after the last whole 3-byte DTC record (truncated?): " + undecoded.hex());
        }
        if (count != whole) {
            warnings.add("count byte says " + count + " DTC(s), the response holds " + whole);
        }
        return new DiagResult.Success<>(new DtcList(dtcs, response, undecoded, OptionalInt.empty(), warnings));
    }

    /**
     * Not available on KWP2000 yet: sends nothing and returns {@link DiagResult.NotAvailable}, see the class
     * comment.
     */
    @Override
    public DiagResult<FreezeFrame> readFreezeFrame(Dtc dtc) {
        Objects.requireNonNull(dtc, "dtc");
        if (!(dtc instanceof KwpDtc)) {
            throw new IllegalArgumentException("Not a KWP2000 DTC: " + dtc);
        }
        return new DiagResult.NotAvailable<>(FREEZE_FRAME_NOT_AVAILABLE);
    }
}
