package io.github.samsanjaysatish.macvagdiag.diag;

import java.util.List;
import java.util.Objects;

/**
 * Environment data the module stored with one DTC (VCDS: mileage, date/time, priority, frequency and reset
 * counters, plus module-specific values).
 *
 * <p>Nothing here is decoded from memory: the record layouts depend on data identifier lengths that only the
 * capture / VCDS log can tell. So for now every record is a {@link RawRecord} with its ID, {@link #fields()} is
 * empty, and the whole responses are kept too.
 *
 * @param dtc the DTC this belongs to
 * @param records undecoded record data, each with an ID saying which request it came from
 * @param fields decoded values, once known (none yet)
 * @param responses every positive response as received, unmodified
 * @param notes readable notes: parts the module didn't provide, echo mismatches
 */
public record FreezeFrame(Dtc dtc, List<RawRecord> records, List<Field> fields, List<RawBytes> responses,
        List<String> notes) {

    /** A decoded freeze-frame value. */
    public record Field(Kind kind, String value, String sourceRecordId) {

        /** What VCDS shows for a fault. */
        public enum Kind { MILEAGE, DATE_TIME, PRIORITY, OCCURRENCE_COUNTER, RESET_COUNTER, OTHER }

        public Field {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(sourceRecordId, "sourceRecordId");
        }
    }

    public FreezeFrame {
        Objects.requireNonNull(dtc, "dtc");
        records = List.copyOf(records);
        fields = List.copyOf(fields);
        responses = List.copyOf(responses);
        notes = List.copyOf(notes);
    }
}
