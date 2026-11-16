package io.github.samsanjaysatish.macvagdiag.diag;

import java.util.List;
import java.util.Objects;

/**
 * Environment data the module stored with one DTC (VCDS: status, priority, frequency, reset counter, mileage,
 * time, plus module-specific values).
 *
 * <ul>
 *   <li><b>KWP2000</b> ({@code 12 00 04 [hi] [lo]}): decoded into {@link #fields()} from the layout confirmed on
 *       the 2026-11-13 capture against the VCDS log; the date stays raw. The record data is kept as well.</li>
 *   <li><b>UDS</b> ({@code 19 06}): kept raw, {@link #fields()} empty; the record layout is
 *       still a hypothesis.</li>
 * </ul>
 *
 * @param dtc the DTC this belongs to
 * @param records undecoded record data, each with an ID saying which request it came from
 * @param fields decoded values, in the order the module sent them
 * @param responses every positive response as received, unmodified
 * @param notes readable notes: parts the module didn't provide, echo mismatches, values left raw
 */
public record FreezeFrame(Dtc dtc, List<RawRecord> records, List<Field> fields, List<RawBytes> responses,
        List<String> notes) {

    /**
     * A decoded freeze-frame value.
     *
     * @param kind what it is
     * @param value as VCDS prints it, e.g. {@code 362256 km}, {@code 09:13:31}, {@code RPM: 828 /min}
     * @param sourceRecordId the request it came from, e.g. {@code 12 00 04 2B A6}
     */
    public record Field(Kind kind, String value, String sourceRecordId) {

        /** What VCDS shows for a fault. */
        public enum Kind {
            /** The DTC status byte stored with the record, as 8 bits ({@code 11100000}). */
            STATUS,
            PRIORITY,
            /** VCDS "Fault Frequency". */
            OCCURRENCE_COUNTER,
            RESET_COUNTER,
            MILEAGE,
            /** VCDS "Time Indication". */
            TIME_INDICATION,
            /** Time of day, {@code hh:mm:ss}. */
            TIME,
            /** Date and time together (not decoded for any protocol yet). */
            DATE_TIME,
            /** A measured value stored with the fault, e.g. {@code RPM: 828 /min}, or a raw one VCDS would label. */
            ENVIRONMENT,
            OTHER
        }

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
