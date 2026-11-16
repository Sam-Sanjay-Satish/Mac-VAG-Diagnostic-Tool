package io.github.samsanjaysatish.macvagdiag.diag;

import io.github.samsanjaysatish.macvagdiag.can.TimeSource;
import io.github.samsanjaysatish.macvagdiag.transport.ApplicationProtocol;
import io.github.samsanjaysatish.macvagdiag.transport.DiagnosticChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.SequencedMap;

/**
 * VW KWP2000 (ISO 14230-3 as VW uses it) over a KWP2000 channel, normally TP 2.0. Request shapes and response
 * layouts follow the 2026-11-13 capture of the target car (see {@code diag/CLAUDE.md}).
 *
 * <ul>
 *   <li>DTCs: {@code 18 00 FF 00} (engine) or {@code 18 02 FF 00} (others), chosen by {@link KwpOptions} →
 *       {@code 58 [count] ([code hi] [code lo] [status])...}.</li>
 *   <li>Identification: {@code 1A 9B} (VW identification block, decoded), then {@code 1A 90} (VIN). Modules that
 *       refuse {@code 1A 9B} (03 ABS, 09 BCM and the 19 gateway answer {@code 7F 1A 11}) are read with one
 *       UDS-style {@code 22 [DID]} per field instead ({@link #FALLBACK_DIDS}).</li>
 *   <li>Freeze frame: {@code 12 00 04 [hi] [lo]}, one request per call (see {@link DiagnosticService#readFreezeFrame}
 *       for the order rule).</li>
 *   <li>Installation list (gateway): {@code 22 04 A1}.</li>
 * </ul>
 */
public final class KwpDiagnosticService implements DiagnosticService {

    /** {@code 1A 9B}: VW ECU identification block (part number, software version, coding, workshop code, component). */
    public static final int ID_OPTION_VW_ECU_IDENTIFICATION = 0x9B;
    /**
     * UNCONFIRMED: {@code 1A 90}, VIN (ISO 14230 identification option). VCDS never sent it on the capture (it
     * reads the VIN with OBD-II {@code 09 02}); kept because {@code scan} reads the VIN from identification.
     */
    public static final int ID_OPTION_VIN = 0x90;

    /**
     * The DIDs read with {@code 22} when {@code 1A 9B} is refused, in order, and the field each decodes to. The
     * same identification DIDs VCDS read from 03, 09 and 19 on the capture ({@code F187} etc.); {@code F190} (VIN)
     * isn't among them.
     */
    public static final SequencedMap<Integer, EcuIdentification.Field> FALLBACK_DIDS;

    static {
        SequencedMap<Integer, EcuIdentification.Field> m = new LinkedHashMap<>();
        m.put(UdsDiagnosticService.DID_SPARE_PART_NUMBER, EcuIdentification.Field.PART_NUMBER);
        m.put(UdsDiagnosticService.DID_SOFTWARE_VERSION, EcuIdentification.Field.SOFTWARE_VERSION);
        m.put(UdsDiagnosticService.DID_HARDWARE_NUMBER, EcuIdentification.Field.HARDWARE_NUMBER);
        m.put(UdsDiagnosticService.DID_SYSTEM_NAME, EcuIdentification.Field.SYSTEM_NAME);
        m.put(UdsDiagnosticService.DID_VW_CODING, EcuIdentification.Field.CODING);
        FALLBACK_DIDS = Collections.unmodifiableSequencedMap(m);
    }

    /** Most requests {@link #readIdentification} sends: {@code 1A 9B} then either {@code 1A 90} or the DIDs. */
    public static final int MAX_IDENTIFICATION_REQUESTS = 1 + Math.max(1, FALLBACK_DIDS.size());
    /** Requests {@link #readFreezeFrame} sends: one {@code 12 00 04 [hi] [lo]}. */
    public static final int FREEZE_FRAME_REQUESTS = 1;

    /** {@code 58 [count]}. */
    static final int DTC_HEADER_LENGTH = 2;
    /** {@code [code hi] [code lo] [status]}. */
    static final int DTC_RECORD_LENGTH = 3;

