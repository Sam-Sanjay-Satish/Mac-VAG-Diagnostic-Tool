package io.github.samsanjaysatish.macvagdiag.diag;

import io.github.samsanjaysatish.macvagdiag.can.Hex;
import io.github.samsanjaysatish.macvagdiag.can.TimeSource;
import io.github.samsanjaysatish.macvagdiag.transport.ApplicationProtocol;
import io.github.samsanjaysatish.macvagdiag.transport.DiagnosticChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.SequencedMap;

/**
 * UDS (ISO 14229-1) over a UDS channel, normally ISO-TP (expected for the EDC17 engine ECU).
 *
 * <p><b>Built before the sniffing capture.</b> The message layouts follow ISO 14229-1 (from memory); which
 * masks, records and DIDs VCDS uses are <b>UNCONFIRMED</b> and kept in the named constants below.
 *
 * <ul>
 *   <li>DTCs: {@code 19 02 FF} → {@code 59 02 [availability mask] ([DTC 3 bytes] [status])...}.</li>
 *   <li>Freeze frame: {@code 19 04 [DTC] FF} (snapshot records) then {@code 19 06 [DTC] FF} (extended data).
 *       Record contents are kept raw: splitting them needs the DID lengths, which only the capture can tell.</li>
 *   <li>Identification: {@code 22 [DID]} for each of {@link #IDENTIFICATION_DIDS}, one DID per request.</li>
 * </ul>
 */
public final class UdsDiagnosticService implements DiagnosticService {

    /** UNCONFIRMED: status mask for {@code 19 02}; {@code FF} = every status bit. */
    public static final int DTC_STATUS_MASK = 0xFF;
    /** UNCONFIRMED: snapshot record number for {@code 19 04}; ISO 14229-1 {@code FF} = all records. */
    public static final int SNAPSHOT_RECORD_ALL = 0xFF;
    /** UNCONFIRMED: extended data record number for {@code 19 06}; ISO 14229-1 {@code FF} = all records. */
    public static final int EXTENDED_DATA_RECORD_ALL = 0xFF;

    /** ISO 14229-1 vehicleManufacturerSparePartNumber. */
    public static final int DID_SPARE_PART_NUMBER = 0xF187;
    /** ISO 14229-1 vehicleManufacturerECUSoftwareVersionNumber. */
    public static final int DID_SOFTWARE_VERSION = 0xF189;
    /** ISO 14229-1 vehicleManufacturerECUHardwareNumber. */
    public static final int DID_HARDWARE_NUMBER = 0xF191;
    /** ISO 14229-1 systemNameOrEngineType. */
    public static final int DID_SYSTEM_NAME = 0xF197;
    /** ISO 14229-1 VIN. */
    public static final int DID_VIN = 0xF190;
    /** UNCONFIRMED: VW coding value. Not an ISO DID; from memory. */
    public static final int DID_VW_CODING = 0x0600;

    /** The DIDs read by {@link #readIdentification}, in order, and the field each decodes to. UNCONFIRMED set. */
    public static final SequencedMap<Integer, EcuIdentification.Field> IDENTIFICATION_DIDS;

    static {
        SequencedMap<Integer, EcuIdentification.Field> m = new LinkedHashMap<>();
        m.put(DID_SPARE_PART_NUMBER, EcuIdentification.Field.PART_NUMBER);
        m.put(DID_SOFTWARE_VERSION, EcuIdentification.Field.SOFTWARE_VERSION);
        m.put(DID_HARDWARE_NUMBER, EcuIdentification.Field.HARDWARE_NUMBER);
        m.put(DID_SYSTEM_NAME, EcuIdentification.Field.SYSTEM_NAME);
        m.put(DID_VW_CODING, EcuIdentification.Field.CODING);
        m.put(DID_VIN, EcuIdentification.Field.VIN);
        IDENTIFICATION_DIDS = Collections.unmodifiableSequencedMap(m);
    }

    /** {@code 59 02 [availability mask]}. */
    static final int DTC_HEADER_LENGTH = 3;
    /** {@code [DTC 3 bytes] [status]}. */
    static final int DTC_RECORD_LENGTH = 4;
    /** {@code 59 04|06 [DTC 3 bytes] [status]}. */
    static final int BY_DTC_HEADER_LENGTH = 6;
    /** {@code 62 [DID hi] [DID lo]}. */
    static final int DID_HEADER_LENGTH = 3;
    static final int VIN_LENGTH = 17;

