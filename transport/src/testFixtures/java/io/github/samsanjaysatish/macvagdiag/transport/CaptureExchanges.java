package io.github.samsanjaysatish.macvagdiag.transport;

import io.github.samsanjaysatish.macvagdiag.can.CanFrame;
import io.github.samsanjaysatish.macvagdiag.can.Capture;
import io.github.samsanjaysatish.macvagdiag.can.CapturedFrame;
import io.github.samsanjaysatish.macvagdiag.can.Hex;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Test fixture: reassembles a listen-only capture of VCDS talking to the car into per-module <b>sessions</b> of
 * diagnostic messages, so tests can replay them (strictly, frame for frame, with {@code ReplayAdapter}) or answer
 * our own requests from them ({@link CaptureEcu}).
 *
 * <ul>
 *   <li><b>TP 2.0:</b> a session runs from the setup request on {@code 200} that the module answered (on
 *       {@code 200 + address}) to the {@code A8} pair, on the negotiated tester transmit ID and the tester receive
 *       ID {@code 300}. One channel at a time, as on the capture. Data packets are reassembled per direction; the
 *       2-byte length's flag bit (bit 15, set on every {@code 7F xx 78}) is kept in {@link Message#lengthField()}.
 *       Setups nobody answered are listed in {@link Result#unansweredSetups()}.</li>
 *   <li><b>ISO-TP:</b> on the request/response pairs the car uses ({@link #ISO_TP_PAIRS}); a session ends with
 *       VCDS's one-byte {@code A8} on the request ID. Frames on the functional ID {@code 700} ({@code 3E 80}) belong
 *       to no session and are counted in {@link Result#functionalFrames()}.</li>
 * </ul>
 * Every frame of a session (setup, control, data, ACKs, flow control) is kept by index in
 * {@link Session#frameIndices()}, so a test can cut the session out of the capture.
 */
public final class CaptureExchanges {

    /** The ISO-TP request → response pairs seen on the 2026-11-13 capture (UDS modules, plus OBD-II on 7E0). */
    public static final Map<Integer, Integer> ISO_TP_PAIRS = Map.of(
            0x714, 0x77E, 0x732, 0x79C, 0x715, 0x77F, 0x711, 0x77B, 0x731, 0x79B, 0x76B, 0x7D5, 0x7E0, 0x7E8);
    public static final int TP20_SETUP_ID = 0x200;
    public static final int FUNCTIONAL_ID = 0x700;

    /** A module link as seen on the bus. */
    public sealed interface Link {
        /** TP 2.0 to logical address {@code address}. */
        record Tp20(int address) implements Link {
            @Override
            public String toString() {
                return String.format("TP2.0 %02X", address);
            }
        }

        /** ISO-TP on {@code requestId → responseId}. */
        record IsoTp(int requestId, int responseId) implements Link {
            @Override
            public String toString() {
                return String.format("ISO-TP %03X/%03X", requestId, responseId);
            }
        }
    }

    /** Something that happened inside a session. */
    public sealed interface Event {
        boolean fromTester();

        Instant time();
    }

    /**
     * A whole diagnostic message.
     *
     * @param hex the payload, bus-style hex
     * @param lengthField TP 2.0: the 16-bit length field as sent (flags included); ISO-TP: the plain length
     * @param firstFrame the capture index of the message's first frame ({@code -1} if not from a capture)
     */
    public record Message(boolean fromTester, String hex, int lengthField, Instant time, int firstFrame)
            implements Event {
        public byte[] payload() {
            return Hex.parse(hex);
        }

        /** TP 2.0: the length field's flag bits ({@code 0x8000} on response pending). */
        public int lengthFlags() {
            return lengthField & ~0x7FFF;
        }
    }

    /** A control frame: TP 2.0 {@code A0 A1 A3 A8}; ISO-TP flow control or VCDS's one-byte {@code A8}. */
    public record Control(boolean fromTester, CanFrame frame, Instant time) implements Event {
        public int opcode() {
            return frame.length() == 0 ? -1 : frame.dataByte(0);
        }
    }

    /** One request and everything the module sent until the tester's next message. */
    public record Exchange(Link link, Message request, List<Message> responses, List<Control> moduleControls) {
        public Exchange {
            responses = List.copyOf(responses);
            moduleControls = List.copyOf(moduleControls);
        }

        public String requestHex() {
            return request.hex();
        }
    }

    /**
     * One channel / conversation with one module.
     *
     * @param setupReply TP 2.0: the module's {@code D0} frame; empty for ISO-TP
     * @param testerTxId the ID the tester sends on (TP 2.0 negotiated, or the ISO-TP request ID)
     * @param rxId the ID the module answers on ({@code 300}, or the ISO-TP response ID)
     * @param closed whether the session ended properly ({@code A8} pair / VCDS's {@code A8})
     */
    public record Session(Link link, Optional<CanFrame> setupReply, int testerTxId, int rxId, List<Integer> frameIndices,
            List<Event> events, boolean closed, List<String> errors) {

        public Session {
            frameIndices = List.copyOf(frameIndices);
            events = List.copyOf(events);
            errors = List.copyOf(errors);
        }

        public List<Message> messages() {
            return events.stream().filter(e -> e instanceof Message).map(e -> (Message) e).toList();
        }

        /** The module's timing reply {@code A1 ...} to the tester's {@code A0}, TP 2.0 only. */
        public Optional<CanFrame> timingReply() {
            for (int i = 0; i + 1 < events.size(); i++) {
                if (events.get(i) instanceof Control c && c.fromTester() && c.opcode() == 0xA0
                        && events.get(i + 1) instanceof Control r && !r.fromTester() && r.opcode() == 0xA1) {
                    return Optional.of(r.frame());
                }
            }
            return Optional.empty();
        }

        /** Requests and their responses, in order. */
        public List<Exchange> exchanges() {
            List<Exchange> out = new ArrayList<>();
            Message request = null;
            List<Message> responses = new ArrayList<>();
            List<Control> controls = new ArrayList<>();
            for (Event e : events) {
                if (e instanceof Message m && m.fromTester()) {
                    if (request != null) {
                        out.add(new Exchange(link, request, responses, controls));
                    }
                    request = m;
                    responses = new ArrayList<>();
                    controls = new ArrayList<>();
                } else if (request != null && e instanceof Message m) {
                    responses.add(m);
                } else if (request != null && e instanceof Control c && !c.fromTester() && c.opcode() == 0xA3) {
                    controls.add(c);
                }
            }
            if (request != null) {
                out.add(new Exchange(link, request, responses, controls));
            }
            return out;
        }

        /** The session's frames, cut out of {@code capture} (keeps the capture's metadata). */
        public Capture slice(Capture capture) {
            return sliceBefore(capture, Integer.MAX_VALUE);
        }

        /** The session's frames before capture index {@code endExclusive}. */
        public Capture sliceBefore(Capture capture, int endExclusive) {
            List<CapturedFrame> frames = new ArrayList<>();
            for (int i : frameIndices) {
                if (i < endExclusive) {
                    frames.add(capture.frames().get(i));
                }
            }
            return new Capture(capture.metadata(), List.of(), frames);
        }
    }

    /** All sessions of a capture. */
    public record Result(List<Session> sessions, Set<Integer> unansweredSetups, int functionalFrames,
            List<Integer> unassignedFrames) {

        public Result {
            sessions = List.copyOf(sessions);
            unansweredSetups = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(unansweredSetups));
            unassignedFrames = List.copyOf(unassignedFrames);
        }

        public List<Session> sessions(Link link) {
            return sessions.stream().filter(s -> s.link().equals(link)).toList();
        }

        public Set<Link> links() {
            Set<Link> out = new LinkedHashSet<>();
            sessions.forEach(s -> out.add(s.link()));
            return out;
        }
    }

    private CaptureExchanges() {
    }

    /** Reassembles {@code capture}. */
    public static Result of(Capture capture) {
        return new Parser(capture).run();
    }

    /** One direction of a message being reassembled. */
    private static final class Assembly {
        ByteArrayOutputStream bytes;
        int lengthField;
        int length;
        Instant start;
        int startIndex;
    }

    private static final class Open {
        final Link link;
        final Optional<CanFrame> setupReply;
        final int txId;
        final int rxId;
        final List<Integer> frames = new ArrayList<>();
        final List<Event> events = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        final Assembly tester = new Assembly();
        final Assembly module = new Assembly();
        boolean testerSentA8;
        boolean moduleSentA8;

        Open(Link link, Optional<CanFrame> setupReply, int txId, int rxId) {
            this.link = link;
            this.setupReply = setupReply;
            this.txId = txId;
            this.rxId = rxId;
        }

        Session close(boolean closed) {
            if (tester.bytes != null || module.bytes != null) {
                errors.add("message still being received at the end of the session");
            }
            return new Session(link, setupReply, txId, rxId, frames, events, closed, errors);
        }
    }

    private static final class Parser {
        private final Capture capture;
        private final List<Session> sessions = new ArrayList<>();
        private final Set<Integer> unanswered = new LinkedHashSet<>();
        private final Set<Integer> answered = new LinkedHashSet<>();
        private final List<Integer> unassigned = new ArrayList<>();
        private int functional;
        private Open tp20;
        /** Setup request waiting for its reply: address → frame index. */
        private final Map<Integer, Integer> pendingSetups = new LinkedHashMap<>();
        private final Map<Integer, Open> isoTp = new LinkedHashMap<>();

        Parser(Capture capture) {
            this.capture = Objects.requireNonNull(capture, "capture");
        }

        Result run() {
            List<CapturedFrame> frames = capture.frames();
            for (int i = 0; i < frames.size(); i++) {
                CapturedFrame cf = frames.get(i);
                CanFrame f = cf.frame();
                if (f.isExtended()) {
                    unassigned.add(i);
                } else if (f.id() == TP20_SETUP_ID) {
                    setupRequest(i, f);
                } else if (f.id() > TP20_SETUP_ID && f.id() <= TP20_SETUP_ID + 0x7F && f.length() >= 7
                        && f.dataByte(0) == 0x00 && f.dataByte(1) == 0xD0) {
                    setupReply(i, f);
                } else if (tp20 != null && (f.id() == tp20.txId || f.id() == tp20.rxId)) {
                    tp20Frame(i, cf);
                } else if (f.id() == FUNCTIONAL_ID) {
                    functional++;
                } else if (ISO_TP_PAIRS.containsKey(f.id()) || ISO_TP_PAIRS.containsValue(f.id())) {
                    isoTpFrame(i, cf);
                } else {
                    unassigned.add(i);
                }
            }
            if (tp20 != null) {
                sessions.add(tp20.close(false));
            }
            isoTp.values().forEach(o -> sessions.add(o.close(false)));
            sessions.sort(java.util.Comparator.comparingInt(s -> s.frameIndices().getFirst()));
            Set<Integer> neverAnswered = new LinkedHashSet<>(unanswered);
            neverAnswered.removeAll(answered);
            return new Result(sessions, neverAnswered, functional, unassigned);
        }

        private void setupRequest(int index, CanFrame f) {
            if (f.length() >= 7 && f.dataByte(1) == 0xC0) {
                pendingSetups.put(f.dataByte(0), index);
                unanswered.add(f.dataByte(0));
            } else {
                unassigned.add(index); // VCDS's unexplained 3-byte frames after unreachable modules
            }
        }

        private void setupReply(int index, CanFrame f) {
            int address = f.id() - TP20_SETUP_ID;
            Integer request = pendingSetups.remove(address);
            if (request == null) {
                unassigned.add(index);
                return;
            }
            if (tp20 != null) {
                tp20.errors.add("new setup before the A8 pair");
                sessions.add(tp20.close(false));
            }
            answered.add(address);
            int rx = Tp20Channel.decodeId(f.dataByte(2), f.dataByte(3));
            int tx = Tp20Channel.decodeId(f.dataByte(4), f.dataByte(5));
            tp20 = new Open(new Link.Tp20(address), Optional.of(f), tx, rx);
            tp20.frames.add(request);
            tp20.frames.add(index);
        }

        private void tp20Frame(int index, CapturedFrame cf) {
            CanFrame f = cf.frame();
            Open o = tp20;
            o.frames.add(index);
            boolean fromTester = f.id() == o.txId;
            if (f.length() == 0) {
                o.errors.add("empty frame " + f);
                return;
            }
            int b0 = f.dataByte(0);
            if (b0 == 0xA0 || b0 == 0xA1 || b0 == 0xA3 || b0 == 0xA8) {
                o.events.add(new Control(fromTester, f, cf.timestamp()));
                if (b0 == 0xA8) {
                    if (fromTester) {
                        o.testerSentA8 = true;
                    } else {
                        o.moduleSentA8 = true;
                    }
                    if (o.testerSentA8 && o.moduleSentA8) {
                        sessions.add(o.close(true));
                        tp20 = null;
                    }
                }
                return;
            }
            int op = b0 >> 4;
            if (op > 3) {
                return; // ACK (B) / not ready (9)
            }
            Assembly a = fromTester ? o.tester : o.module;
            byte[] data = f.data();
            int from = 1;
            if (a.bytes == null) {
                if (data.length < 3) {
                    o.errors.add("first packet without a length: " + f);
                    return;
                }
                a.lengthField = (Byte.toUnsignedInt(data[1]) << 8) | Byte.toUnsignedInt(data[2]);
                a.length = a.lengthField & 0x7FFF;
                a.bytes = new ByteArrayOutputStream();
                a.start = cf.timestamp();
                a.startIndex = index;
                from = 3;
            }
            a.bytes.write(data, from, data.length - from);
            boolean last = op == 1 || op == 3;
            if (last || a.bytes.size() >= a.length) {
                if (a.bytes.size() != a.length || !last) {
                    o.errors.add(String.format("length %d, got %d bytes (last=%s) at %s", a.length, a.bytes.size(), last, f));
                }
                o.events.add(new Message(fromTester, Hex.format(a.bytes.toByteArray()), a.lengthField, a.start,
                        a.startIndex));
                a.bytes = null;
            }
        }

        private void isoTpFrame(int index, CapturedFrame cf) {
            CanFrame f = cf.frame();
            boolean fromTester = ISO_TP_PAIRS.containsKey(f.id());
            int request = fromTester ? f.id() : requestFor(f.id());
            Open o = isoTp.get(request);
            if (o == null) {
                o = new Open(new Link.IsoTp(request, ISO_TP_PAIRS.get(request)), Optional.empty(), request,
                        ISO_TP_PAIRS.get(request));
                isoTp.put(request, o);
            }
            o.frames.add(index);
            if (f.length() == 0) {
                o.errors.add("empty frame " + f);
                return;
            }
            if (fromTester && f.length() == 1 && f.dataByte(0) == 0xA8) {
                o.events.add(new Control(true, f, cf.timestamp()));
                sessions.add(o.close(true));
                isoTp.remove(request);
                return;
            }
            Assembly a = fromTester ? o.tester : o.module;
            byte[] data = f.data();
            int pci = f.dataByte(0) >> 4;
            switch (pci) {
                case 0 -> {
                    int length = f.dataByte(0) & 0x0F;
                    if (length == 0 || length > data.length - 1) {
                        o.errors.add("bad single frame " + f);
                        return;
                    }
                    o.events.add(new Message(fromTester, Hex.format(java.util.Arrays.copyOfRange(data, 1, 1 + length)),
                            length, cf.timestamp(), index));
                }
                case 1 -> {
                    a.length = ((f.dataByte(0) & 0x0F) << 8) | f.dataByte(1);
                    a.lengthField = a.length;
                    a.bytes = new ByteArrayOutputStream();
                    a.start = cf.timestamp();
                    a.startIndex = index;
                    a.bytes.write(data, 2, data.length - 2);
                }
                case 2 -> {
                    if (a.bytes == null) {
                        o.errors.add("consecutive frame without a first frame " + f);
                        return;
                    }
                    a.bytes.write(data, 1, Math.min(data.length - 1, a.length - a.bytes.size()));
                    if (a.bytes.size() >= a.length) {
                        o.events.add(new Message(fromTester, Hex.format(a.bytes.toByteArray()), a.length, a.start,
                                a.startIndex));
                        a.bytes = null;
                    }
                }
                case 3 -> o.events.add(new Control(fromTester, f, cf.timestamp()));
                default -> o.errors.add("unknown PCI " + f);
            }
        }

        private static int requestFor(int responseId) {
            return ISO_TP_PAIRS.entrySet().stream().filter(e -> e.getValue() == responseId).findFirst().orElseThrow()
                    .getKey();
        }
    }
}
