package io.github.samsanjaysatish.macvagdiag.tools;

import io.github.samsanjaysatish.macvagdiag.tools.CaptureSanitizer.Occurrence;
import io.github.samsanjaysatish.macvagdiag.tools.DiagPayloads.Message;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code sanitize-capture}: writes a copy of a capture with the VIN masked, verified by reading the written file
 * back. Reads files only; never opens a serial port or an adapter.
 */
final class SanitizeCaptureCommand {

    static final String USAGE = """
            Usage: %s sanitize-capture --output <file> [--vin <VIN> | --vin-from <file>] [--mask <text>]... <capture>
              Masks the VIN in a candump capture so it can be committed as a test fixture. The VIN is found in
              reassembled TP 2.0 / ISO-TP payloads, also when split across frames, and replaced by %s; any
              8+ character piece of it and its last 6 characters are masked too. Comment lines are masked as text.
              Everything else stays byte for byte; one '# sanitized: ...' line is added before the first frame.
              The written file is read back and checked for any trace of the VIN (payloads, raw frame bytes,
              comments); on failure it is deleted and the exit code is 1.
              --output    sanitized capture; never overwritten
              --vin       the car's VIN (default: taken from the capture's VIN responses: OBD 49 02, UDS 62 F1 90,
                          KWP 5A 90). Needed when the capture has none, so the output can be checked anyway.
              --vin-from  read the VIN from another capture or from a VCDS log instead of typing it
              --mask      also mask this ASCII text (6+ characters, e.g. a module serial number); repeatable.
                          Digits become 0, letters X.""".formatted(Main.TOOL_NAME, Vin.FAKE);

    private static final String PREFIX = "sanitize-capture: ";
    private static final int MAX_LINES_LISTED = 12;

    private SanitizeCaptureCommand() {
    }

    static int run(List<String> argList, Environment env) {
        if (Main.wantsHelp(argList)) {
            env.out().println(USAGE);
            return Main.EXIT_OK;
        }
        SanitizeArgs args;
        try {
            args = SanitizeArgs.parse(argList, Set.of());
        } catch (UsageException e) {
            env.err().println(PREFIX + e.getMessage());
            env.err().println(USAGE);
            return Main.EXIT_USAGE;
        }
        if (Files.exists(args.output())) {
            env.err().println(PREFIX + args.output() + " already exists; it is never overwritten");
            return Main.EXIT_USAGE;
        }
        try {
            String input = readUtf8(args.input());
            List<Message> messages = DiagPayloads.reassemble(CaptureSanitizer.Parsed.of(input).frames);
            List<VinFinder.Found> responses = VinFinder.inResponses(messages);
            Set<String> detected = new LinkedHashSet<>();
            responses.forEach(f -> detected.add(f.vin()));
            Optional<String> given = args.givenVin();
            String vin = SanitizeArgs.choose(given, detected);
            Redactions redactions = redactions(vin, args.masks());

            CaptureSanitizer.Result result = CaptureSanitizer.sanitize(input, redactions);
            CaptureSanitizer.verify(input, result.output(), redactions);
            write(args.output(), result.output().getBytes(StandardCharsets.UTF_8));
            try {
                CaptureSanitizer.verify(input, readUtf8(args.output()), redactions);
            } catch (SanitizeException | IOException | RuntimeException e) {
                Files.deleteIfExists(args.output());
                throw e;
            }

            report(env, args, given, responses, result, VinFinder.vinLike(messages, Set.of(vin)));
            return Main.EXIT_OK;
        } catch (SanitizeException e) {
            env.err().println(PREFIX + e.getMessage());
            return Main.EXIT_FAILURE;
        } catch (IOException e) {
            env.err().println(PREFIX + "I/O error: " + e.getMessage());
            return Main.EXIT_FAILURE;
        }
    }

    static Redactions redactions(String vin, List<String> masks) throws SanitizeException {
        try {
            return Redactions.of(Optional.of(vin), masks);
        } catch (IllegalArgumentException e) {
            throw new SanitizeException(e.getMessage());
        }
    }

    private static String readUtf8(Path file) throws IOException, SanitizeException {
        try {
            byte[] bytes = Files.readAllBytes(file);
            return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (NoSuchFileException e) {
            throw new SanitizeException(file + " not found");
        } catch (CharacterCodingException e) {
            throw new SanitizeException(file + " is not UTF-8 text");
        }
    }

    /** Creates the parent directories and the file; fails if the file appeared in the meantime. */
    static void write(Path output, byte[] bytes) throws IOException {
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(output, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static void report(Environment env, SanitizeArgs args, Optional<String> given,
                               List<VinFinder.Found> responses, CaptureSanitizer.Result result,
                               List<VinFinder.Found> vinLike) {
        var out = env.out();
        out.println(PREFIX + args.input() + " -> " + args.output());
        out.println("  frames: " + result.frames() + " (" + result.framesChanged() + " changed)");
        out.println("  VIN: " + (given.isEmpty() ? "from the capture's own VIN responses"
                : args.vinFrom().isPresent() ? "from " + args.vinFrom().get() : "from --vin")
                + "; replaced by " + Vin.FAKE);
        Map<String, List<Integer>> byWhere = new LinkedHashMap<>();
        for (VinFinder.Found f : responses) {
            byWhere.computeIfAbsent(f.where(), k -> new java.util.ArrayList<>()).add(f.message().firstFrame());
        }
        out.println("  VIN responses in the capture: " + responses.size());
        byWhere.forEach((where, frames) -> out.println("    " + where + ": " + frames.size()));
        Map<String, List<Integer>> masked = new LinkedHashMap<>();
        for (Occurrence o : result.occurrences()) {
            masked.computeIfAbsent(o.where() + ": " + o.kind().label(), k -> new java.util.ArrayList<>())
                    .add(o.line());
        }
        out.println("  masked: " + result.occurrences().size() + " place(s)");
        masked.forEach((what, lines) -> out.println("    " + lines.size() + "x " + what + " (line"
                + (lines.size() == 1 ? " " : "s ") + lineList(lines) + ")"));
        out.println("  verified on the written file: no trace of the VIN"
                + (args.masks().isEmpty() ? "" : " or the --mask strings")
                + " in reassembled payloads, raw frame bytes or comment lines");
        if (!vinLike.isEmpty()) {
            Map<String, Long> where = vinLike.stream().collect(Collectors.groupingBy(VinFinder.Found::where,
                    LinkedHashMap::new, Collectors.counting()));
            out.println("  review: " + vinLike.size() + " other 17-character string(s) with a valid VIN check "
                    + "digit, not masked (often module serial numbers): " + where.entrySet().stream()
                    .map(e -> e.getKey() + " x" + e.getValue()).collect(Collectors.joining(", ")));
        }
    }

    static String lineList(List<Integer> lines) {
        String shown = lines.stream().limit(MAX_LINES_LISTED).map(String::valueOf).collect(Collectors.joining(", "));
        return lines.size() > MAX_LINES_LISTED ? shown + ", ..." : shown;
    }
}
