package io.github.samsanjaysatish.macvagdiag.diag;

import java.util.ArrayList;
import java.util.List;

/**
 * Decodes a KWP2000 freeze frame, {@code 12 00 04 [hi] [lo]} →
 *
 * <pre>
 * 52 00 6C 01 [status] [priority] [frequency] [reset counter] [mileage km, 3 bytes] [time indication]
 *    [5 bytes date/time] ([formula] [a] [b])... 04 [hi] [lo]
 * </pre>
 *
 * This layout fits all 14 records of the 2026-11-13 capture (12 engine, 2 gateway); every decoded value matches
 * the VCDS log. The gateway stores no environment triplets.
 *
 * <ul>
 *   <li>{@code 6C 01}: <b>UNCONFIRMED</b>, the same in every record; checked and noted if different, not decoded.</li>
 *   <li>Date/time: the low 17 bits of the last 3 of the 5 bytes are hour (5 bits), minute (6), second (6). VCDS
 *       prints the second <b>+ 1</b> on every KWP record (UDS records match exactly), so {@link #vcdsTime} adds one
 *       second (carrying over, UNCONFIRMED at :59). The date bits are close to all-ones on this car and VCDS prints
 *       the same date for every record, so the date is not decoded: the 5 bytes stay in the raw record.</li>
 * </ul>
 */
final class KwpFreezeFrames {

    /** {@code 52 00}: positive response SID, then the freeze frame number echo. */
    static final int FRAME_NUMBER_INDEX = 1;
    /** UNCONFIRMED: bytes 2..3, {@code 6C 01} in every record seen. */
    static final int HEADER_CONSTANT_HI = 0x6C;
    static final int HEADER_CONSTANT_LO = 0x01;
    static final int STATUS_INDEX = 4;
    static final int PRIORITY_INDEX = 5;
    static final int FREQUENCY_INDEX = 6;
    static final int RESET_COUNTER_INDEX = 7;
    static final int MILEAGE_INDEX = 8;
    static final int TIME_INDICATION_INDEX = 11;
    static final int DATE_TIME_INDEX = 12;
    static final int DATE_TIME_LENGTH = 5;
    static final int FIRST_TRIPLET_INDEX = DATE_TIME_INDEX + DATE_TIME_LENGTH;
    static final int TRIPLET_LENGTH = 3;
    /** {@code 04 [hi] [lo]}: the record access method and the DTC, echoed at the end. */
    static final int TRAILER_LENGTH = 3;
    static final int MIN_LENGTH = FIRST_TRIPLET_INDEX + TRAILER_LENGTH;
    /** Low 17 bits of the last 3 date/time bytes: hour · minute · second. */
    static final int TIME_BITS_MASK = 0x1FFFF;

    private KwpFreezeFrames() {
    }

