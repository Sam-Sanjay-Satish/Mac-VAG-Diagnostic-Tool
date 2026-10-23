package io.github.samsanjaysatish.macvagdiag.diag;

import io.github.samsanjaysatish.macvagdiag.transport.ApplicationProtocol;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What a module says about itself. Every identification answer is kept raw in {@link #records()}; the
 * {@link #fields()} map holds only what this layer could decode (and is often partial: a module may not provide
 * a field, and the KWP {@code 1A 9B} block layout isn't decoded yet).
 *
 * @param protocol the protocol it was read with
 * @param fields decoded values, e.g. {@code PART_NUMBER -> "03L906018JL"}
 * @param records every identification answer as received (after the service/identifier echo), with its ID
 * @param notes readable notes: identifiers the module refused, values that weren't text
 */
public record EcuIdentification(ApplicationProtocol protocol, Map<Field, String> fields, List<RawRecord> records,
        List<String> notes) {

    /** Decoded identification fields. */
    public enum Field { PART_NUMBER, SOFTWARE_VERSION, HARDWARE_NUMBER, SYSTEM_NAME, CODING, VIN }

    public EcuIdentification {
        Objects.requireNonNull(protocol, "protocol");
        fields = Map.copyOf(fields);
        records = List.copyOf(records);
        notes = List.copyOf(notes);
    }

    public Optional<String> field(Field f) {
        return Optional.ofNullable(fields.get(f));
    }

    public Optional<String> partNumber() {
        return field(Field.PART_NUMBER);
    }

    public Optional<String> softwareVersion() {
        return field(Field.SOFTWARE_VERSION);
    }

    public Optional<String> hardwareNumber() {
        return field(Field.HARDWARE_NUMBER);
    }

    public Optional<String> systemName() {
        return field(Field.SYSTEM_NAME);
    }

    public Optional<String> coding() {
        return field(Field.CODING);
    }

    /** The VIN, unmasked; masking for reports is {@code scan}'s job. */
    public Optional<String> vin() {
        return field(Field.VIN);
    }
}
