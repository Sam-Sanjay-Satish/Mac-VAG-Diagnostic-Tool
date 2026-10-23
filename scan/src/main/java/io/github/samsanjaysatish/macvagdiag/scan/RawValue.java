package io.github.samsanjaysatish.macvagdiag.scan;

import java.util.Objects;
import java.util.Optional;

/**
 * Bytes that aren't decoded (yet), kept with the ID of the request they came from.
 *
 * @param id where they came from, written the way the request appears on the bus, e.g. {@code 1A 9B}
 * @param hex the bytes as bus-style hex, e.g. {@code 31 4B 30}
 * @param text the bytes as text, if they are printable ASCII
 */
public record RawValue(String id, String hex, Optional<String> text) {

    public RawValue {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(hex, "hex");
        Objects.requireNonNull(text, "text");
    }
}
