package io.github.samsanjaysatish.macvagdiag.can;

import java.nio.charset.StandardCharsets;

/**
 * slcan (Lawicel) text protocol: pure encode/decode functions, no I/O. See the slcan reference in
 * {@code can-core/CLAUDE.md} for what the CANable 2.0 stock firmware actually accepts and sends.
 */
public final class SlcanCodec {

    /** Every command ends with carriage return. */
    public static final char CR = '\r';
    /** Lawicel's error reply (BEL). */
    public static final char BELL = 0x07;

    /** {@code tIIIL<data>}: standard data frame. */
    public static final char STANDARD_DATA = 't';
    /** {@code TIIIIIIIIL<data>}: extended data frame. */
    public static final char EXTENDED_DATA = 'T';
    /** {@code rIIIL}: standard remote frame (received only; we never send these). */
    public static final char STANDARD_REMOTE = 'r';
    /** {@code RIIIIIIIIL}: extended remote frame (received only). */
    public static final char EXTENDED_REMOTE = 'R';

    /** {@code O}: go on the bus in normal mode. */
    public static final String OPEN_COMMAND = "O\r";
    /** {@code C}: go off the bus. */
    public static final String CLOSE_COMMAND = "C\r";
    /** {@code V}: firmware version. */
    public static final String VERSION_COMMAND = "V\r";

    /**
     * The CANable stock firmware's (nonstandard) silent-mode command, sent before {@code O}.
     *
     * <p><b>Not used.</b> In the stock CANable 2.0 firmware (normaldotcom/canable2-fw, commit b158aa7,
     * {@code src/can.c}) {@code M1} only stores the mode, and {@code can_enable()} (run by {@code O})
     * overwrites it with {@code FDCAN_MODE_NORMAL} before initializing the controller, so the adapter
     * ends up in normal mode anyway. {@link SlcanAdapter} therefore refuses {@link AdapterMode#LISTEN_ONLY}.
     * See {@code can-core/CLAUDE.md}.
     */
    public static final String SILENT_MODE_COMMAND = "M1\r";

    static final int STANDARD_ID_DIGITS = 3;
    static final int EXTENDED_ID_DIGITS = 8;

    private SlcanCodec() {
    }

    /** The {@code Sn} digit for a bitrate ({@code S6} = 500 kbit/s). */
    public static char bitrateCode(Bitrate bitrate) {
        return switch (bitrate) {
            case KBIT_10 -> '0';
            case KBIT_20 -> '1';
            case KBIT_50 -> '2';
            case KBIT_100 -> '3';
            case KBIT_125 -> '4';
            case KBIT_250 -> '5';
            case KBIT_500 -> '6';
            // S7 differs between Lawicel (800 kbit/s) and CANable (750 kbit/s), so it is not offered.
            case KBIT_1000 -> '8';
        };
    }

    /** {@code S6\r} for 500 kbit/s. */
    public static String bitrateCommand(Bitrate bitrate) {
        return "S" + bitrateCode(bitrate) + CR;
    }

    /** {@code t7E080210030000000000\r}: uppercase hex, terminated by CR. */
    public static String encodeFrame(CanFrame frame) {
        StringBuilder sb = new StringBuilder(1 + EXTENDED_ID_DIGITS + 1 + 2 * frame.length() + 1);
        if (frame.isExtended()) {
            sb.append(EXTENDED_DATA).append(String.format("%08X", frame.id()));
        } else {
            sb.append(STANDARD_DATA).append(String.format("%03X", frame.id()));
        }
        sb.append(frame.length());
        for (int i = 0; i < frame.length(); i++) {
            sb.append(String.format("%02X", frame.dataByte(i)));
        }
        return sb.append(CR).toString();
    }

