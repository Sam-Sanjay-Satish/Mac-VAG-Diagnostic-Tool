package io.github.samsanjaysatish.macvagdiag.transport;

import io.github.samsanjaysatish.macvagdiag.can.CanFrame;
import io.github.samsanjaysatish.macvagdiag.can.Capture;
import io.github.samsanjaysatish.macvagdiag.can.Hex;
import io.github.samsanjaysatish.macvagdiag.can.SimulatedAdapter;
import io.github.samsanjaysatish.macvagdiag.can.SimulatedAdapter.Reply;
import io.github.samsanjaysatish.macvagdiag.transport.CaptureExchanges.Exchange;
import io.github.samsanjaysatish.macvagdiag.transport.CaptureExchanges.Link;
import io.github.samsanjaysatish.macvagdiag.transport.CaptureExchanges.Message;
import io.github.samsanjaysatish.macvagdiag.transport.CaptureExchanges.Session;
import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Test fixture: the real car, as far as the capture shows it. A {@link SimulatedAdapter.Responder} that answers
 * <b>our</b> requests with the module responses recorded when VCDS sent the same request bytes to the same module.
 * Our request set and order differ from VCDS's (fewer identification reads, no {@code 31 B8}, no gateway channel
 * before each module), so a frame-for-frame replay can't follow an Auto-Scan; this does.
 *
 * <ul>
 *   <li><b>Matching:</b> by module link ({@link Link}) and the whole request payload. A request VCDS sent several
 *       times gets the recorded answers in capture order (e.g. {@code 12 00 04 04 01} for a code stored twice:
 *       first record, then the second), and wraps around after the last. The cursor restarts at each TP 2.0 channel
 *       setup, like the engine's per-session freeze-frame cursor (both VCDS sessions read the records in the same
 *       order). Several captures are merged in order.</li>
 *   <li><b>Responses as captured:</b> every recorded message of the answer, including {@code 7F xx 78} with the TP
 *       2.0 length field's flag bit exactly as recorded ({@code 80 03}). Module-initiated {@code A3}s are not
 *       replayed. Answers come without delay (tests use fake time).</li>
 *   <li><b>Framing is ours, not copied:</b> TP 2.0 setup reply and {@code A1} timing as recorded for the module;
 *       our own sequence numbers; ACK {@code B[seq+1]} for the tester's {@code 0x}/{@code 1x} packets; block size
 *       15 with a {@code 0x} packet at each 15th packet, then a wait for the tester's ACK; {@code A3} → recorded
 *       {@code A1}; {@code A8} → {@code A8}. ISO-TP single / first + consecutive frames, padded {@code AA} as the
 *       ECUs do, consecutive frames after the tester's flow control.</li>
 *   <li><b>Derived answers:</b> VCDS read some DIDs only inside its six-DID request
 *       ({@code 22 F1 87 F1 89 F1 91 F1 A3 F1 A5 F1 DF} on 03, 09 and 19; we may not send that form). A single
 *       {@code 22 [DID]} with no recording of its own is answered with {@code 62 [DID] [data]} cut out of that
 *       recorded multi-DID response (split at each requested DID in order), and listed in {@link #derived()}.</li>
 *   <li><b>Requests the capture never saw</b> are recorded in {@link #unseen()}. {@code 22 [DID]} gets
 *       {@code 7F 22 31} (what these modules answered for every DID they don't have). {@code 1A [option]} gets
 *       {@code 7F 1A 11} on modules that refused {@code 1A} entirely on the capture (03, 09, 19), else
 *       {@code 7F 1A 12} (what 44 answered to an option it lacks). Anything else gets no answer.</li>
 *   <li>Setups to addresses no module answered on the capture, and IDs it doesn't know, get no answer.</li>
 * </ul>
 * Not thread-safe beyond {@link SimulatedAdapter}'s own locking (it calls {@link #respond} under its lock).
 */
public final class CaptureEcu implements SimulatedAdapter.Responder {

    /** A request this ECU had no recording for, and what it answered. */
    public record Unseen(Link link, String request, String answer) {
        @Override
        public String toString() {
            return link + " " + request + " -> " + answer;
        }
    }

    /** A request this ECU answered from the capture. */
    public record Answered(Link link, String request, List<String> responses) {
    }

    private static final int TP20_RX = 0x300;
    private static final int BLOCK_SIZE = 15;
    private static final int ISO_TP_PADDING = 0xAA;

    /** Recorded data for one module. */
    private static final class Recorded {
        final Link link;
        CanFrame setupReply;
        CanFrame timingReply;
        final Map<String, List<List<Message>>> answers = new LinkedHashMap<>();
        /** Single-DID answers cut out of multi-DID responses: request → {@code 62 [DID] [data]}. */
        final Map<String, String> derived = new LinkedHashMap<>();
        boolean refuses1A;

        Recorded(Link link) {
            this.link = link;
        }
    }

    private final Map<Link, Recorded> modules = new LinkedHashMap<>();
    private final List<Unseen> unseen = new ArrayList<>();
    private final List<Answered> answered = new ArrayList<>();
    private final List<Answered> derivedAnswers = new ArrayList<>();
    private final List<String> protocolErrors = new ArrayList<>();
    private final Map<Link, Map<String, Integer>> cursors = new HashMap<>();

    // The open TP 2.0 channel (transport opens one at a time).
    private Recorded tp20;
    private int tp20Tx;
    private int testerSeq;
    private int moduleSeq;
    private ByteArrayOutputStream request;
    private int requestLength;
    private final Deque<byte[]> pausedPackets = new ArrayDeque<>();
    private int ackAwaited = -1;

    // ISO-TP consecutive frames waiting for the tester's flow control.
    private final Deque<byte[]> pendingConsecutive = new ArrayDeque<>();
    private int pendingConsecutiveId;

    private CaptureEcu() {
    }

    /** An ECU answering from {@code captures}, merged in order. */
    public static CaptureEcu of(Capture... captures) {
        CaptureEcu ecu = new CaptureEcu();
        for (Capture c : captures) {
            ecu.add(CaptureExchanges.of(c));
        }
        return ecu;
    }

    private void add(CaptureExchanges.Result result) {
        for (Session s : result.sessions()) {
            Recorded r = modules.computeIfAbsent(s.link(), Recorded::new);
            if (r.setupReply == null && s.setupReply().isPresent()) {
                r.setupReply = s.setupReply().get();
            }
            if (r.timingReply == null && s.timingReply().isPresent()) {
                r.timingReply = s.timingReply().get();
            }
            for (Exchange e : s.exchanges()) {
                if (e.responses().isEmpty()) {
                    continue; // e.g. OBD on 7E0 with nothing back; nothing to replay
                }
                r.answers.computeIfAbsent(e.requestHex(), k -> new ArrayList<>()).add(e.responses());
                if (e.requestHex().startsWith("1A ") && e.responses().getLast().hex().equals("7F 1A 11")) {
                    r.refuses1A = true;
                }
                splitMultiDid(r, e.request().payload(), e.responses().getLast().payload());
            }
        }
    }

    /**
     * {@code 22 [DID1] [DID2] ...} → {@code 62 [DID1] [data] [DID2] [data] ...}: each DID's data runs up to the next
     * requested DID (searched in order, at least one data byte each), the last one to the end.
     */
    private static void splitMultiDid(Recorded r, byte[] request, byte[] response) {
        if (request.length < 5 || request[0] != 0x22 || (request.length - 1) % 2 != 0 || response.length < 3
                || response[0] != 0x62) {
            return;
        }
        int dids = (request.length - 1) / 2;
        int[] at = new int[dids + 1];
        int from = 1;
        for (int i = 0; i < dids; i++) {
            int p = find(response, request[1 + 2 * i], request[2 + 2 * i], from);
            if (p < 0) {
                return; // not the layout we expect: derive nothing
            }
            at[i] = p;
            from = p + 3;
        }
        at[dids] = response.length;
        for (int i = 0; i < dids; i++) {
            byte[] single = new byte[1 + at[i + 1] - at[i]];
            single[0] = 0x62;
            System.arraycopy(response, at[i], single, 1, at[i + 1] - at[i]);
            r.derived.putIfAbsent(Hex.format(new byte[] {0x22, request[1 + 2 * i], request[2 + 2 * i]}), Hex.format(single));
        }
    }

    private static int find(byte[] b, byte hi, byte lo, int from) {
        for (int i = from; i + 1 < b.length; i++) {
            if (b[i] == hi && b[i + 1] == lo) {
                return i;
            }
        }
        return -1;
    }

    /** Single-DID requests answered from a multi-DID recording, in order. */
    public synchronized List<Answered> derived() {
        return List.copyOf(derivedAnswers);
    }

    /** The modules this ECU knows. */
    public List<Link> links() {
        return List.copyOf(modules.keySet());
    }

    /** Requests answered without a recording (see the class comment), in order. */
    public synchronized List<Unseen> unseen() {
        return List.copyOf(unseen);
    }

    /** Requests answered from the capture, in order. */
    public synchronized List<Answered> answered() {
        return List.copyOf(answered);
    }

    /** Framing errors in what the tester sent (wrong sequence numbers, short packets). Empty in a good run. */
    public synchronized List<String> protocolErrors() {
        return List.copyOf(protocolErrors);
    }

    @Override
    public synchronized List<Reply> respond(CanFrame sent) {
        if (sent.isExtended() || sent.length() == 0) {
            return List.of();
        }
        if (sent.id() == CaptureExchanges.TP20_SETUP_ID) {
            return setup(sent);
        }
        if (tp20 != null && sent.id() == tp20Tx) {
            return tp20Frame(sent);
        }
        Integer response = CaptureExchanges.ISO_TP_PAIRS.get(sent.id());
        if (response != null) {
            Recorded r = modules.get(new Link.IsoTp(sent.id(), response));
            return r == null ? List.of() : isoTp(r, sent);
        }
        return List.of();
    }

    // ------------------------------------------------------------------ TP 2.0

    private List<Reply> setup(CanFrame sent) {
        if (sent.length() < 7 || sent.dataByte(1) != 0xC0) {
            return List.of();
        }
        Recorded r = modules.get(new Link.Tp20(sent.dataByte(0)));
        if (r == null || r.setupReply == null) {
            return List.of();
        }
        tp20 = r;
        tp20Tx = Tp20Channel.decodeId(r.setupReply.dataByte(4), r.setupReply.dataByte(5));
        testerSeq = 0;
        moduleSeq = 0;
        request = null;
        pausedPackets.clear();
        ackAwaited = -1;
        cursors.remove(r.link);
        return List.of(Reply.now(r.setupReply));
    }

    private List<Reply> tp20Frame(CanFrame sent) {
        int b0 = sent.dataByte(0);
        switch (b0) {
            case 0xA0, 0xA3 -> {
                return tp20.timingReply == null ? List.of() : List.of(Reply.now(tp20.timingReply));
            }
            case 0xA8 -> {
                tp20 = null;
                return List.of(Reply.now(CanFrame.standard(TP20_RX, 0xA8)));
            }
            case 0xA1 -> {
                return List.of(); // the tester's answer to a module A3 (we don't send any)
            }
            default -> {
            }
        }
        int op = b0 >> 4;
        int seq = b0 & 0x0F;
        if (op == 0xB) {
            if (ackAwaited >= 0 && seq == ackAwaited) {
                ackAwaited = -1;
                List<Reply> out = new ArrayList<>();
                emitPackets(out);
                return out;
            }
            return List.of();
        }
        if (op > 3) {
            return List.of();
        }
        if (seq != testerSeq) {
            protocolErrors.add(String.format("%s: tester packet sequence %X, expected %X: %s", tp20.link, seq, testerSeq, sent));
        }
        testerSeq = (seq + 1) & 0x0F;
        byte[] data = sent.data();
        int from = 1;
        if (request == null) {
            if (data.length < 3) {
                protocolErrors.add(tp20.link + ": first packet without a length: " + sent);
                return List.of();
            }
            requestLength = (Byte.toUnsignedInt(data[1]) << 8) | Byte.toUnsignedInt(data[2]);
            request = new ByteArrayOutputStream();
            from = 3;
        }
        request.write(data, from, data.length - from);
        List<Reply> out = new ArrayList<>();
        if (op == 0 || op == 1) {
            out.add(Reply.now(CanFrame.standard(TP20_RX, 0xB0 | testerSeq)));
        }
        if (op == 1 || op == 3) {
            if (request.size() != requestLength) {
                protocolErrors.add(String.format("%s: request length %d, got %d bytes", tp20.link, requestLength, request.size()));
            }
            String hex = Hex.format(request.toByteArray());
            request = null;
            for (Message m : answer(tp20, hex)) {
                queueTp20Message(m.payload(), m.lengthFlags());
            }
            emitPackets(out);
        }
        return out;
    }

    /** Queues one module → tester message as packets: {@code 2x}, a {@code 0x} at each 15th packet, {@code 1x} last. */
    private void queueTp20Message(byte[] message, int lengthFlags) {
        List<byte[]> chunks = new ArrayList<>();
        int first = Math.min(Tp20Channel.FIRST_PACKET_DATA_BYTES, message.length);
        byte[] head = new byte[2 + first];
        int length = message.length | lengthFlags;
        head[0] = (byte) (length >> 8);
        head[1] = (byte) length;
        System.arraycopy(message, 0, head, 2, first);
        chunks.add(head);
        for (int offset = first; offset < message.length; offset += Tp20Channel.PACKET_DATA_BYTES) {
            chunks.add(Arrays.copyOfRange(message, offset, Math.min(offset + Tp20Channel.PACKET_DATA_BYTES, message.length)));
        }
        for (int i = 0; i < chunks.size(); i++) {
            int op = i == chunks.size() - 1 ? 0x1 : ((i + 1) % BLOCK_SIZE == 0 ? 0x0 : 0x2);
            byte[] chunk = chunks.get(i);
            byte[] packet = new byte[1 + chunk.length];
            packet[0] = (byte) (op << 4); // sequence filled in when sent
            System.arraycopy(chunk, 0, packet, 1, chunk.length);
            pausedPackets.add(packet);
        }
    }

    /** Sends queued packets until one that expects an ACK in the middle of a message ({@code 0x}). */
    private void emitPackets(List<Reply> out) {
        while (!pausedPackets.isEmpty()) {
            byte[] packet = pausedPackets.poll();
            int op = (packet[0] >> 4) & 0x0F;
            packet[0] = (byte) ((op << 4) | moduleSeq);
            moduleSeq = (moduleSeq + 1) & 0x0F;
            out.add(Reply.now(new CanFrame(TP20_RX, false, packet)));
            if (op == 0x0) {
                ackAwaited = moduleSeq;
                return;
            }
        }
    }

    // ------------------------------------------------------------------ ISO-TP

    private List<Reply> isoTp(Recorded r, CanFrame sent) {
        Link.IsoTp link = (Link.IsoTp) r.link;
        int pci = sent.dataByte(0) >> 4;
        if (pci == 0x3) {
            List<Reply> out = new ArrayList<>();
            while (!pendingConsecutive.isEmpty() && pendingConsecutiveId == link.responseId()) {
                out.add(Reply.now(new CanFrame(link.responseId(), false, pendingConsecutive.poll())));
            }
            return out;
        }
        int length = sent.dataByte(0) & 0x0F;
        if (pci != 0 || length == 0 || length > sent.length() - 1) {
            if (!(sent.length() == 1 && sent.dataByte(0) == 0xA8)) {
                protocolErrors.add(link + ": not a single-frame request: " + sent);
            }
            return List.of();
        }
        String hex = Hex.format(Arrays.copyOfRange(sent.data(), 1, 1 + length));
        List<Reply> out = new ArrayList<>();
        pendingConsecutive.clear();
        for (Message m : answer(r, hex)) {
            isoTpMessage(link.responseId(), m.payload(), out);
        }
        return out;
    }

    private void isoTpMessage(int id, byte[] message, List<Reply> out) {
        if (message.length <= 7) {
            byte[] frame = padded();
            frame[0] = (byte) message.length;
            System.arraycopy(message, 0, frame, 1, message.length);
            out.add(Reply.now(new CanFrame(id, false, frame)));
            return;
        }
        byte[] first = padded();
        first[0] = (byte) (0x10 | (message.length >> 8));
        first[1] = (byte) message.length;
        System.arraycopy(message, 0, first, 2, 6);
        out.add(Reply.now(new CanFrame(id, false, first)));
        int seq = 1;
        for (int offset = 6; offset < message.length; offset += 7) {
            byte[] cf = padded();
            cf[0] = (byte) (0x20 | (seq & 0x0F));
            System.arraycopy(message, offset, cf, 1, Math.min(7, message.length - offset));
            pendingConsecutive.add(cf);
            seq++;
        }
        pendingConsecutiveId = id;
    }

    private static byte[] padded() {
        byte[] frame = new byte[8];
        Arrays.fill(frame, (byte) ISO_TP_PADDING);
        return frame;
    }

    // ------------------------------------------------------------------ answers

    private List<Message> answer(Recorded r, String request) {
        List<List<Message>> recorded = r.answers.get(request);
        if (recorded == null && r.derived.containsKey(request)) {
            String hex = r.derived.get(request);
            derivedAnswers.add(new Answered(r.link, request, List.of(hex)));
            return List.of(new Message(false, hex, Hex.parse(hex).length, java.time.Instant.EPOCH, -1));
        }
        if (recorded == null) {
            Optional<String> nrc = unseenAnswer(r, request);
            unseen.add(new Unseen(r.link, request, nrc.orElse("no answer")));
            return nrc.map(n -> List.of(new Message(false, n, Hex.parse(n).length, java.time.Instant.EPOCH, -1)))
                    .orElse(List.of());
        }
        Map<String, Integer> c = cursors.computeIfAbsent(r.link, k -> new HashMap<>());
        int i = c.getOrDefault(request, 0);
        c.put(request, i + 1);
        List<Message> messages = recorded.get(i % recorded.size());
        answered.add(new Answered(r.link, request, messages.stream().map(Message::hex).toList()));
        return messages;
    }

    private static Optional<String> unseenAnswer(Recorded r, String request) {
        if (request.startsWith("22 ")) {
            return Optional.of("7F 22 31");
        }
        if (request.startsWith("1A ")) {
            return Optional.of(r.refuses1A ? "7F 1A 11" : "7F 1A 12");
        }
        return Optional.empty();
    }
}
