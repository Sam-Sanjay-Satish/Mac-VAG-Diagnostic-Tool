package io.github.samsanjaysatish.macvagdiag.tools;

import io.github.samsanjaysatish.macvagdiag.can.CanFrame;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rebuilds diagnostic message payloads from a capture's frames and remembers where every payload byte sits
 * (frame index, byte index), so the sanitizer can mask a VIN that is split across frames and write the change
 * back into exactly those bytes.
 *
 * <p>Self-contained and tolerant on purpose: it reads captures of other testers (VCDS), including channels
 * that were already open when the capture started and messages cut off at its end. It never fails on odd
 * frames; it just leaves them out of the payloads (the sanitizer's raw-byte verification still covers them).
 *
 * <ul>
 *   <li><b>VW TP 2.0.</b> Setup requests on {@code 200} are ignored. A positive setup reply on {@code 200 + addr}
 *       ({@code 00 D0 [rx lo] [rx hi] [tx lo] [tx hi] ..}) marks both negotiated IDs as TP 2.0 from then on.
 *       On those IDs, opcodes {@code 0x}..{@code 3x} are data: the first packet of a message carries a 2-byte
 *       length (the top two bits are flags; {@code 80 03} on every {@code 7F xx 78}), then payload; later packets
 *       are all payload; {@code 1x} / {@code 3x} end the message. ACKs ({@code 9x}, {@code Bx}) and channel
 *       control ({@code Ax}) carry no payload; {@code A8} drops a half-received message.</li>
 *   <li><b>ISO-TP</b> (every other ID, outside {@code 200..2FF}): single frame, first frame (also the 32-bit
 *       length escape), consecutive frames, flow control. Padding after the declared length is left out.
 *       Consecutive frames without a first frame (capture started mid-message) form a message of unknown
 *       length.</li>
 * </ul>
 */
final class DiagPayloads {

    static final int TP20_SETUP_ID = 0x200;
    static final int TP20_SETUP_REPLY_LAST_ID = 0x2FF;
    static final int TP20_SETUP_POSITIVE = 0xD0;
    /** The length field's flag bits (bit 15 is set on every {@code 7F xx 78} the car sent). */
    static final int TP20_LENGTH_MASK = 0x3FFF;
    static final int TP20_DISCONNECT = 0xA8;
    private static final int TP20_ID_INVALID_BIT = 0x10;
    private static final int UNKNOWN = -1;

    enum Protocol {
        TP20("TP 2.0"),
        ISOTP("ISO-TP");

        private final String label;

        Protocol(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    /**
     * One reassembled message. {@code frames[i]} / {@code indexes[i]} locate {@code bytes[i]} in the input.
     *
     * @param complete whether the declared length was received exactly
     */
    record Message(int canId, boolean extended, Protocol protocol, byte[] bytes, int[] frames, int[] indexes,
                   boolean complete) {

        int firstFrame() {
            return frames.length == 0 ? -1 : frames[0];
        }

        String idText() {
            return extended ? "%08X".formatted(canId) : "%03X".formatted(canId);
        }
    }

    /**
     * All payload bytes of one CAN ID and protocol, messages concatenated in order. Searching it also finds a
     * string that runs across a message boundary.
     */
    record Stream(int canId, boolean extended, Protocol protocol, byte[] bytes, int[] frames, int[] indexes) {

        String idText() {
            return extended ? "%08X".formatted(canId) : "%03X".formatted(canId);
        }
    }

    private DiagPayloads() {
    }

    static List<Message> reassemble(List<CanFrame> frames) {
        return new Reassembler().run(frames);
    }

    /** Groups messages by CAN ID and protocol, in order of first appearance. */
    static List<Stream> streams(List<Message> messages) {
        Map<String, List<Message>> groups = new LinkedHashMap<>();
        for (Message m : messages) {
            groups.computeIfAbsent(m.idText() + "/" + m.protocol(), k -> new ArrayList<>()).add(m);
        }
        List<Stream> out = new ArrayList<>();
        for (List<Message> group : groups.values()) {
            Builder b = new Builder(group.getFirst().canId(), group.getFirst().extended(), group.getFirst().protocol(),
                    UNKNOWN);
            for (Message m : group) {
                for (int i = 0; i < m.bytes().length; i++) {
                    b.addByte(m.bytes()[i], m.frames()[i], m.indexes()[i]);
                }
            }
            Message all = b.toMessage();
            out.add(new Stream(all.canId(), all.extended(), all.protocol(), all.bytes(), all.frames(),
                    all.indexes()));
        }
        return out;
    }

    /** Decodes a TP 2.0 ID field ({@code lo}, {@code hi}); -1 if the "invalid" bit is set. */
    static int decodeTp20Id(int lo, int hi) {
        if ((hi & TP20_ID_INVALID_BIT) != 0) {
            return UNKNOWN;
        }
        return (hi & 0x07) << 8 | lo;
    }

    private static long key(int id, boolean extended) {
        return (extended ? 1L << 32 : 0L) | id;
    }

    /** Per-ID reassembly state. */
    private static final class Reassembler {
        private final List<Message> done = new ArrayList<>();
        private final Map<Long, Builder> tp20 = new HashMap<>();     // claimed IDs -> message in progress (or null)
        private final Map<Long, Builder> isotp = new HashMap<>();

        List<Message> run(List<CanFrame> frames) {
            for (int i = 0; i < frames.size(); i++) {
                CanFrame f = frames.get(i);
                byte[] d = f.data();
                int id = f.id();
                boolean ext = f.isExtended();
                if (!ext && id >= TP20_SETUP_ID && id <= TP20_SETUP_REPLY_LAST_ID) {
                    if (id != TP20_SETUP_ID) {
                        setupReply(d);
                    }
                    continue;
                }
                long key = key(id, ext);
                if (tp20.containsKey(key)) {
                    tp20Frame(key, id, i, d);
                } else {
                    isotpFrame(key, id, ext, i, d);
                }
            }
            tp20.values().forEach(this::finish);
            isotp.values().forEach(this::finish);
            return done;
        }

        private void setupReply(byte[] d) {
            if (d.length < 6 || d[0] != 0 || (d[1] & 0xFF) != TP20_SETUP_POSITIVE) {
                return;
            }
            for (int id : new int[] {decodeTp20Id(d[2] & 0xFF, d[3] & 0xFF), decodeTp20Id(d[4] & 0xFF, d[5] & 0xFF)}) {
                if (id < 0) {
                    continue;
                }
                long key = key(id, false);
                finish(tp20.get(key));
                finish(isotp.remove(key));
                tp20.put(key, null);
            }
        }

        private void tp20Frame(long key, int id, int frame, byte[] d) {
            if (d.length == 0) {
                return;
            }
            int b0 = d[0] & 0xFF;
            int op = b0 >> 4;
            if (op <= 3) {
                Builder b = tp20.get(key);
                if (b == null) {
                    if (d.length < 3) {
                        return;
                    }
                    int length = ((d[1] & 0xFF) << 8 | d[2] & 0xFF) & TP20_LENGTH_MASK;
                    b = new Builder(id, false, Protocol.TP20, length);
                    b.add(frame, d, 3);
                    tp20.put(key, b);
                } else {
                    b.add(frame, d, 1);
                }
                if (op == 1 || op == 3) {
                    finish(b);
                    tp20.put(key, null);
                }
            } else if (b0 == TP20_DISCONNECT) {
                finish(tp20.get(key));
                tp20.put(key, null);
            }
        }

        private void isotpFrame(long key, int id, boolean ext, int frame, byte[] d) {
            if (d.length == 0) {
                return;
            }
            int pci = (d[0] & 0xF0) >> 4;
            switch (pci) {
                case 0 -> {
                    finish(isotp.remove(key));
                    int length = d[0] & 0x0F;
                    int from = 1;
                    if (length == 0 && d.length > 1) {   // CAN FD style escape
                        length = d[1] & 0xFF;
                        from = 2;
                    }
                    if (length == 0) {
                        return;
                    }
                    Builder b = new Builder(id, ext, Protocol.ISOTP, length);
                    b.add(frame, d, from);
                    finish(b);
                }
                case 1 -> {
                    finish(isotp.remove(key));
                    if (d.length < 2) {
                        return;
                    }
                    int length = (d[0] & 0x0F) << 8 | d[1] & 0xFF;
                    int from = 2;
                    if (length == 0 && d.length >= 6) {  // 32-bit length escape
                        length = (d[2] & 0xFF) << 24 | (d[3] & 0xFF) << 16 | (d[4] & 0xFF) << 8 | d[5] & 0xFF;
                        from = 6;
                    }
                    Builder b = new Builder(id, ext, Protocol.ISOTP, length);
                    b.add(frame, d, from);
                    if (b.full()) {
                        finish(b);
                    } else {
                        isotp.put(key, b);
                    }
                }
                case 2 -> {
                    Builder b = isotp.get(key);
                    if (b == null) {
                        b = new Builder(id, ext, Protocol.ISOTP, UNKNOWN);
                        isotp.put(key, b);
                    }
                    b.add(frame, d, 1);
                    if (b.full()) {
                        finish(isotp.remove(key));
                    }
                }
                default -> {
                    // 3 = flow control (no payload); 4..F aren't ISO-TP.
                }
            }
        }

        private void finish(Builder b) {
            if (b != null && b.size > 0) {
                done.add(b.toMessage());
            }
        }
    }

    /** A growing message. */
    private static final class Builder {
        private final int id;
        private final boolean extended;
        private final Protocol protocol;
        private final int expected;
        private byte[] bytes = new byte[16];
        private int[] frames = new int[16];
        private int[] indexes = new int[16];
        private int size;

        Builder(int id, boolean extended, Protocol protocol, int expected) {
            this.id = id;
            this.extended = extended;
            this.protocol = protocol;
            this.expected = expected;
        }

        void add(int frame, byte[] data, int from) {
            for (int i = from; i < data.length && !full(); i++) {
                addByte(data[i], frame, i);
            }
        }

        void addByte(byte value, int frame, int index) {
            if (size == bytes.length) {
                bytes = Arrays.copyOf(bytes, size * 2);
                frames = Arrays.copyOf(frames, size * 2);
                indexes = Arrays.copyOf(indexes, size * 2);
            }
            bytes[size] = value;
            frames[size] = frame;
            indexes[size] = index;
            size++;
        }

        boolean full() {
            return expected != UNKNOWN && size >= expected;
        }

        Message toMessage() {
            return new Message(id, extended, protocol, Arrays.copyOf(bytes, size), Arrays.copyOf(frames, size),
                    Arrays.copyOf(indexes, size), expected != UNKNOWN && size == expected);
        }
    }
}
