package io.github.samsanjaysatish.macvagdiag.tools;

import io.github.samsanjaysatish.macvagdiag.can.CaptureFormatException;
import io.github.samsanjaysatish.macvagdiag.can.CaptureReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Arguments shared by {@code sanitize-capture} and {@code sanitize-vcds-log}, and how they settle on the VIN.
 * Values given with {@code --vin} and {@code --mask} are never echoed back.
 *
 * @param flags the command-specific flags that were given (e.g. {@code --mask-vcid})
 */
record SanitizeArgs(Path input, Path output, Optional<String> vin, Optional<Path> vinFrom, List<String> masks,
                    Set<String> flags) {

    static final String OUTPUT = "--output";
    static final String VIN = "--vin";
    static final String VIN_FROM = "--vin-from";
    static final String MASK = "--mask";

    static SanitizeArgs parse(List<String> args, Set<String> allowedFlags) throws UsageException {
        Path input = null;
        Path output = null;
        String vin = null;
        Path vinFrom = null;
        List<String> masks = new ArrayList<>();
        Set<String> flags = new LinkedHashSet<>();
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            String name = arg;
            String value = null;
            int eq = arg.indexOf('=');
            if (arg.startsWith("--") && eq > 0) {
                name = arg.substring(0, eq);
                value = arg.substring(eq + 1);
            }
            if (allowedFlags.contains(name)) {
                if (value != null) {
                    throw new UsageException(name + " takes no value");
                }
                flags.add(name);
                continue;
            }
            boolean takesValue = switch (name) {
                case OUTPUT, VIN, VIN_FROM, MASK -> true;
                default -> false;
            };
            if (!takesValue) {
                if (arg.startsWith("-")) {
                    throw new UsageException("unknown option '" + name + "'");
                }
                if (input != null) {
                    throw new UsageException("expected one input file");
                }
                input = path(arg, "input");
                continue;
            }
            if (value == null) {
                if (i + 1 >= args.size()) {
                    throw new UsageException(name + " needs a value");
                }
                value = args.get(++i);
            }
            switch (name) {
                case OUTPUT -> {
                    if (output != null) {
                        throw new UsageException(OUTPUT + " given more than once");
                    }
                    output = path(value, OUTPUT);
                }
                case VIN -> {
                    if (vin != null) {
                        throw new UsageException(VIN + " given more than once");
                    }
                    vin = value.strip().toUpperCase(Locale.ROOT);
                    if (!Vin.isWellFormed(vin)) {
                        throw new UsageException(VIN + " must be 17 characters: digits and letters except I, O, Q");
                    }
                }
                case VIN_FROM -> {
                    if (vinFrom != null) {
                        throw new UsageException(VIN_FROM + " given more than once");
                    }
                    vinFrom = path(value, VIN_FROM);
                }
                default -> masks.add(value);
            }
        }
        if (input == null) {
            throw new UsageException("missing the input file");
        }
        if (output == null) {
            throw new UsageException("missing " + OUTPUT + " <file>");
        }
        if (input.toAbsolutePath().normalize().equals(output.toAbsolutePath().normalize())) {
            throw new UsageException(OUTPUT + " must differ from the input");
        }
        return new SanitizeArgs(input, output, Optional.ofNullable(vin), Optional.ofNullable(vinFrom),
                List.copyOf(masks), Set.copyOf(flags));
    }

    private static Path path(String text, String what) throws UsageException {
        if (text.isBlank()) {
            throw new UsageException(what + " is empty");
        }
        try {
            return Path.of(text);
        } catch (InvalidPathException e) {
            throw new UsageException("bad " + what + " path: " + e.getReason());
        }
    }

    /**
     * The VIN given by {@code --vin} and/or {@code --vin-from} (they must agree), or empty if neither was given.
     * {@code --vin-from} takes a capture (VIN responses) or a VCDS log ({@code VIN:} fields).
     */
    Optional<String> givenVin() throws SanitizeException, IOException {
        if (vinFrom.isEmpty()) {
            return vin;
        }
        Set<String> found = vinsIn(vinFrom.get());
        if (found.isEmpty()) {
            throw new SanitizeException("no VIN found in " + vinFrom.get());
        }
        if (found.size() > 1) {
            throw new SanitizeException(found.size() + " different VINs found in " + vinFrom.get());
        }
        String fromFile = found.iterator().next();
        if (vin.isPresent() && !vin.get().equals(fromFile)) {
            throw new SanitizeException(VIN + " differs from the VIN in " + vinFrom.get());
        }
        return Optional.of(fromFile);
    }

    private static Set<String> vinsIn(Path file) throws IOException, SanitizeException {
        String text;
        try {
            text = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
        } catch (NoSuchFileException e) {
            throw new SanitizeException(file + " not found");
        }
        try {
            if (!CaptureReader.read(new StringReader(text)).frames().isEmpty()) {
                Set<String> vins = new LinkedHashSet<>();
                VinFinder.inResponses(DiagPayloads.reassemble(
                        CaptureReader.read(new StringReader(text)).canFrames())).forEach(f -> vins.add(f.vin()));
                return vins;
            }
        } catch (CaptureFormatException e) {
            // not a capture: read it as a VCDS log
        }
        return VinFinder.inVcdsLog(text);
    }

    /**
     * Settles on the VIN to mask.
     *
     * @param given from {@link #givenVin()}
     * @param detected VINs found in the input itself
     * @throws SanitizeException if the input holds a VIN other than the given one, several VINs, or none at all
     *     while none was given (then there would be nothing to verify against)
     */
    static String choose(Optional<String> given, Set<String> detected) throws SanitizeException {
        if (given.isPresent()) {
            for (String d : detected) {
                if (!d.equals(given.get())) {
                    throw new SanitizeException("the input holds a VIN different from the one given; refusing "
                            + "(pass the input's own VIN, or check it's from the same car)");
                }
            }
            return given.get();
        }
        if (detected.isEmpty()) {
            throw new SanitizeException("no VIN found in the input; pass " + VIN + " or " + VIN_FROM
                    + " <file> so the output can be verified not to contain it");
        }
        if (detected.size() > 1) {
            throw new SanitizeException(detected.size() + " different VINs found in the input; pass " + VIN
                    + " to say which car this is (the others would need a separate check)");
        }
        return detected.iterator().next();
    }
}
