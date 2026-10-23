package io.github.samsanjaysatish.macvagdiag.diag;

import java.util.Objects;

/**
 * Bytes this layer doesn't decode (yet), kept with an ID saying where they came from, e.g. id
 * {@code "22 F1 87"} and data {@code 30 33 4C ...}. Reports and MCP output show these as they are; labels are a
 * later problem.
 *
 * @param id where the bytes came from, written the way the request appears on the bus
 * @param data the undecoded bytes
 */
public record RawRecord(String id, RawBytes data) {

    public RawRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(data, "data");
    }

    @Override
    public String toString() {
        return id + ": " + (data.isEmpty() ? "(no bytes)" : data.hex());
    }
}
