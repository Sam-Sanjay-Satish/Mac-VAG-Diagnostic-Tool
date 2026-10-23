package io.github.samsanjaysatish.macvagdiag.scan;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What a module says about itself. Decoded fields are often partial (the KWP {@code 1A 9B} block isn't decoded
 * yet); everything the module sent is in {@link #raw()}. The VIN is unmasked here; masking is a reporting option
 * ({@link VinMask}).
 */
public record EcuInfo(Protocol protocol, Optional<String> partNumber, Optional<String> softwareVersion,
        Optional<String> hardwareNumber, Optional<String> systemName, Optional<String> coding, Optional<String> vin,
        List<RawValue> raw, List<String> notes) {

    public EcuInfo {
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(partNumber, "partNumber");
        Objects.requireNonNull(softwareVersion, "softwareVersion");
        Objects.requireNonNull(hardwareNumber, "hardwareNumber");
        Objects.requireNonNull(systemName, "systemName");
        Objects.requireNonNull(coding, "coding");
        Objects.requireNonNull(vin, "vin");
        raw = List.copyOf(raw);
        notes = List.copyOf(notes);
    }
}