    /** The bytes that go on the serial line for a command or frame string. */
    public static byte[] ascii(String command) {
        return command.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Decodes one received line, without its terminating CR. A BEL from {@link SlcanLineSplitter}
     * arrives as the one-character line {@code "\u0007"}.
     *
     * @throws SlcanFormatException with the line quoted, for anything that is not valid slcan
     */
    public static SlcanMessage decode(String line) {
        if (line.isEmpty() || line.equals("z") || line.equals("Z")) {
            return new SlcanMessage.Ack();
        }
        if (line.equals(String.valueOf(BELL))) {
            return new SlcanMessage.Error();
        }
        char prefix = line.charAt(0);
        return switch (prefix) {
            case STANDARD_DATA -> decodeData(line, false);
            case EXTENDED_DATA -> decodeData(line, true);
            case STANDARD_REMOTE -> decodeRemote(line, false);
            case EXTENDED_REMOTE -> decodeRemote(line, true);
            case 'd', 'D', 'b', 'B' -> throw new SlcanFormatException(line, "CAN FD frames are not supported");
            default -> throw new SlcanFormatException(line, "unknown prefix '" + printable(String.valueOf(prefix)) + "'");
        };
    }

    private static SlcanMessage decodeData(String line, boolean extended) {
        int idDigits = extended ? EXTENDED_ID_DIGITS : STANDARD_ID_DIGITS;
        int id = parseId(line, idDigits, extended);
        int dlc = parseDlc(line, idDigits);
        int expected = 1 + idDigits + 1 + 2 * dlc;
        if (line.length() != expected) {
            throw new SlcanFormatException(line, "expected " + expected + " characters for " + dlc
                    + " data bytes, got " + line.length());
        }
        byte[] data = new byte[dlc];
        int pos = 1 + idDigits + 1;
        for (int i = 0; i < dlc; i++) {
            data[i] = (byte) ((hexDigit(line, pos) << 4) | hexDigit(line, pos + 1));
            pos += 2;
        }
        return new SlcanMessage.Received(new CanFrame(id, extended, data));
    }

    private static SlcanMessage decodeRemote(String line, boolean extended) {
        int idDigits = extended ? EXTENDED_ID_DIGITS : STANDARD_ID_DIGITS;
        int id = parseId(line, idDigits, extended);
        int dlc = parseDlc(line, idDigits);
        int expected = 1 + idDigits + 1;
        if (line.length() != expected) {
            throw new SlcanFormatException(line, "expected " + expected + " characters for a remote frame, got "
                    + line.length());
        }
        return new SlcanMessage.RemoteFrame(id, extended, dlc);
    }

    private static int parseId(String line, int idDigits, boolean extended) {
        if (line.length() < 1 + idDigits + 1) {
            throw new SlcanFormatException(line, "too short for a " + (extended ? "29" : "11") + "-bit ID and length");
        }
        long id = 0;
        for (int i = 1; i <= idDigits; i++) {
            id = (id << 4) | hexDigit(line, i);
        }
        long max = extended ? CanFrame.MAX_EXTENDED_ID : CanFrame.MAX_STANDARD_ID;
        if (id > max) {
            throw new SlcanFormatException(line, String.format("ID 0x%X out of range 0x0..0x%X", id, max));
        }
        return (int) id;
    }

    private static int parseDlc(String line, int idDigits) {
        int dlc = hexDigit(line, 1 + idDigits);
        if (dlc > CanFrame.MAX_DATA_LENGTH) {
            throw new SlcanFormatException(line, "length " + dlc + " exceeds " + CanFrame.MAX_DATA_LENGTH);
        }
        return dlc;
    }

    private static int hexDigit(String line, int index) {
        int value = Hex.digit(line.charAt(index));
        if (value < 0) {
            throw new SlcanFormatException(line, "bad hex digit '" + printable(String.valueOf(line.charAt(index)))
                    + "' at position " + index);
        }
        return value;
    }

    /** {@code text} with control characters shown as {@code \r}, {@code \a} or {@code \xNN}, for messages. */
    static String printable(String text) {
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            switch (c) {
                case '\r' -> sb.append("\\r");
                case '\n' -> sb.append("\\n");
                case BELL -> sb.append("\\a");
                default -> {
                    if (c < 0x20 || c > 0x7E) {
                        sb.append(String.format("\\x%02X", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