    private final Exchange exchange;

    public UdsDiagnosticService(DiagnosticChannel channel, TimeSource time) {
        this(channel, time, DiagTimings.DEFAULTS);
    }

    /** @throws IllegalArgumentException if the channel isn't UDS */
    public UdsDiagnosticService(DiagnosticChannel channel, TimeSource time, DiagTimings timings) {
        if (channel.protocol() != ApplicationProtocol.UDS) {
            throw new IllegalArgumentException("Not a UDS channel: " + channel.route());
        }
        this.exchange = new Exchange(channel, time, timings);
    }

    @Override
    public ApplicationProtocol protocol() {
        return ApplicationProtocol.UDS;
    }

    @Override
    public DiagResult<RawBytes> startSession(DiagnosticSession session) throws InterruptedException {
        if (session.protocol() != ApplicationProtocol.UDS) {
            throw new IllegalArgumentException(session + " is not a UDS session");
        }
        DiagRequest request = DiagRequest.udsStartSession(session);
        DiagResult<RawBytes> r = exchange.run(request);
        int sessionByte = request.bytes()[1];
        if (r instanceof DiagResult.Success<RawBytes> s && !Reads.echoes(s.value(), 1, sessionByte)) {
            return new DiagResult.Malformed<>(String.format("session response doesn't echo %02X", sessionByte), s.value());
        }
        return r;
    }

    @Override
    public DiagResult<RawBytes> testerPresent() throws InterruptedException {
        DiagResult<RawBytes> r = exchange.run(DiagRequest.udsTesterPresent());
        if (r instanceof DiagResult.Success<RawBytes> s && !Reads.echoes(s.value(), 1, DiagRequest.UDS_TESTER_PRESENT_SUB_FUNCTION)) {
            return new DiagResult.Malformed<>("tester present response doesn't echo 00", s.value());
        }
        return r;
    }

