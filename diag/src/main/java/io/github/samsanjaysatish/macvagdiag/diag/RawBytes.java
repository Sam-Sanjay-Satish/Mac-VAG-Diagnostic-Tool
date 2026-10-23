package io.github.samsanjaysatish.macvagdiag.diag;

import io.github.samsanjaysatish.macvagdiag.can.Hex;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable bytes as they came from the module. Used wherever this layer keeps data it can't (yet) decode, so
 * nothing is ever dropped. Prints as bus-style hex, e.g. {@code 1A 2B}.
 */
public record RawBytes(byte[] bytes) {

    public static final RawBytes EMPTY = new RawBytes(new byte[0]);

    public RawBytes {
        bytes = Objects.requireNonNull(bytes, "bytes").clone();
    }

    /** Parses bus-style hex, e.g. {@code "59 02 FF"}. */
    public static RawBytes ofHex(String hex) {
        return new RawBytes(Hex.parse(hex));
    }

    /** Bytes {@code from} (inclusive) to {@code to} (exclusive) of {@code source}. */
    static RawBytes slice(byte[] source, int from, int to) {
        return new RawBytes(Arrays.copyOfRange(source, from, to));
    }

    /** A copy of the bytes. */
    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    public int length() {
        return bytes.length;
    }

    public boolean isEmpty() {
        return bytes.length == 0;
    }

    /** Byte {@code index} as an unsigned value 0..255. */
    public int byteAt(int index) {
        return Byte.toUnsignedInt(bytes[index]);
    }

    /** Bus-style hex, e.g. {@code 1A 2B}; empty string for no bytes. */
    public String hex() {
        return Hex.format(bytes);
    }

    /**
     * The bytes as text, if they are printable ASCII ({@code 20..7E}) once trailing padding ({@code 00},
     * {@code 20}, {@code FF}) is trimmed. Empty if any other byte is present or nothing is left. The raw bytes
     * stay available either way.
     */
    public Optional<String> asciiText() {
        int end = bytes.length;
        while (end > 0 && isPadding(bytes[end - 1])) {
            end--;
        }
        if (end == 0) {
            return Optional.empty();
        }
        for (int i = 0; i < end; i++) {
            int b = Byte.toUnsignedInt(bytes[i]);
            if (b < 0x20 || b > 0x7E) {
                return Optional.empty();
            }
        }
        return Optional.of(new String(bytes, 0, end, StandardCharsets.US_ASCII).strip());
    }

    private static boolean isPadding(byte b) {
        return b == 0x00 || b == 0x20 || b == (byte) 0xFF;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RawBytes other && Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return hex();
    }
}
