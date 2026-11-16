package io.github.samsanjaysatish.macvagdiag.tools;

import io.github.samsanjaysatish.macvagdiag.tools.VcdsLogSanitizer.Field;
import io.github.samsanjaysatish.macvagdiag.tools.VcdsLogSanitizer.FieldHit;
import io.github.samsanjaysatish.macvagdiag.tools.VcdsLogSanitizer.TextHit;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code sanitize-vcds-log}: writes a copy of a VCDS text log with the VIN masked (and other identifying fields on
 * request), verified by reading the written file back. The file is handled as single bytes (ISO-8859-1), so
 * whatever code page VCDS wrote survives unchanged.
 */
final class SanitizeVcdsLogCommand {

    static final String MASK_VCID = "--mask-vcid";
    static final String MASK_VINID = "--mask-vinid";
    static final String MASK_SERIALS = "--mask-serials";
    static final String MASK_SHOP_CODES = "--mask-shop-codes";
    static final String MASK_ALL = "--mask-all";
    private static final Set<String> FLAGS = Set.of(MASK_VCID, MASK_VINID, MASK_SERIALS, MASK_SHOP_CODES, MASK_ALL);

    static final String USAGE = """
            Usage: %s sanitize-vcds-log --output <file> [--vin <VIN> | --vin-from <file>] [--mask-vcid] [--mask-vinid]
                   [--mask-serials] [--mask-shop-codes] [--mask-all] [--mask <text>]... <vcds log>
              Replaces the VIN in a VCDS text log with %s (also 8+ character pieces of it, and its last 6
              characters as a standalone word). A license plate or repair order, if filled in, is always masked.
              Every replacement keeps the length. Other identifying fields are listed and kept unless asked for:
              --mask-vcid        VCID values (computed by VCDS, never on the bus)
              --mask-vinid       VINID values
              --mask-serials     module and sensor serial numbers (also on the bus; see sanitize-capture --mask)
              --mask-shop-codes  workshop codes (WSC workshop / importer / equipment numbers)
              --mask-all         all of the above
              --output    sanitized log; never overwritten
              --vin       the VIN (default: the log's 'VIN:' fields; needed when it has none)
              --vin-from  read the VIN from a capture or another VCDS log
              --mask      also mask this text (6+ characters), anywhere; repeatable""".formatted(Main.TOOL_NAME,
            Vin.FAKE);

    private static final String PREFIX = "sanitize-vcds-log: ";

    private SanitizeVcdsLogCommand() {
    }

    static int run(List<String> argList, Environment env) {
        if (Main.wantsHelp(argList)) {
            env.out().println(USAGE);
            return Main.EXIT_OK;
        }
        SanitizeArgs args;
        try {
            args = SanitizeArgs.parse(argList, FLAGS);
        } catch (UsageException e) {
            env.err().println(PREFIX + e.getMessage());
            env.err().println(USAGE);
            return Main.EXIT_USAGE;
        }
        if (Files.exists(args.output())) {
            env.err().println(PREFIX + args.output() + " already exists; it is never overwritten");
            return Main.EXIT_USAGE;
        }
        Set<Field> requested = requested(args.flags());
        try {
            String input = read(args);
            Optional<String> given = args.givenVin();
            String vin = SanitizeArgs.choose(given, VinFinder.inVcdsLog(input));
            Redactions redactions = SanitizeCaptureCommand.redactions(vin, args.masks());

            VcdsLogSanitizer.Result result = VcdsLogSanitizer.sanitize(input, redactions, requested);
            VcdsLogSanitizer.verify(input, result.output(), redactions, requested);
            SanitizeCaptureCommand.write(args.output(), result.output().getBytes(StandardCharsets.ISO_8859_1));
            try {
                String back = new String(Files.readAllBytes(args.output()), StandardCharsets.ISO_8859_1);
                VcdsLogSanitizer.verify(input, back, redactions, requested);
            } catch (SanitizeException | IOException | RuntimeException e) {
                Files.deleteIfExists(args.output());
                throw e;
            }
            report(env, args, given, result);
            return Main.EXIT_OK;
        } catch (SanitizeException e) {
            env.err().println(PREFIX + e.getMessage());
            return Main.EXIT_FAILURE;
        } catch (IOException e) {
            env.err().println(PREFIX + "I/O error: " + e.getMessage());
            return Main.EXIT_FAILURE;
        }
    }

    static Set<Field> requested(Set<String> flags) {
        Set<Field> fields = EnumSet.noneOf(Field.class);
        boolean all = flags.contains(MASK_ALL);
        if (all || flags.contains(MASK_VCID)) {
            fields.add(Field.VCID);
        }
        if (all || flags.contains(MASK_VINID)) {
            fields.add(Field.VINID);
        }
        if (all || flags.contains(MASK_SERIALS)) {
            fields.add(Field.SERIAL);
        }
        if (all || flags.contains(MASK_SHOP_CODES)) {
            fields.add(Field.SHOP_CODE);
        }
        return fields;
    }

    private static String read(SanitizeArgs args) throws IOException, SanitizeException {
        try {
            return new String(Files.readAllBytes(args.input()), StandardCharsets.ISO_8859_1);
        } catch (NoSuchFileException e) {
            throw new SanitizeException(args.input() + " not found");
        }
    }

    private static void report(Environment env, SanitizeArgs args, Optional<String> given,
                               VcdsLogSanitizer.Result result) {
        var out = env.out();
        out.println(PREFIX + args.input() + " -> " + args.output());
        out.println("  VIN: " + (given.isEmpty() ? "from the log's 'VIN:' fields"
                : args.vinFrom().isPresent() ? "from " + args.vinFrom().get() : "from --vin")
                + "; replaced by " + Vin.FAKE);
        Map<String, List<Integer>> masked = new LinkedHashMap<>();
        for (TextHit h : result.textHits()) {
            masked.computeIfAbsent(h.kind().label(), k -> new ArrayList<>()).add(h.line());
        }
        out.println("  masked as text: " + result.textHits().size() + " place(s)");
        masked.forEach((what, lines) -> out.println("    " + lines.size() + "x " + what + " (line"
                + (lines.size() == 1 ? " " : "s ") + SanitizeCaptureCommand.lineList(lines) + ")"));
        out.println("  identifying fields:");
        for (Field f : Field.values()) {
            List<Integer> lines = result.fields().stream().filter(h -> h.field() == f).map(FieldHit::line).toList();
            boolean isMasked = result.fields().stream().anyMatch(h -> h.field() == f && h.masked());
            String state = lines.isEmpty() ? "none"
                    : lines.size() + " (line" + (lines.size() == 1 ? " " : "s ")
                    + SanitizeCaptureCommand.lineList(lines) + "), "
                    + (isMasked ? "masked" : "kept" + f.option().map(o -> "; " + o + " masks them").orElse(""));
            out.println("    " + f.label() + ": " + state);
        }
        if (result.otherPlaces() > 0) {
            out.println("  masked field values found again elsewhere: " + result.otherPlaces() + " place(s)");
        }
        out.println("  verified on the written file: no trace of the VIN"
                + (result.fields().stream().anyMatch(FieldHit::masked) ? " or the masked fields" : "")
                + (args.masks().isEmpty() ? "" : " or the --mask strings"));
    }
}