    @Override
    public DiagResult<EcuIdentification> readIdentification() throws InterruptedException {
        Map<EcuIdentification.Field, String> fields = new EnumMap<>(EcuIdentification.Field.class);
        List<RawRecord> records = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        DiagResult<EcuIdentification> firstFailure = null;
        for (Map.Entry<Integer, EcuIdentification.Field> e : IDENTIFICATION_DIDS.entrySet()) {
            int did = e.getKey();
            DiagRequest request = DiagRequest.udsReadDataByIdentifier(did);
            DiagResult<RawBytes> r = exchange.run(request);
            if (r instanceof DiagResult.Success<RawBytes> s) {
                RawBytes response = s.value();
                if (!Reads.echoes(response, 1, did >> 8) || !Reads.echoes(response, 2, did & 0xFF)) {
                    records.add(new RawRecord(request.hex() + " (whole response, DID not echoed)", response));
                    notes.add(request.hex() + ": answer doesn't echo the DID, kept raw");
                    continue;
                }
                RawBytes data = RawBytes.slice(response.bytes(), DID_HEADER_LENGTH, response.length());
                records.add(new RawRecord(request.hex(), data));
                decodeField(e.getValue(), data, request, fields, notes);
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
        return new DiagResult.Success<>(new EcuIdentification(ApplicationProtocol.UDS, fields, records, notes));
    }

    private static void decodeField(EcuIdentification.Field field, RawBytes data, DiagRequest request,
            Map<EcuIdentification.Field, String> fields, List<String> notes) {
        switch (field) {
            case CODING -> {
                if (data.isEmpty()) {
                    notes.add(request.hex() + ": empty coding");
                } else {
                    fields.put(field, Hex.formatCompact(data.bytes()));
                }
            }
            case VIN -> data.asciiText().filter(t -> t.length() == VIN_LENGTH).ifPresentOrElse(
                    vin -> fields.put(field, vin),
                    () -> notes.add(request.hex() + ": not a 17-character VIN, kept raw"));
            default -> data.asciiText().ifPresentOrElse(
                    text -> fields.put(field, text),
                    () -> notes.add(request.hex() + ": not text, kept raw"));
        }
    }

    @Override
    public DiagResult<DtcList> readDtcs() throws InterruptedException {
        DiagResult<RawBytes> r = exchange.run(DiagRequest.udsReadDtcsByStatusMask(DTC_STATUS_MASK));
        if (r instanceof DiagResult.Success<RawBytes> s) {
            return parseDtcs(s.value());
        }
        return r.failure();
    }

    /**
     * Parses {@code 59 02 [availability mask] ([DTC 3 bytes] [status])...}. Whole records are decoded; left-over
     * bytes are kept in {@link DtcList#undecoded()} with a warning.
     */
    static DiagResult<DtcList> parseDtcs(RawBytes response) {
        if (response.length() < DTC_HEADER_LENGTH) {
            return new DiagResult.Malformed<>("too short for '59 02 [availability mask]'", response);
        }
        if (response.byteAt(1) != DiagRequest.UDS_REPORT_DTC_BY_STATUS_MASK) {
            return new DiagResult.Malformed<>("doesn't echo sub-function 02", response);
        }
        byte[] bytes = response.bytes();
        int whole = (bytes.length - DTC_HEADER_LENGTH) / DTC_RECORD_LENGTH;
        List<Dtc> dtcs = new ArrayList<>();
        for (int i = 0; i < whole; i++) {
            int at = DTC_HEADER_LENGTH + i * DTC_RECORD_LENGTH;
            int code = (Byte.toUnsignedInt(bytes[at]) << 16) | (Byte.toUnsignedInt(bytes[at + 1]) << 8)
                    | Byte.toUnsignedInt(bytes[at + 2]);
            dtcs.add(new UdsDtc(code, Byte.toUnsignedInt(bytes[at + 3])));
        }
        RawBytes undecoded = RawBytes.slice(bytes, DTC_HEADER_LENGTH + whole * DTC_RECORD_LENGTH, bytes.length);
        List<String> warnings = undecoded.isEmpty() ? List.of()
                : List.of(undecoded.length() + " byte(s) after the last whole 4-byte DTC record (truncated?): " + undecoded.hex());
        return new DiagResult.Success<>(new DtcList(dtcs, response, undecoded, OptionalInt.of(response.byteAt(2)), warnings));
    }

    @Override
    public DiagResult<FreezeFrame> readFreezeFrame(Dtc dtc) throws InterruptedException {
        Objects.requireNonNull(dtc, "dtc");
        if (!(dtc instanceof UdsDtc uds)) {
            throw new IllegalArgumentException("Not a UDS DTC: " + dtc);
        }
        List<RawRecord> records = new ArrayList<>();
        List<RawBytes> responses = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        DiagResult<FreezeFrame> firstFailure = null;
        List<DiagRequest> requests = List.of(
                DiagRequest.udsReadSnapshot(uds.code(), SNAPSHOT_RECORD_ALL),
                DiagRequest.udsReadExtendedData(uds.code(), EXTENDED_DATA_RECORD_ALL));
        for (DiagRequest request : requests) {
            DiagResult<RawBytes> r = exchange.run(request);
            if (r instanceof DiagResult.Success<RawBytes> s) {
                responses.add(s.value());
                parseByDtcResponse(request, uds, s.value(), records, notes);
            } else if (Reads.partFailureContinues(r)) {
                notes.add(request.hex() + ": " + r.message());
                if (firstFailure == null) {
                    firstFailure = r.failure();
                }
            } else {
                return r.failure();
            }
        }
        if (responses.isEmpty()) {
            return firstFailure;
        }
        return new DiagResult.Success<>(new FreezeFrame(dtc, records, List.of(), responses, notes));
    }

    /**
     * {@code 59 04|06 [DTC 3 bytes] [status] [records...]}: checks the header, keeps everything after it as one
     * raw record identified by the request.
     */
    static void parseByDtcResponse(DiagRequest request, UdsDtc dtc, RawBytes response, List<RawRecord> records,
            List<String> notes) {
        int subFunction = request.bytes()[1];
        if (response.length() < BY_DTC_HEADER_LENGTH || response.byteAt(1) != subFunction) {
            records.add(new RawRecord(request.hex() + " (whole response, unexpected header)", response));
            notes.add(request.hex() + ": header not '59 " + String.format("%02X", subFunction)
                    + " [DTC] [status]', whole response kept raw");
            return;
        }
        RawBytes echoed = RawBytes.slice(response.bytes(), 2, 5);
        if (!echoed.equals(dtc.codeBytes())) {
            notes.add(request.hex() + ": module answered for DTC " + echoed.hex() + ", not " + dtc.codeBytes().hex());
        }
        RawBytes data = RawBytes.slice(response.bytes(), BY_DTC_HEADER_LENGTH, response.length());
        if (data.isEmpty()) {
            notes.add(request.hex() + ": no records stored");
        } else {
            records.add(new RawRecord(request.hex(), data));
        }
    }
}
