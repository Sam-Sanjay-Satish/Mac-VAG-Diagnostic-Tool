package io.github.samsanjaysatish.macvagdiag.scan;

import java.util.List;
import java.util.Objects;

/**
 * The environment data stored with one fault code. Nothing is decoded yet (record layouts need the capture), so
 * {@link #records()} holds the raw blocks and {@link #fields()} is empty for now.
 *
 * @param availability whether it was read
 * @param message readable detail, e.g. why it isn't available
 * @param records raw record blocks, each with the request it came from
 * @param fields decoded values, once known (none yet)
 * @param responses every whole response as bus-style hex
 * @param notes readable notes from decoding
 */
public record FreezeFrameData(Availability availability, String message, List<RawValue> records, List<Field> fields,
        List<String> responses, List<String> notes) {

    public enum Availability {
        READ,
        /** The module or the approved allowlist can't provide it (e.g. KWP2000 for now). */
        NOT_AVAILABLE,
        /** Asked, but the module refused or didn't answer. */
        FAILED,
        /** Not asked: a limit, a cancel or a module that stopped responding. */
        SKIPPED
    }

    /** A decoded value, e.g. kind {@code MILEAGE}. */
    public record Field(String kind, String value, String sourceRecordId) {
        public Field {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(sourceRecordId, "sourceRecordId");
        }
    }

    public FreezeFrameData {
        Objects.requireNonNull(availability, "availability");
        Objects.requireNonNull(message, "message");
        records = List.copyOf(records);
        fields = List.copyOf(fields);
        responses = List.copyOf(responses);
        notes = List.copyOf(notes);
    }

    public static FreezeFrameData skipped(String why) {
        return new FreezeFrameData(Availability.SKIPPED, why, List.of(), List.of(), List.of(), List.of());
    }
}