    // 1A 9B data layout (after 5A 9B), 46 bytes on every KWP module of the capture (01, 02, 08, 44).
    static final int ID_9B_LENGTH = 46;
    static final int ID_9B_PART_NUMBER_END = 12;   // 12 ASCII, e.g. "03L997030M  "
    static final int ID_9B_SOFTWARE_END = 16;      // 4 ASCII, e.g. "9983"
    static final int ID_9B_CODING_END = 20;        // [type] [3 bytes], e.g. 03 00 C3 9E = coding 0050078
    static final int ID_9B_WORKSHOP_END = 26;      // 6 bytes workshop code, kept raw
    /** Bytes 26..45: component name, 20 ASCII, e.g. "R4 2.0l TDI G000AG  ". */
    static final int ID_9B_COMPONENT_END = 46;
    /**
     * Coding type byte {@code 03}: the next 3 bytes are the coding as a 7-digit decimal ({@code 00 C3 9E} =
     * {@code 0050078}; 01 and 02 on the capture). {@code 00 00 00 00} = no coding (08 and 44; VCDS shows none).
     * UNCONFIRMED: other type values (kept raw with a note).
     */
    static final int CODING_TYPE_DECIMAL = 0x03;

    private final Exchange exchange;
    private final KwpOptions options;

    public KwpDiagnosticService(DiagnosticChannel channel, TimeSource time) {
        this(channel, time, DiagTimings.DEFAULTS, KwpOptions.DEFAULT);
    }