    /**
     * The freeze frame for {@code dtc} from {@code response} (the whole positive response). Never fails: a
     * response that doesn't fit the layout is kept whole as a raw record with a note, and no fields.
     */
    static FreezeFrame decode(KwpDtc dtc, DiagRequest request, RawBytes response) {
        String id = request.hex();
        List<RawRecord> records = new ArrayList<>();
        List<FreezeFrame.Field> fields = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        int n = response.length();
        if (n < MIN_LENGTH || (n - MIN_LENGTH) % TRIPLET_LENGTH != 0
                || response.byteAt(FRAME_NUMBER_INDEX) != DiagRequest.KWP_FREEZE_FRAME_NUMBER
                || response.byteAt(n - TRAILER_LENGTH) != DiagRequest.KWP_RECORD_ACCESS_BY_DTC) {
            records.add(new RawRecord(id + " (whole response, unexpected layout)", response));
            notes.add(id + ": not the '52 00 6C 01 ... 04 [hi] [lo]' layout, whole response kept raw");
            return new FreezeFrame(dtc, records, fields, List.of(response), notes);
        }
        byte[] b = response.bytes();
        records.add(new RawRecord(id, RawBytes.slice(b, FRAME_NUMBER_INDEX + 1, n)));

        if (response.byteAt(2) != HEADER_CONSTANT_HI || response.byteAt(3) != HEADER_CONSTANT_LO) {
            notes.add(String.format("%s: header bytes %02X %02X, not the 6C 01 seen so far", id, response.byteAt(2), response.byteAt(3)));
        }
        int echoed = (response.byteAt(n - 2) << 8) | response.byteAt(n - 1);
        if (echoed != dtc.code()) {
            notes.add(String.format("%s: module answered for code %04X, not %04X", id, echoed, dtc.code()));
        }
        int status = response.byteAt(STATUS_INDEX);
        if (status != dtc.status().raw()) {
            notes.add(String.format("%s: stored status %02X differs from the DTC list entry's %02X "
                    + "(freeze frames must be read once per list entry, in list order)", id, status, dtc.status().raw()));
        }

        fields.add(new FreezeFrame.Field(FreezeFrame.Field.Kind.STATUS, new KwpDtcStatus(status).bits(), id));
        fields.add(new FreezeFrame.Field(FreezeFrame.Field.Kind.PRIORITY, Integer.toString(response.byteAt(PRIORITY_INDEX)), id));
        fields.add(new FreezeFrame.Field(FreezeFrame.Field.Kind.OCCURRENCE_COUNTER, Integer.toString(response.byteAt(FREQUENCY_INDEX)), id));
        fields.add(new FreezeFrame.Field(FreezeFrame.Field.Kind.RESET_COUNTER, Integer.toString(response.byteAt(RESET_COUNTER_INDEX)), id));
        int km = (response.byteAt(MILEAGE_INDEX) << 16) | (response.byteAt(MILEAGE_INDEX + 1) << 8) | response.byteAt(MILEAGE_INDEX + 2);
        fields.add(new FreezeFrame.Field(FreezeFrame.Field.Kind.MILEAGE, km + " km", id));
        fields.add(new FreezeFrame.Field(FreezeFrame.Field.Kind.TIME_INDICATION, Integer.toString(response.byteAt(TIME_INDICATION_INDEX)), id));
        int timeBits = ((response.byteAt(DATE_TIME_INDEX + 2) << 16) | (response.byteAt(DATE_TIME_INDEX + 3) << 8)
                | response.byteAt(DATE_TIME_INDEX + 4)) & TIME_BITS_MASK;
        vcdsTime(timeBits).ifPresentOrElse(
                t -> fields.add(new FreezeFrame.Field(FreezeFrame.Field.Kind.TIME, t, id)),
                () -> notes.add(String.format("%s: time bits %05X are not a time of day, kept raw", id, timeBits)));

        for (int at = FIRST_TRIPLET_INDEX; at < n - TRAILER_LENGTH; at += TRIPLET_LENGTH) {
            int formula = response.byteAt(at);
            fields.add(new FreezeFrame.Field(FreezeFrame.Field.Kind.ENVIRONMENT,
                    KwpMeasuringValue.describe(formula, response.byteAt(at + 1), response.byteAt(at + 2)), id));
        }
        return new FreezeFrame(dtc, records, fields, List.of(response), notes);
    }

    /**
     * {@code hh:mm:ss} from the 17 time bits, plus the one second VCDS adds; empty if the bits aren't a time of
     * day.
     */
    static java.util.Optional<String> vcdsTime(int timeBits) {
        int hour = timeBits >> 12;
        int minute = (timeBits >> 6) & 0x3F;
        int second = timeBits & 0x3F;
        if (hour > 23 || minute > 59 || second > 59) {
            return java.util.Optional.empty();
        }
        int total = (hour * 3600 + minute * 60 + second + 1) % 86_400;
        return java.util.Optional.of(String.format("%02d:%02d:%02d", total / 3600, total / 60 % 60, total % 60));
    }
}
