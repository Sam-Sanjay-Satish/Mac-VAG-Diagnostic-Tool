package io.github.samsanjaysatish.macvagdiag.scan;

import io.github.samsanjaysatish.macvagdiag.can.CanFrame;
import io.github.samsanjaysatish.macvagdiag.can.Hex;
import io.github.samsanjaysatish.macvagdiag.can.SimulatedAdapter;
import io.github.samsanjaysatish.macvagdiag.can.SimulatedAdapter.Reply;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>WIRING, DEMO DATA.</b> A scripted fake car for {@link SimulatedAdapter}: answers the real transport at the
 * CAN-frame level so the whole stack (scan → diag → transport → adapter) runs without a car. Used by
 * {@link ScanServiceFactory#demo} and by scan's full-stack tests.
 *
 * <p><b>Everything here is made up</b> (VIN {@value #VIN}, part numbers, fault codes) and follows the same
 * UNCONFIRMED protocol assumptions as transport and diag, because there is no capture yet. It shows what a scan
 * looks like; it says nothing about a real Jetta.
 *
 * <ul>
 *   <li>01 Engine: UDS over ISO-TP {@code 7E0/7E8} only (TP 2.0 address 01 is silent, so scan's link fallback
 *       runs), faults P0401 and P2463 with freeze-frame records.</li>
 *   <li>02, 03, 08, 09, 15, 17, 19: KWP2000 over TP 2.0. 03 and 19 have a fault each; 08 answers its first
 *       DTC read on each channel with {@code 7F 18 21} (busy), to show the back-off; 17 reports the VIN.</li>
 *   <li>16, 25, 44, 46, 56: not installed (no answer).</li>
 * </ul>
 */
final class DemoCar implements SimulatedAdapter.Responder {

    static final String VIN = "DEMOVIN0000000000";
    private static final int SETUP_ID = 0x200;
    private static final int TESTER_RX = 0x300; // what transport proposes in the setup (UNCONFIRMED there)
    private static final int FIRST_TESTER_TX = 0x740;
    private static final int UDS_REQUEST_ID = 0x7E0;
    private static final int UDS_RESPONSE_ID = 0x7E8;
    private static final String TIMING_REPLY = "A1 0F 8A FF 0A FF";
    private static final int PADDING = 0xAA;
    /** The adapter keeps every frame for tests; a long-running demo server would grow without bound. */
    private static final long CLEAR_RECORDED_FRAMES_EVERY = 20_000;

    private record KwpModule(int address, int testerTx, String identification, boolean reportsVin,
            String dtcResponse, int busyDtcReadsPerChannel) {
    }

    private static final Map<Integer, KwpModule> KWP = new LinkedHashMap<>();

    static {
        kwp(0x02, "02E300012D  DSG DEMO       0412", false, "58 00", 0);
        kwp(0x03, "1K0907379AC ESP DEMO MK60  0004", false, "58 01 05 22 23", 0); // 01314, symptom 35
        kwp(0x08, "5K0907044BT CLIMA DEMO     0117", false, "58 00", 1);
        kwp(0x09, "1K0937049N  BCM DEMO       0811", false, "58 00", 0);
        kwp(0x15, "5C0959655   AIRBAG DEMO    0202", false, "58 00", 0);
        kwp(0x17, "5C6920870A  KOMBI DEMO     0303", true, "58 00", 0);
        kwp(0x19, "7N0907530AL GATEWAY DEMO   0080", false, "58 01 04 14 A3", 0); // 01044, intermittent
    }

    private static void kwp(int address, String id, boolean vin, String dtcs, int busy) {
        KWP.put(address, new KwpModule(address, FIRST_TESTER_TX + KWP.size(), id, vin, dtcs, busy));
    }

    private static final Map<String, String> ENGINE = Map.ofEntries(
            Map.entry("10 01", "50 01 00 32 01 F4"),
            Map.entry("10 03", "50 03 00 32 01 F4"),
            Map.entry("3E 00", "7E 00"),
            Map.entry("22 F1 87", "62 F1 87 " + ascii("03L906018XX")),
            Map.entry("22 F1 89", "62 F1 89 " + ascii("9971")),
            Map.entry("22 F1 91", "62 F1 91 " + ascii("03L907309X")),
            Map.entry("22 F1 97", "62 F1 97 " + ascii("DEMO R4 2.0L TDI")),
            Map.entry("22 06 00", "62 06 00 00 00 17 2A 00 00"),
            Map.entry("22 F1 90", "62 F1 90 " + ascii(VIN)),
            Map.entry("19 02 FF", "59 02 FF 04 01 00 2F 24 63 00 08"),
            Map.entry("19 04 04 01 00 FF", "59 04 04 01 00 2F 01 01 F4 0D 2A"),
            Map.entry("19 06 04 01 00 FF", "59 06 04 01 00 2F 01 05 02"),
            Map.entry("19 04 24 63 00 FF", "59 04 24 63 00 08"),
            Map.entry("19 06 24 63 00 FF", "7F 19 31"));

    /** The open TP 2.0 channel (transport opens one at a time). */
    private static final class Channel {
        final KwpModule module;
        int moduleSeq;
        ByteArrayOutputStream request;
        int requestLength;
        int busyLeft;

        Channel(KwpModule module) {
            this.module = module;
            this.busyLeft = module.busyDtcReadsPerChannel();
        }
    }

    private Channel channel;
    private final Deque<byte[]> pendingConsecutiveFrames = new ArrayDeque<>();
    private SimulatedAdapter adapter;
    private long framesSeen;

    /** Lets the demo clear the adapter's record of sent frames now and then (long-running demo server). */
    void attach(SimulatedAdapter adapter) {
        this.adapter = adapter;
    }

    @Override
    public synchronized List<Reply> respond(CanFrame sent) {
        if (adapter != null && ++framesSeen % CLEAR_RECORDED_FRAMES_EVERY == 0) {
            adapter.clearSentFrames();
        }
        if (sent.isExtended() || sent.length() == 0) {
            return List.of();
        }
        if (sent.id() == SETUP_ID) {
            return setup(sent);
        }
        if (sent.id() == UDS_REQUEST_ID) {
            return isoTp(sent);
        }
        if (channel != null && sent.id() == channel.module.testerTx()) {
            return tp20(channel, sent);
        }
        return List.of();
    }

    // ------------------------------------------------------------------ TP 2.0

    private List<Reply> setup(CanFrame sent) {
        if (sent.length() < 7 || sent.dataByte(1) != 0xC0) {
            return List.of();
        }
        KwpModule m = KWP.get(sent.dataByte(0));
        if (m == null) {
            return List.of(); // not installed
        }
        channel = new Channel(m);
        int tx = m.testerTx();
        return List.of(Reply.now(CanFrame.standard(SETUP_ID + m.address(), 0x00, 0xD0, TESTER_RX & 0xFF,
                TESTER_RX >> 8, tx & 0xFF, tx >> 8, 0x01)));
    }

    private List<Reply> tp20(Channel ch, CanFrame sent) {
        int b0 = sent.dataByte(0);
        if (b0 == 0xA0 || b0 == 0xA3) {
            return List.of(Reply.now(CanFrame.standardHex(TESTER_RX, TIMING_REPLY)));
        }
        if (b0 == 0xA8) {
            channel = null;
            return List.of(Reply.now(CanFrame.standardHex(TESTER_RX, "A8")));
        }
        int op = b0 >> 4;
        if (op > 3) {
            return List.of(); // the tester's ACKs
        }
        byte[] data = sent.data();
        int from = 1;
        if (ch.request == null) {
            if (data.length < 3) {
                return List.of();
            }
            ch.requestLength = (Byte.toUnsignedInt(data[1]) << 8) | Byte.toUnsignedInt(data[2]);
            ch.request = new ByteArrayOutputStream();
            from = 3;
        }
        ch.request.write(data, from, data.length - from);
        List<Reply> replies = new ArrayList<>();
        if (op == 0 || op == 1) {
            replies.add(Reply.now(CanFrame.standard(TESTER_RX, 0xB0 | ((b0 + 1) & 0x0F))));
        }
        if ((op == 1 || op == 3) && ch.request.size() == ch.requestLength) {
            byte[] response = Hex.parse(kwpAnswer(ch, Hex.format(ch.request.toByteArray())));
            ch.request = null;
            List<byte[]> chunks = segment(response);
            for (int i = 0; i < chunks.size(); i++) {
                int dataOp = i == chunks.size() - 1 ? 0x1 : 0x2;
                byte[] chunk = chunks.get(i);
                byte[] frame = new byte[1 + chunk.length];
                frame[0] = (byte) ((dataOp << 4) | (ch.moduleSeq & 0x0F));
                System.arraycopy(chunk, 0, frame, 1, chunk.length);
                ch.moduleSeq = (ch.moduleSeq + 1) & 0x0F;
                replies.add(Reply.now(new CanFrame(TESTER_RX, false, frame)));
            }
        }
        return replies;
    }

    /** TP 2.0 packet data: the first carries a 2-byte length and up to 5 bytes, later ones 7. */
    private static List<byte[]> segment(byte[] message) {
        List<byte[]> out = new ArrayList<>();
        int first = Math.min(5, message.length);
        byte[] head = new byte[2 + first];
        head[0] = (byte) (message.length >> 8);
        head[1] = (byte) message.length;
        System.arraycopy(message, 0, head, 2, first);
        out.add(head);
        for (int offset = first; offset < message.length; offset += 7) {
            out.add(Arrays.copyOfRange(message, offset, Math.min(offset + 7, message.length)));
        }
        return out;
    }

    private static String kwpAnswer(Channel ch, String request) {
        KwpModule m = ch.module;
        return switch (request) {
            case "10 89" -> "50 89";
            case "3E" -> "7E";
            case "1A 9B" -> "5A 9B " + ascii(m.identification());
            case "1A 90" -> m.reportsVin() ? "5A 90 " + ascii(VIN) : "7F 1A 12";
            case "18 02" -> {
                if (ch.busyLeft > 0) {
                    ch.busyLeft--;
                    yield "7F 18 21";
                }
                yield m.dtcResponse();
            }
            default -> String.format("7F %s 11", request.substring(0, 2));
        };
    }

    // ------------------------------------------------------------------ ISO-TP (engine)

    private List<Reply> isoTp(CanFrame sent) {
        int pci = sent.dataByte(0) >> 4;
        if (pci == 0x3) { // our flow control: send the rest of the response
            List<Reply> out = new ArrayList<>();
            while (!pendingConsecutiveFrames.isEmpty()) {
                out.add(Reply.now(new CanFrame(UDS_RESPONSE_ID, false, pendingConsecutiveFrames.poll())));
            }
            return out;
        }
        int length = sent.dataByte(0) & 0x0F;
        if (pci != 0 || length == 0 || length > 7) {
            return List.of();
        }
        String request = Hex.format(Arrays.copyOfRange(sent.data(), 1, 1 + length));
        if (request.equals("3E 80")) {
            return List.of(); // suppressed positive response
        }
        byte[] response = Hex.parse(ENGINE.getOrDefault(request, String.format("7F %s 11", request.substring(0, 2))));
        pendingConsecutiveFrames.clear();
        if (response.length <= 7) {
            byte[] frame = padded();
            frame[0] = (byte) response.length;
            System.arraycopy(response, 0, frame, 1, response.length);
            return List.of(Reply.now(new CanFrame(UDS_RESPONSE_ID, false, frame)));
        }
        byte[] first = padded();
        first[0] = (byte) (0x10 | (response.length >> 8));
        first[1] = (byte) response.length;
        System.arraycopy(response, 0, first, 2, 6);
        int seq = 1;
        for (int offset = 6; offset < response.length; offset += 7) {
            byte[] cf = padded();
            cf[0] = (byte) (0x20 | (seq & 0x0F));
            System.arraycopy(response, offset, cf, 1, Math.min(7, response.length - offset));
            pendingConsecutiveFrames.add(cf);
            seq++;
        }
        return List.of(Reply.now(new CanFrame(UDS_RESPONSE_ID, false, first)));
    }

    private static byte[] padded() {
        byte[] frame = new byte[8];
        Arrays.fill(frame, (byte) PADDING);
        return frame;
    }

    private static String ascii(String text) {
        return Hex.format(text.getBytes(StandardCharsets.US_ASCII));
    }
}
