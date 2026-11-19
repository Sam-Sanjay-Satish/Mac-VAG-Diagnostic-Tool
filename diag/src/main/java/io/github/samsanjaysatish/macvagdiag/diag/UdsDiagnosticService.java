package io.github.samsanjaysatish.macvagdiag.diag;

import io.github.samsanjaysatish.macvagdiag.can.TimeSource;
import io.github.samsanjaysatish.macvagdiag.transport.ApplicationProtocol;
import io.github.samsanjaysatish.macvagdiag.transport.DiagnosticChannel;
import java.util.ArrayList;
import java.util.Collections;
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
 *   <li>DTCs: {@code 19 02 AE} → {@code 59 02 [availability mask] ([DTC 3 bytes] [status])...}.</li>
 *   <li>Freeze frame: {@code 19 06 [DTC] FF} (extended data, all records) only, as VCDS read it on every UDS
 *       module of the 2026-11-13 capture; VCDS never sent {@code 19 04} (snapshot records), so neither do we
 *       (changed 2026-11-16). The record contents are kept raw: the layout in {@code diag/CLAUDE.md} is still a
 *       hypothesis.</li>
 *   <li>Identification: {@code 22 [DID]} for each of {@link #IDENTIFICATION_DIDS}, one DID per request.</li>
 * </ul>
 */
public final class UdsDiagnosticService implements DiagnosticService {

    /**
     * Status mask for {@code 19 02}: {@code AE} (bits 1 2 3 5 7), what VCDS sent to every UDS module on the
     * 2026-11-13 capture.
     */
    public static final int DTC_STATUS_MASK = 0xAE;
    /** Extended data record number for {@code 19 06}: {@code FF} = all records, as VCDS sent it (2026-11-13). */
    public static final int EXTENDED_DATA_RECORD_ALL = 0xFF;

    /** ISO 14229-1 vehicleManufacturerSparePartNumber. */
    public static final int DID_SPARE_PART_NUMBER = 0xF187;
    /** ISO 14229-1 vehicleManufacturerECUSoftwareVersionNumber. */
    public static final int DID_SOFTWARE_VERSION = 0xF189;
    /** ISO 14229-1 vehicleManufacturerECUHardwareNumber. */
    public static final int DID_HARDWARE_NUMBER = 0xF191;
    /** ISO 14229-1 systemNameOrEngineType. */
    public static final int DID_SYSTEM_NAME = 0xF197;
    /** UNCONFIRMED: VW coding value. Not an ISO DID; from memory. */
    public static final int DID_VW_CODING = 0x0600;

    /**
     * The DIDs read by {@link #readIdentification}, in order, and the field each decodes to. UNCONFIRMED set. The
     * VIN ({@code F190}) is deliberately never read (user's decision, 2026-11-17; VCDS never read it either).
     */
    public static final SequencedMap<Integer, EcuIdentification.Field> IDENTIFICATION_DIDS;

    static {
        SequencedMap<Integer, EcuIdentification.Field> m = new LinkedHashMap<>();
        m.put(DID_SPARE_PART_NUMBER, EcuIdentification.Field.PART_NUMBER);
        m.put(DID_SOFTWARE_VERSION, EcuIdentification.Field.SOFTWARE_VERSION);
        m.put(DID_HARDWARE_NUMBER, EcuIdentification.Field.HARDWARE_NUMBER);
        m.put(DID_SYSTEM_NAME, EcuIdentification.Field.SYSTEM_NAME);
        m.put(DID_VW_CODING, EcuIdentification.Field.CODING);
        IDENTIFICATION_DIDS = Collections.unmodifiableSequencedMap(m);
    }

    /** {@code 59 02 [availability mask]}. */
    static final int DTC_HEADER_LENGTH = 3;
    /** {@code [DTC 3 bytes] [status]}. */
    static final int DTC_RECORD_LENGTH = 4;
    /** {@code 59 04|06 [DTC 3 bytes] [status]}. */
    static final int BY_DTC_HEADER_LENGTH = 6;
    /** Requests {@link #readFreezeFrame} sends: one {@code 19 06}. */
    public static final int FREEZE_FRAME_REQUESTS = 1;

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
        IdentificationReader reader = new IdentificationReader(exchange);
        for (Map.Entry<Integer, EcuIdentification.Field> e : IDENTIFICATION_DIDS.entrySet()) {
            reader.readDid(DiagRequest.udsReadDataByIdentifier(e.getKey()), e.getKey(), e.getValue());
            if (reader.stopped()) {
                break;
            }
        }
        return reader.result(ApplicationProtocol.UDS);
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
        DiagRequest request = DiagRequest.udsReadExtendedData(uds.code(), EXTENDED_DATA_RECORD_ALL);
        DiagResult<RawBytes> r = exchange.run(request);
        if (!(r instanceof DiagResult.Success<RawBytes> s)) {
            return r.failure();
        }
        List<RawRecord> records = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        parseByDtcResponse(request, uds, s.value(), records, notes);
        return new DiagResult.Success<>(new FreezeFrame(dtc, records, List.of(), List.of(s.value()), notes));
    }

    @Override
    public DiagResult<InstallationList> readInstallationList() throws InterruptedException {
        DiagResult<RawBytes> r = exchange.run(DiagRequest.udsReadDataByIdentifier(InstallationList.DID));
        if (r instanceof DiagResult.Success<RawBytes> s) {
            return InstallationList.parse(s.value());
        }
        return r.failure();
    }

    @Override
    public DiagResult<Odometer> readOdometer() throws InterruptedException {
        DiagResult<RawBytes> r = exchange.run(DiagRequest.udsReadDataByIdentifier(Odometer.DID));
        if (r instanceof DiagResult.Success<RawBytes> s) {
            return Odometer.parse(s.value());
        }
        return r.failure();
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

    /** UDS modules have no measuring groups: {@link DiagResult.NotAvailable}, nothing sent. */
    @Override
    public DiagResult<MeasuringBlock> readMeasuringBlock(int group) {
        if (group < 1 || group > 0xFF) {
            throw new IllegalArgumentException("measuring block group must be 1..255: " + group);
        }
        return new DiagResult.NotAvailable<>("measuring blocks are KWP2000 only; UDS live data (DIDs) is not built yet");
    }
}
