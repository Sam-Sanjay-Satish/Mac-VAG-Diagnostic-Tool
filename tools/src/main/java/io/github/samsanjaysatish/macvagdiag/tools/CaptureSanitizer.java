package io.github.samsanjaysatish.macvagdiag.tools;

import io.github.samsanjaysatish.macvagdiag.can.CanFrame;
import io.github.samsanjaysatish.macvagdiag.can.CaptureFormatException;
import io.github.samsanjaysatish.macvagdiag.can.CaptureReader;
import io.github.samsanjaysatish.macvagdiag.tools.DiagPayloads.Message;
import io.github.samsanjaysatish.macvagdiag.tools.DiagPayloads.Stream;
import io.github.samsanjaysatish.macvagdiag.tools.Redactions.Hit;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks the VIN (and {@code --mask} strings) in a candump capture so it can be committed as a test fixture.
 *
 * <p>Pure text in, text out. The output is the input line for line, with one {@code # sanitized: ...} line
 * inserted before the first frame. Only payload bytes of reassembled TP 2.0 / ISO-TP messages
 * ({@link DiagPayloads}) and the text of comment lines change; timestamps, interfaces, IDs, lengths, protocol
 * bytes and line endings stay byte for byte.
 *
 * <p>{@link #verify} checks a result independently (it's run on what was written to disk): structure unchanged,
 * changed bytes only inside payloads, and no trace of the VIN left in the reassembled payloads, in any single
 * frame, in the raw bytes of each ID (all bytes, and without the first byte), in all frames' bytes in file order,
 * or in comment lines.
 */
final class CaptureSanitizer {

    static final String SANITIZED_KEY = "sanitized";
    private static final int MAX_PROBLEMS_SHOWN = 20;
    private static final Pattern FRAME_LINE = Pattern.compile(
            "(\\s*\\(\\d+(?:\\.\\d+)?\\)\\s+\\S+\\s+)([0-9A-Fa-f]{3}|[0-9A-Fa-f]{8})#([0-9A-Fa-f]*)(\\s*)");

    /**
     * Where something was masked.
     *
     * @param where e.g. {@code 7E8 ISO-TP payload} or {@code comment line}
     * @param line 1-based line number in the input (for a payload: the frame holding its first byte)
     */
    record Occurrence(String where, Redactions.Kind kind, int line) {
    }

    /**
     * @param output the sanitized capture
     * @param messages the input's reassembled messages
     */
    record Result(String output, int frames, int framesChanged, List<Occurrence> occurrences,
                  List<Message> messages) {
    }

    private CaptureSanitizer() {
    }

    static Result sanitize(String input, Redactions redactions) throws SanitizeException {
        Parsed p = Parsed.of(input);
        List<Message> messages = DiagPayloads.reassemble(p.frames);
        byte[][] data = new byte[p.frames.size()][];
        for (int i = 0; i < data.length; i++) {
            data[i] = p.frames.get(i).data();
        }
        List<Occurrence> occurrences = new ArrayList<>();
        for (Stream s : DiagPayloads.streams(messages)) {
            byte[] masked = s.bytes().clone();
            for (Hit h : redactions.maskBytes(masked)) {
                occurrences.add(new Occurrence(s.idText() + " " + s.protocol().label() + " payload",
                        h.needle().kind(), p.frameLine.get(s.frames()[h.offset()]) + 1));
            }
            for (int i = 0; i < masked.length; i++) {
                if (masked[i] != s.bytes()[i]) {
                    data[s.frames()[i]][s.indexes()[i]] = masked[i];
                }
            }
        }

        List<String> lines = new ArrayList<>(p.lines);
        for (int li = 0; li < lines.size(); li++) {
            if (p.lineToFrame.containsKey(li)) {
                continue;
            }
            List<Hit> hits = new ArrayList<>();
            lines.set(li, redactions.maskText(lines.get(li), hits));
            for (Hit h : hits) {
                occurrences.add(new Occurrence("comment line", h.needle().kind(), li + 1));
            }
        }
        int framesChanged = 0;
        for (int f = 0; f < data.length; f++) {
            byte[] original = p.frames.get(f).data();
            if (!java.util.Arrays.equals(original, data[f])) {
                framesChanged++;
                int li = p.frameLine.get(f);
                lines.set(li, p.rewrite(li, original, data[f]));
            }
        }
        occurrences.sort(java.util.Comparator.comparingInt(Occurrence::line));

        int insertAt = p.insertIndex();
        StringBuilder out = new StringBuilder(input.length() + 200);
        String eol = p.lineEnding();
        for (int li = 0; li < lines.size(); li++) {
            if (li == insertAt) {
                out.append(sanitizedLine(redactions, occurrences)).append(eol);
            }
            out.append(lines.get(li));
            String terminator = p.terminators.get(li);
            if (terminator.isEmpty() && insertAt == lines.size() && li == lines.size() - 1) {
                terminator = eol;
            }
            out.append(terminator);
        }
        if (insertAt == lines.size()) {
            out.append(sanitizedLine(redactions, occurrences)).append(eol);
        }
        return new Result(out.toString(), p.frames.size(), framesChanged, List.copyOf(occurrences), messages);
    }

    private static String sanitizedLine(Redactions r, List<Occurrence> occurrences) {
        long vin = occurrences.stream().filter(o -> o.kind() != Redactions.Kind.EXTRA).count();
        long extra = occurrences.size() - vin;
        String vinPart = r.vin().isPresent()
                ? "VIN replaced by " + Vin.FAKE + " in " + vin + " place" + (vin == 1 ? "" : "s")
                : "no VIN given";
        return "# " + SANITIZED_KEY + ": " + Main.toolNameAndVersion() + " sanitize-capture; " + vinPart
                + "; --mask strings masked in " + extra + " place" + (extra == 1 ? "" : "s");
    }

    /**
     * Checks {@code output} against {@code input}: same structure and no trace left. Throws with every problem
     * found (locations only, never the value).
     */
    static void verify(String input, String output, Redactions redactions) throws SanitizeException {
        Parsed in = Parsed.of(input);
        Parsed out = Parsed.of(output);
        Set<String> problems = new LinkedHashSet<>();

        int insertAt = in.insertIndex();
        if (out.lines.size() != in.lines.size() + 1
                || !out.lines.get(insertAt).startsWith("# " + SANITIZED_KEY + ":")) {
            problems.add("the output doesn't have the input's lines plus one '# " + SANITIZED_KEY + ":' line");
        } else {
            Set<Long> payloadBytes = new HashSet<>();
            for (Message m : DiagPayloads.reassemble(in.frames)) {
                for (int i = 0; i < m.bytes().length; i++) {
                    payloadBytes.add((long) m.frames()[i] << 4 | m.indexes()[i]);
                }
            }
            for (int li = 0; li < in.lines.size(); li++) {
                int lo = li < insertAt ? li : li + 1;
                compareLine(in, li, out, lo, payloadBytes, problems);
            }
        }

        for (Stream s : DiagPayloads.streams(DiagPayloads.reassemble(out.frames))) {
            for (Hit h : redactions.findBytes(s.bytes())) {
                problems.add(s.idText() + " " + s.protocol().label() + " payload: " + h.needle().kind().label()
                        + " remains at line " + (out.frameLine.get(s.frames()[h.offset()]) + 1));
            }
        }
        for (RawStream raw : RawStream.all(out)) {
            for (Hit h : redactions.findBytes(raw.bytes)) {
                problems.add(raw.label + ": " + h.needle().kind().label() + " in the raw frame bytes at line "
                        + (out.frameLine.get(raw.frames[h.offset()]) + 1));
            }
        }
        for (int li = 0; li < out.lines.size(); li++) {
            if (!out.lineToFrame.containsKey(li)) {
                for (Hit h : redactions.findText(out.lines.get(li))) {
                    problems.add("line " + (li + 1) + ": " + h.needle().kind().label() + " in a comment line");
                }
            }
        }
        if (!problems.isEmpty()) {
            List<String> shown = problems.stream().limit(MAX_PROBLEMS_SHOWN).toList();
            throw new SanitizeException("verification failed, " + problems.size() + " problem(s):\n  "
                    + String.join("\n  ", shown)
                    + (problems.size() > shown.size() ? "\n  ..." : ""));
        }
    }

    private static void compareLine(Parsed in, int li, Parsed out, int lo, Set<Long> payloadBytes,
                                    Set<String> problems) {
        String where = "line " + (li + 1);
        Integer fi = in.lineToFrame.get(li);
        Integer fo = out.lineToFrame.get(lo);
        if (fi == null || fo == null) {
            if (fi != null || fo != null) {
                problems.add(where + ": frame line and comment line don't match");
            } else if (in.lines.get(li).length() != out.lines.get(lo).length()) {
                problems.add(where + ": a comment line changed length");
            }
            return;
        }
        Matcher a = FRAME_LINE.matcher(in.lines.get(li));
        Matcher b = FRAME_LINE.matcher(out.lines.get(lo));
        if (!a.matches() || !b.matches() || !a.group(1).equals(b.group(1)) || !a.group(2).equals(b.group(2))
                || !a.group(4).equals(b.group(4)) || a.group(3).length() != b.group(3).length()) {
            problems.add(where + ": timestamp, interface, ID or length changed");
            return;
        }
        if (!in.terminators.get(li).equals(out.terminators.get(lo))) {
            problems.add(where + ": line ending changed");
        }
        byte[] x = in.frames.get(fi).data();
        byte[] y = out.frames.get(fo).data();
        for (int k = 0; k < x.length; k++) {
            if (x[k] != y[k] && !payloadBytes.contains((long) fi << 4 | k)) {
                problems.add(where + ": byte " + k + " changed outside a message payload");
            }
        }
    }

    /** Raw frame bytes concatenated, as a backstop for anything the reassembly doesn't parse. */
    private record RawStream(String label, byte[] bytes, int[] frames) {

        static List<RawStream> all(Parsed p) {
            List<RawStream> out = new ArrayList<>();
            Map<String, List<Integer>> byId = new LinkedHashMap<>();
            for (int f = 0; f < p.frames.size(); f++) {
                CanFrame frame = p.frames.get(f);
                String id = frame.isExtended() ? "%08X".formatted(frame.id()) : "%03X".formatted(frame.id());
                byId.computeIfAbsent(id, k -> new ArrayList<>()).add(f);
                out.add(of("frame", p, List.of(f), 0));
            }
            for (Map.Entry<String, List<Integer>> e : byId.entrySet()) {
                out.add(of(e.getKey() + " (all bytes)", p, e.getValue(), 0));
                out.add(of(e.getKey() + " (without byte 0)", p, e.getValue(), 1));
            }
            List<Integer> everything = new ArrayList<>();
            for (int f = 0; f < p.frames.size(); f++) {
                everything.add(f);
            }
            out.add(of("all frames in file order", p, everything, 0));
            return out;
        }

        private static RawStream of(String label, Parsed p, List<Integer> frames, int skip) {
            int size = 0;
            for (int f : frames) {
                size += Math.max(0, p.frames.get(f).length() - skip);
            }
            byte[] bytes = new byte[size];
            int[] owner = new int[size];
            int n = 0;
            for (int f : frames) {
                byte[] d = p.frames.get(f).data();
                for (int k = skip; k < d.length; k++) {
                    bytes[n] = d[k];
                    owner[n++] = f;
                }
            }
            return new RawStream(label, bytes, owner);
        }
    }

    /** The capture split into lines (terminators kept apart), with the frame lines decoded. */
    static final class Parsed {
        final List<String> lines = new ArrayList<>();
        final List<String> terminators = new ArrayList<>();
        final List<CanFrame> frames = new ArrayList<>();
        final List<Integer> frameLine = new ArrayList<>();
        final Map<Integer, Integer> lineToFrame = new LinkedHashMap<>();

        static Parsed of(String text) throws SanitizeException {
            try {
                CaptureReader.read(new StringReader(text));
            } catch (CaptureFormatException e) {
                throw new SanitizeException("not a valid candump log: " + e.getMessage());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            Parsed p = new Parsed();
            int start = 0;
            while (start < text.length()) {
                int nl = text.indexOf('\n', start);
                int end = nl < 0 ? text.length() : nl;
                int contentEnd = end > start && text.charAt(end - 1) == '\r' ? end - 1 : end;
                p.lines.add(text.substring(start, contentEnd));
                p.terminators.add(text.substring(contentEnd, nl < 0 ? end : nl + 1));
                start = nl < 0 ? text.length() : nl + 1;
            }
            for (int li = 0; li < p.lines.size(); li++) {
                String stripped = p.lines.get(li).strip();
                if (stripped.isEmpty() || stripped.charAt(0) == '#') {
                    continue;
                }
                Matcher m = FRAME_LINE.matcher(p.lines.get(li));
                if (!m.matches()) {
                    throw new SanitizeException("line " + (li + 1) + ": frame line format not supported by the "
                            + "sanitizer (expected '(time) iface ID#hexdata')");
                }
                String id = m.group(2);
                CanFrame frame = new CanFrame(Integer.parseUnsignedInt(id, 16), id.length() == 8,
                        io.github.samsanjaysatish.macvagdiag.can.Hex.parse(m.group(3)));
                p.lineToFrame.put(li, p.frames.size());
                p.frameLine.add(li);
                p.frames.add(frame);
            }
            return p;
        }

        /** Where the {@code # sanitized:} line goes: before the first frame, or at the end. */
        int insertIndex() {
            return frameLine.isEmpty() ? lines.size() : frameLine.getFirst();
        }

        String lineEnding() {
            return terminators.stream().filter(t -> !t.isEmpty()).findFirst().orElse("\n");
        }

        /** The frame line with {@code masked} in place of {@code original}; hex digit case kept per digit. */
        String rewrite(int li, byte[] original, byte[] masked) {
            Matcher m = FRAME_LINE.matcher(lines.get(li));
            if (!m.matches()) {
                throw new IllegalStateException("not a frame line");
            }
            char[] chars = lines.get(li).toCharArray();
            int base = m.start(3);
            for (int k = 0; k < original.length; k++) {
                if (original[k] == masked[k]) {
                    continue;
                }
                for (int nibble = 0; nibble < 2; nibble++) {
                    int pos = base + 2 * k + nibble;
                    int value = nibble == 0 ? (masked[k] & 0xF0) >> 4 : masked[k] & 0x0F;
                    char c = Character.forDigit(value, 16);
                    chars[pos] = Character.isUpperCase(chars[pos]) || Character.isDigit(chars[pos])
                            && !hasLowerHex(m.group(3)) ? Character.toUpperCase(c) : c;
                }
            }
            return new String(chars);
        }

        private static boolean hasLowerHex(String hex) {
            return hex.chars().anyMatch(c -> c >= 'a' && c <= 'f');
        }
    }
}