    /**
     * @param options the module's DTC request and code mapping ({@link KwpOptions#ENGINE} for 01 Engine,
     *     {@link KwpOptions#DEFAULT} otherwise)
     * @throws IllegalArgumentException if the channel isn't KWP2000
     */
    public KwpDiagnosticService(DiagnosticChannel channel, TimeSource time, DiagTimings timings, KwpOptions options) {
        if (channel.protocol() != ApplicationProtocol.KWP2000) {
            throw new IllegalArgumentException("Not a KWP2000 channel: " + channel.route());
        }
        this.exchange = new Exchange(channel, time, timings);
        this.options = Objects.requireNonNull(options, "options");
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

    /**
     * {@code 1A 9B} first. Decoded if it answers; then {@code 1A 90} for the VIN. If {@code 1A 9B} is answered
     * negatively, {@code 1A} isn't used further and the {@link #FALLBACK_DIDS} are read with {@code 22} instead.
     */
    @Override
    public DiagResult<EcuIdentification> readIdentification() throws InterruptedException {
        IdentificationReader reader = new IdentificationReader(exchange);
        DiagRequest block = DiagRequest.kwpReadEcuIdentification(ID_OPTION_VW_ECU_IDENTIFICATION);
        DiagResult<RawBytes> first = exchange.run(block);
        switch (first) {
            case DiagResult.Success<RawBytes> s -> {
                readOption(reader, block, s.value());
                DiagRequest vin = DiagRequest.kwpReadEcuIdentification(ID_OPTION_VIN);
                RawBytes response = reader.run(vin);
                if (response != null) {
                    readOption(reader, vin, response);
                }
            }
            case DiagResult.Negative<RawBytes> n -> {
                reader.notes.add(block.hex() + ": " + n.message() + "; reading identification DIDs with 22 instead");
                for (var e : FALLBACK_DIDS.entrySet()) {
                    reader.readDid(DiagRequest.kwpReadDataByIdentifier(e.getKey()), e.getKey(), e.getValue());
                    if (reader.stopped()) {
                        break;
                    }
                }
                if (reader.records.isEmpty() && !reader.stopped()) {
                    return n.failure();
                }
            }
            case DiagResult.Malformed<RawBytes> m -> {
                reader.notes.add(block.hex() + ": " + m.message());
                DiagRequest vin = DiagRequest.kwpReadEcuIdentification(ID_OPTION_VIN);
                RawBytes response = reader.run(vin);
                if (response != null) {
                    readOption(reader, vin, response);
                } else if (!reader.stopped() && reader.records.isEmpty()) {
                    return m.failure();
                }
            }
            default -> {
                return first.failure();
            }
        }
        return reader.result(ApplicationProtocol.KWP2000);
    }

    /** One {@code 5A [option] [data]} answer: kept raw, and decoded for {@code 9B} / {@code 90}. */
    private static void readOption(IdentificationReader reader, DiagRequest request, RawBytes response) {
        int option = request.bytes()[1] & 0xFF;
        if (!Reads.echoes(response, 1, option)) {
            reader.records.add(new RawRecord(request.hex() + " (whole response, option not echoed)", response));
            reader.notes.add(request.hex() + ": answer doesn't echo the option, kept raw");
            return;
        }
        RawBytes data = RawBytes.slice(response.bytes(), 2, response.length());
        reader.records.add(new RawRecord(request.hex(), data));
        if (option == ID_OPTION_VIN) {
            reader.putVin(data, request);
        } else if (option == ID_OPTION_VW_ECU_IDENTIFICATION) {
            decodeIdentificationBlock(reader, request, data);
        }
    }

    /** The 46-byte {@code 1A 9B} block; anything else is kept raw with a note. */
    static void decodeIdentificationBlock(IdentificationReader reader, DiagRequest request, RawBytes data) {
        if (data.length() != ID_9B_LENGTH) {
            reader.notes.add(request.hex() + ": " + data.length() + " bytes, not the " + ID_9B_LENGTH
                    + "-byte block layout; kept raw");
            return;
        }
        byte[] b = data.bytes();
        reader.putText(EcuIdentification.Field.PART_NUMBER, RawBytes.slice(b, 0, ID_9B_PART_NUMBER_END), request);
        reader.putText(EcuIdentification.Field.SOFTWARE_VERSION, RawBytes.slice(b, ID_9B_PART_NUMBER_END, ID_9B_SOFTWARE_END), request);
        int codingType = data.byteAt(ID_9B_SOFTWARE_END);
        int coding = (data.byteAt(ID_9B_SOFTWARE_END + 1) << 16) | (data.byteAt(ID_9B_SOFTWARE_END + 2) << 8)
                | data.byteAt(ID_9B_SOFTWARE_END + 3);
        if (codingType == CODING_TYPE_DECIMAL) {
            reader.fields.put(EcuIdentification.Field.CODING, String.format("%07d", coding));
        } else if (codingType != 0 || coding != 0) {
            reader.notes.add(String.format("%s: coding type %02X not known, coding bytes kept raw (%s)", request.hex(),
                    codingType, RawBytes.slice(b, ID_9B_SOFTWARE_END, ID_9B_CODING_END).hex()));
        }
        reader.putText(EcuIdentification.Field.SYSTEM_NAME, RawBytes.slice(b, ID_9B_WORKSHOP_END, ID_9B_COMPONENT_END), request);
    }

    @Override
    public DiagResult<DtcList> readDtcs() throws InterruptedException {
        DiagResult<RawBytes> r = exchange.run(DiagRequest.kwpReadDtcsByStatus(options.dtcRequest()));
        if (r instanceof DiagResult.Success<RawBytes> s) {
            return parseDtcs(s.value(), options.codeMapping());
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
     * {@code 12 00 04 [hi] [lo]} for {@code dtc}'s code, decoded by {@link KwpFreezeFrames}. Call once per DTC list
     * entry, in list order (see {@link DiagnosticService#readFreezeFrame}).
     */
    @Override
    public DiagResult<FreezeFrame> readFreezeFrame(Dtc dtc) throws InterruptedException {
        Objects.requireNonNull(dtc, "dtc");
        if (!(dtc instanceof KwpDtc kwp)) {
            throw new IllegalArgumentException("Not a KWP2000 DTC: " + dtc);
        }
        DiagRequest request = DiagRequest.kwpReadFreezeFrameByDtc(kwp.code());
        DiagResult<RawBytes> r = exchange.run(request);
        if (r instanceof DiagResult.Success<RawBytes> s) {
            return new DiagResult.Success<>(KwpFreezeFrames.decode(kwp, request, s.value()));
        }
        return r.failure();
    }

    @Override
    public DiagResult<InstallationList> readInstallationList() throws InterruptedException {
        DiagResult<RawBytes> r = exchange.run(DiagRequest.kwpReadDataByIdentifier(InstallationList.DID));
        if (r instanceof DiagResult.Success<RawBytes> s) {
            return InstallationList.parse(s.value());
        }
        return r.failure();
    }
}
