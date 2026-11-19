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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>WIRING, DEMO DATA.</b> A scripted fake car for {@link SimulatedAdapter}: answers the real transport at the
 * CAN-frame level so the whole stack (scan → diag → transport → adapter) runs without a car. Used by
 * {@link ScanServiceFactory#demo} and by scan's full-stack tests.
 *
 * <p><b>The data is made up</b> (part numbers, fault codes, serials, mileage: none from a real car). The
 * <b>shape</b> follows the target car as the 2026-11-13 capture showed it, so a demo scan runs the same paths as a
 * real one: the module table and CAN IDs of {@link ModuleAddress}, the request shapes VCDS used and the response
 * layouts diag decodes.
 *
 * <ul>
 *   <li><b>19 Gateway</b> (TP 2.0 {@code 1F}, tx {@code 32E}): answers the installation list {@code 22 04 A1} with
 *       every module below, refuses {@code 1A 9B} ({@code 7F 1A 11}) and is identified with {@code 22} DIDs, like
 *       the real gateway. Fault 01044 (intermittent) with a freeze frame.</li>
 *   <li><b>01 Engine</b> (TP 2.0 {@code 01}, tx {@code 740}): KWP2000, {@code 1A 9B} block. {@code 18 00 FF 00}: P0401 stored twice and P2463; each {@code 12 00 04 04 01} returns the
 *       next stored record.</li>
 *   <li>02 (tx {@code 760}), 08 ({@code 2C}, tx {@code 33C}), 44 ({@code 09}, tx {@code 7A8}): KWP2000, no faults.
 *       08 answers its first DTC read on each channel with {@code 7F 18 21} (busy) to show the back-off.</li>
 *   <li>03 ABS ({@code 03}, tx {@code 790}) and 09 Central Electrics ({@code 20}, tx {@code 338}): refuse
 *       {@code 1A 9B}, identified with {@code 22} DIDs. 03 has 01314. 09 answers each {@code 22} with a response
 *       pending {@code 7F 22 78} first, sent with the TP 2.0 length flag ({@code 80 03}) as the real BCM does.</li>
 *   <li>UDS over ISO-TP: 05, 15, 17, 25, 2B, 77 on their CAN ID pairs. 17 has B1A10-12 and U0115-00 with
 *       {@code 19 06} extended data records (no {@code 19 04}, like VCDS), and the odometer {@code 22 22 03}
 *       ({@value #ODOMETER_KM} km); the rest have none.</li>
 *   <li>2E and 56: listed by the gateway as "cannot be reached", and silent. 04: listed OK, no link.</li>
 * </ul>
 */
final class DemoCar implements SimulatedAdapter.Responder {

    /** 17's made-up odometer: {@code 62 22 03 30 39} = 12345 × 10 km. */
    static final int ODOMETER_KM = 123450;
    private static final int SETUP_ID = 0x200;
    private static final int TESTER_RX = 0x300; // what transport proposes in the setup
    private static final String TIMING_REPLY = "A1 0F 8A FF 4A FF";
    private static final int PADDING = 0xAA;
    /** The adapter keeps every frame for tests; a long-running demo server would grow without bound. */
    private static final long CLEAR_RECORDED_FRAMES_EVERY = 20_000;

    /** A KWP2000 module on TP 2.0. {@code block9B} null = refuses {@code 1A 9B} and answers {@code 22} DIDs. */
    private record KwpModule(int logicalAddress, int testerTx, String block9B, Map<String, String> dids,
            String dtcRequest, String dtcResponse, int busyDtcReadsPerChannel,
            boolean pendingFirst) {
    }

    private static final Map<Integer, KwpModule> KWP = new LinkedHashMap<>();

    static {
        // Engine: 18 00 FF 00. P0401 (04 01) stored twice, MIL on, type 002; P2463 (24 63) intermittent, type 003.
        KWP.put(0x01, new KwpModule(0x01, 0x740, block9B("03L906018XX", "9971", 0x017234, "DEMO R4 2.0L TDI"),
                Map.of(), "18 00 FF 00", "58 03 04 01 E2 04 01 E2 24 63 23", 0, false));
        KWP.put(0x02, new KwpModule(0x02, 0x760, block9B("02E300012D", "0412", 0x000021, "DEMO DSG"),
                Map.of(), "18 02 FF 00", "58 00", 0, false));
        KWP.put(0x03, new KwpModule(0x03, 0x790, null, dids("1K0907379AC", "ESP DEMO MK60"),
                "18 02 FF 00", "58 01 05 22 63", 0, false)); // 01314, type 003
        KWP.put(0x2C, new KwpModule(0x2C, 0x33C, block9B("5K0907044BT", "0117", 0x000140, "DEMO CLIMATRONIC"),
                Map.of(), "18 02 FF 00", "58 00", 1, false));
        KWP.put(0x20, new KwpModule(0x20, 0x338, null, dids("1K0937049N", "BCM DEMO"),
                "18 02 FF 00", "58 00", 0, true));
        KWP.put(0x1F, new KwpModule(0x1F, 0x32E, null, dids("7N0907530AL", "GATEWAY DEMO"),
                "18 02 FF 00", "58 01 04 14 23", 0, false)); // 01044, intermittent, type 003
        KWP.put(0x09, new KwpModule(0x09, 0x7A8, block9B("1K0909144J", "3501", 0x000101, "DEMO EPS"),
                Map.of(), "18 02 FF 00", "58 00", 0, false));
    }

    /**
     * The installation list {@code 62 04 A1 ...}: {@code [VCDS address] [TP 2.0 address] [status] [group]}. Status
     * {@code 01} OK, {@code 05} malfunction, {@code 19} cannot be reached, {@code 00} not installed.
     */
    static final String INSTALLATION_LIST = "62 04 A1"
            + " 01 01 05 10" + " 02 02 01 10" + " 03 03 05 10" + " 04 00 01 15" + " 05 31 01 11" + " 08 2C 01 11"
            + " 09 20 01 11" + " 15 05 01 10" + " 16 00 00 11" + " 17 07 05 11" + " 19 1F 05 10" + " 25 14 01 11"
            + " 2B 30 01 11" + " 2E 54 19 31" + " 44 09 01 10" + " 46 00 00 11" + " 56 52 19 31" + " 77 5A 01 31";

    /** A UDS module over ISO-TP: its CAN IDs and answers by request. */
    private record UdsModule(int requestId, int responseId, Map<String, String> answers) {
    }

    private static final Map<Integer, UdsModule> UDS = new HashMap<>();

    static {
        uds(0x732, 0x79C, "5K0905861", "KESSY DEMO", "59 02 FF");
        uds(0x715, 0x77F, "5C0959655", "AIRBAG DEMO", "59 02 FF");
        Map<String, String> instruments = new HashMap<>(udsBase("5C6920870A", "KOMBI DEMO",
                "59 02 FF 9A 10 12 09 C1 15 00 08"));
        instruments.put("19 06 9A 10 12 FF", "59 06 9A 10 12 09 01 05 02");
        instruments.put("19 06 C1 15 00 FF", "7F 19 31");
        int count = ODOMETER_KM / 10;
        instruments.put("22 22 03", String.format("62 22 03 %02X %02X", count >> 8, count & 0xFF));
        UDS.put(0x714, new UdsModule(0x714, 0x77E, Map.copyOf(instruments)));
        uds(0x711, 0x77B, "5K0953234", "IMMO DEMO", "59 02 FF");
        uds(0x731, 0x79B, "1K0905861", "ELV DEMO", "59 02 FF");
        uds(0x76B, 0x7D5, "3C0035730", "PHONE DEMO", "59 02 FF");
    }

    private static void uds(int request, int response, String part, String name, String dtcs) {
        UDS.put(request, new UdsModule(request, response, udsBase(part, name, dtcs)));
    }

    private static Map<String, String> udsBase(String part, String name, String dtcs) {
        return Map.of(
                "10 03", "50 03 00 32 01 F4",
                "10 01", "50 01 00 32 01 F4",
                "3E 00", "7E 00",
                "22 F1 87", "62 F1 87 " + ascii(part),
                "22 F1 89", "62 F1 89 " + ascii("0101"),
                "22 F1 97", "62 F1 97 " + ascii(name),
                "19 02 AE", dtcs);
    }

    private static Map<String, String> dids(String part, String name) {
        return Map.of(
                "22 F1 87", "62 F1 87 " + ascii(part),
                "22 F1 89", "62 F1 89 " + ascii("0001"),
                "22 F1 97", "62 F1 97 " + ascii(name));
    }

    /**
     * The 46-byte {@code 1A 9B} block diag decodes: part number (12 ASCII), software version (4 ASCII), coding
     * ({@code 03} + 3 bytes), workshop code (6 bytes, all zero here), component (20 ASCII).
     */
    private static String block9B(String part, String software, int coding, String component) {
        return ascii(pad(part, 12)) + " " + ascii(pad(software, 4))
                + String.format(" 03 %02X %02X %02X", coding >> 16, (coding >> 8) & 0xFF, coding & 0xFF)
                + " 00 00 00 00 00 00 " + ascii(pad(component, 20));
    }

    private static String pad(String text, int length) {
        return String.format("%-" + length + "s", text).substring(0, length);
    }

    /** The open TP 2.0 channel (transport opens one at a time). */
    private static final class Channel {
        final KwpModule module;
        int moduleSeq;
        ByteArrayOutputStream request;
        int requestLength;
        int busyLeft;
        final Map<String, Integer> freezeFramesRead = new HashMap<>();

        Channel(KwpModule module) {
            this.module = module;
            this.busyLeft = module.busyDtcReadsPerChannel();
        }
    }

    private Channel channel;
    private final Deque<byte[]> pendingConsecutiveFrames = new ArrayDeque<>();
    private int pendingResponseId;
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
        UdsModule uds = UDS.get(sent.id());
        if (uds != null) {
            return isoTp(uds, sent);
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
            return List.of(); // not installed, or not reachable (2E, 56), or UDS only
        }
        channel = new Channel(m);
        int tx = m.testerTx();
        return List.of(Reply.now(CanFrame.standard(SETUP_ID + m.logicalAddress(), 0x00, 0xD0, TESTER_RX & 0xFF,
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
            String request = Hex.format(ch.request.toByteArray());
            ch.request = null;
            if (ch.module.pendingFirst() && request.startsWith("22 ")) {
                // "Response pending" first, with the length flag (bit 15) set as the real BCM sends it.
                sendMessage(ch, Hex.parse("7F 22 78"), 0x8000, replies);
            }
            sendMessage(ch, Hex.parse(kwpAnswer(ch, request)), 0, replies);
        }
        return replies;
    }

    /** One module → tester message as TP 2.0 data packets; the last one asks for an ACK. */
    private static void sendMessage(Channel ch, byte[] message, int lengthFlags, List<Reply> replies) {
        List<byte[]> chunks = segment(message, lengthFlags);
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

    /** TP 2.0 packet data: the first carries a 2-byte length (with flag bits) and up to 5 bytes, later ones 7. */
    private static List<byte[]> segment(byte[] message, int lengthFlags) {
        List<byte[]> out = new ArrayList<>();
        int first = Math.min(5, message.length);
        byte[] head = new byte[2 + first];
        int length = message.length | lengthFlags;
        head[0] = (byte) (length >> 8);
        head[1] = (byte) length;
        System.arraycopy(message, 0, head, 2, first);
        out.add(head);
        for (int offset = first; offset < message.length; offset += 7) {
            out.add(Arrays.copyOfRange(message, offset, Math.min(offset + 7, message.length)));
        }
        return out;
    }

    private static String kwpAnswer(Channel ch, String request) {
        KwpModule m = ch.module;
        if (request.equals("22 04 A1")) {
            return m.logicalAddress() == 0x1F ? INSTALLATION_LIST : "7F 22 31";
        }
        if (request.startsWith("22 ")) {
            return m.dids().getOrDefault(request, "7F 22 31");
        }
        if (request.equals(m.dtcRequest())) {
            if (ch.busyLeft > 0) {
                ch.busyLeft--;
                return "7F 18 21";
            }
            return m.dtcResponse();
        }
        if (request.startsWith("21 ")) {
            return measuringBlock(m.logicalAddress(), Integer.parseInt(request.substring(3), 16));
        }
        return switch (request) {
            case "10 89" -> "50 89";
            case "3E" -> "7E";
            case "1A 9B" -> m.block9B() == null ? "7F 1A 11" : "5A 9B " + m.block9B();
            default -> request.startsWith("12 00 04 ") ? kwpFreezeFrame(ch, request.substring(9))
                    : String.format("7F %s 11", request.substring(0, 2));
        };
    }

    /** Counts measuring-block reads so the demo values move a little between reads. */
    private static final java.util.concurrent.atomic.AtomicInteger MEASURING_READS = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * {@code 21 gg}: made-up measuring blocks in the layout of the real engine (8 triplets, only 1–4 shown):
     * engine 001 (RPM, injection quantity, rail pressure, coolant), 007 (fuel, oil = empty {@code 25 00 00}, intake,
     * coolant), 011 (RPM, boost specified / actual, duty cycle); ABS (03, no demo labels) 001 and 005. Every other
     * group: {@code 7F 21 31}. RPM and boost move a little from read to read.
     */
    private static String measuringBlock(int logicalAddress, int group) {
        int n = MEASURING_READS.getAndIncrement();
        int wobble = n % 5; // 0..4
        String rest = " 25 00 00 25 00 00 25 00 00 25 00 00";
        if (logicalAddress == 0x01) {
            return switch (group) {
                // 01 b = 0x74.. : RPM 0.2*a*b with a = 0x73 (115): 0x24 + wobble -> 828..920 /min
                case 1 -> String.format("61 01 01 73 %02X 27 46 17 64 50 29 05 09 B0", 0x24 + wobble) + rest;
                case 7 -> "61 07 05 09 8C 25 00 00 05 09 82 05 09 B0" + rest;
                case 11 -> String.format("61 0B 01 73 %02X 60 79 53 60 79 %02X 21 FF 40", 0x24 + wobble, 0x50 + wobble)
                        + rest;
                default -> "7F 21 31";
            };
        }
        if (logicalAddress == 0x03) {
            return switch (group) {
                case 1 -> "61 01 07 64 00 07 64 00 07 64 00 07 64 00" + rest; // four wheel speeds, 0.0 km/h
                case 5 -> "61 05 06 4C B6 25 00 00 25 00 00 25 00 00" + rest;  // 13.83 V, then empty fields
                default -> "7F 21 31";
            };
        }
        return "7F 21 31";
    }

    /**
     * {@code 12 00 04 [hi] [lo]}: a made-up record in the layout diag decodes (status, priority 2, frequency 1,
     * reset counter 255, 12345 km + 1000 km per earlier record of the same code, 18:02:50, no environment values)
     * for the next stored entry of that code, else {@code 7F 12 31}. Like the real engine, repeating the request
     * for a code stored several times returns the next record.
     */
    private static String kwpFreezeFrame(Channel ch, String code) {
        String dtcs = ch.module.dtcResponse();
        int wanted = ch.freezeFramesRead.getOrDefault(code, 0);
        int seen = 0;
        for (int at = 6; at + 8 <= dtcs.length(); at += 9) { // "58 nn " then "hi lo st " records
            if (dtcs.startsWith(code, at)) {
                if (seen++ < wanted) {
                    continue;
                }
                ch.freezeFramesRead.put(code, wanted + 1);
                String status = dtcs.substring(at + 6, at + 8);
                int km = 12345 + 1000 * wanted;
                return String.format("52 00 6C 01 %s 02 01 FF %02X %02X %02X 00 01 FF BF 20 B1 04 %s", status,
                        km >> 16, (km >> 8) & 0xFF, km & 0xFF, code);
            }
        }
        return "7F 12 31";
    }

    // ------------------------------------------------------------------ ISO-TP (UDS modules)

    private List<Reply> isoTp(UdsModule m, CanFrame sent) {
        int pci = sent.dataByte(0) >> 4;
        if (pci == 0x3) { // our flow control: send the rest of the response
            List<Reply> out = new ArrayList<>();
            while (!pendingConsecutiveFrames.isEmpty()) {
                out.add(Reply.now(new CanFrame(pendingResponseId, false, pendingConsecutiveFrames.poll())));
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
        byte[] response = Hex.parse(m.answers().getOrDefault(request,
                String.format("7F %s %s", request.substring(0, 2), request.startsWith("22") ? "31" : "11")));
        pendingConsecutiveFrames.clear();
        pendingResponseId = m.responseId();
        if (response.length <= 7) {
            byte[] frame = padded();
            frame[0] = (byte) response.length;
            System.arraycopy(response, 0, frame, 1, response.length);
            return List.of(Reply.now(new CanFrame(m.responseId(), false, frame)));
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
        return List.of(Reply.now(new CanFrame(m.responseId(), false, first)));
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
