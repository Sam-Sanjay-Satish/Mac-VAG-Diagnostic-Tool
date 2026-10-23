package io.github.samsanjaysatish.macvagdiag.can;

import java.util.Arrays;

/**
 * One classic CAN data frame: an 11-bit (standard) or 29-bit (extended) ID and 0 to 8 data bytes.
 *
 * <p>Immutable. The data array is copied in and out, and {@code equals}/{@code hashCode} compare
 * content. {@code toString} is the frame as it appears on the bus: {@code 7E0 [8] 02 10 03 00 00 00 00 00}.
 * Remote and CAN FD frames are not modelled.
 */
public final class CanFrame {

    /** Highest 11-bit ID, {@code 7FF}. */
    public static final int MAX_STANDARD_ID = 0x7FF;
    /** Highest 29-bit ID, {@code 1FFFFFFF}. */
    public static final int MAX_EXTENDED_ID = 0x1FFF_FFFF;
    /** Classic CAN carries at most 8 data bytes. */
    public static final int MAX_DATA_LENGTH = 8;

    private final int id;
    private final boolean extended;
    private final byte[] data;

    /**
     * @throws IllegalArgumentException if the ID is out of range for the ID type or there are more than 8 bytes
     */
    public CanFrame(int id, boolean extended, byte[] data) {
        int maxId = extended ? MAX_EXTENDED_ID : MAX_STANDARD_ID;
        if (id < 0 || id > maxId) {
            throw new IllegalArgumentException(String.format(
                    "%s CAN ID 0x%X out of range 0x0..0x%X", extended ? "Extended" : "Standard", id, maxId));
        }
        if (data.length > MAX_DATA_LENGTH) {
            throw new IllegalArgumentException(
                    "CAN data length " + data.length + " exceeds " + MAX_DATA_LENGTH + " bytes");
        }
        this.id = id;
        this.extended = extended;
        this.data = data.clone();
    }

    /** A standard (11-bit) frame: {@code CanFrame.standard(0x7E0, 0x02, 0x10, 0x03)}. */
    public static CanFrame standard(int id, int... data) {
        return new CanFrame(id, false, toBytes(data));
    }

    /** An extended (29-bit) frame. */
    public static CanFrame extended(int id, int... data) {
        return new CanFrame(id, true, toBytes(data));
    }

    /** A standard frame from bus-style hex: {@code CanFrame.standardHex(0x7E0, "02 10 03 00 00 00 00 00")}. */
    public static CanFrame standardHex(int id, String hexData) {
        return new CanFrame(id, false, Hex.parse(hexData));
    }

    /** An extended frame from bus-style hex. */
    public static CanFrame extendedHex(int id, String hexData) {
        return new CanFrame(id, true, Hex.parse(hexData));
    }

    private static byte[] toBytes(int[] values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            if (values[i] < 0 || values[i] > 0xFF) {
                throw new IllegalArgumentException("Byte value out of range 0x00..0xFF: " + values[i]);
            }
            bytes[i] = (byte) values[i];
        }
        return bytes;
    }

    public int id() {
        return id;
    }

    /** {@code true} for a 29-bit ID, {@code false} for 11-bit. */
    public boolean isExtended() {
        return extended;
    }

    /** Number of data bytes (the DLC), 0 to 8. */
    public int length() {
        return data.length;
    }

    /** A copy of the data bytes. */
    public byte[] data() {
        return data.clone();
    }

    /** Data byte {@code index} as an unsigned value 0..255, without copying the array. */
    public int dataByte(int index) {
        return Byte.toUnsignedInt(data[index]);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CanFrame other
                && id == other.id
                && extended == other.extended
                && Arrays.equals(data, other.data);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * Integer.hashCode(id) + Boolean.hashCode(extended)) + Arrays.hashCode(data);
    }

    /** {@code 7E0 [8] 02 10 03 00 00 00 00 00}; extended IDs use 8 digits ({@code 18DAF110 [2] 01 02}). */
    @Override
    public String toString() {
        String idText = extended ? String.format("%08X", id) : String.format("%03X", id);
        String head = idText + " [" + data.length + "]";
        return data.length == 0 ? head : head + " " + Hex.format(data);
    }
}
