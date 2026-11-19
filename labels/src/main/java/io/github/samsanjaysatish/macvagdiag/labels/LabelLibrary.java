package io.github.samsanjaysatish.macvagdiag.labels;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.ByteBuffer;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds a module's label file the way VCDS does (Ross-Tech's documented search order, see the root
 * {@code CLAUDE.md}, "Label files"), and parses it.
 *
 * <p>Folders are searched in the order given (e.g. a user folder, then the main one). In each folder, by file
 * name, case ignored:
 * <ol>
 *   <li>{@code TEST-NN.lbl}, {@code NN} the module address in hex ({@code 01}, {@code 5F});</li>
 *   <li>{@code XXX-XXX-XXX-SS.lbl}, the part number with suffix;</li>
 *   <li>{@code XXX-XXX-XXX.lbl}, without suffix;</li>
 *   <li>{@code AA-NN.lbl}, {@code AA} the part number's first two characters.</li>
 * </ol>
 * If nothing is found for the software part number, the same search runs with the hardware part number (VCDS
 * does this for the target car's DSG: software {@code 02E 300 062 B}, labels {@code 02E-927-770} from hardware
 * {@code 02E 927 770 AL}). The first file found is used, after following its redirects (suffix table, {@code ?} pattern, {@code HW}
 * pattern; the first matching line wins; up to {@link #MAX_REDIRECTS} hops). A redirect target is looked for in
 * every folder, in order. If no redirect matches, or the target is missing, the file itself is used.
 *
 * <p>A file named {@code X.clb} decoded to text is expected as {@code X.lbl} (the copy in
 * {@code local/label-files-decoded/}); a redirect naming {@code X.CLB} or {@code X.LBL} finds it either way.
 * Files are read once and cached. Thread-safe.
 */
public final class LabelLibrary {

    private static final Logger log = LoggerFactory.getLogger(LabelLibrary.class);

    static final int MAX_REDIRECTS = 4;
    /** Larger files are skipped (the biggest in our set is well under 1 MiB). */
    static final long MAX_FILE_BYTES = 4L * 1024 * 1024;

    /** One label file: on disk, or given as text (bundled demo labels). */
    private sealed interface Source permits OnDisk, InMemory {
        String fileName();

        String read() throws IOException;
    }

    private record OnDisk(Path path) implements Source {
        @Override
        public String fileName() {
            return path.getFileName().toString();
        }

        @Override
        public String read() throws IOException {
            if (Files.size(path) > MAX_FILE_BYTES) {
                throw new IOException("larger than " + MAX_FILE_BYTES + " bytes");
            }
            return decode(Files.readAllBytes(path));
        }
    }

    private record InMemory(String fileName, String text) implements Source {
        @Override
        public String read() {
            return text;
        }
    }

    private final List<Map<String, Source>> folders;
    private final Map<Source, LabelFile> cache = new ConcurrentHashMap<>();

    private LabelLibrary(List<Map<String, Source>> folders) {
        this.folders = List.copyOf(folders);
    }

    /**
     * One folder of files given as text, by file name (e.g. made-up labels bundled for demo mode). Names without
     * {@code .lbl} / {@code .clb} are ignored.
     */
    public static LabelLibrary ofTexts(Map<String, String> files) {
        Map<String, Source> index = new HashMap<>();
        files.forEach((name, text) -> {
            String key = baseName(name);
            if (key != null) {
                index.putIfAbsent(key, new InMemory(name, Objects.requireNonNull(text, name)));
            }
        });
        return new LabelLibrary(List.of(index));
    }

    /** No label files: every lookup is "none". */
    public static LabelLibrary empty() {
        return new LabelLibrary(List.of());
    }

    /**
     * A library over these folders, searched in order. A folder that doesn't exist is skipped with a warning.
     *
     * @throws IOException if a folder exists but can't be listed
     */
    public static LabelLibrary of(List<Path> folders) throws IOException {
        List<Map<String, Source>> indexes = new ArrayList<>();
        for (Path folder : folders) {
            if (!Files.isDirectory(folder)) {
                log.warn("label folder {} does not exist; skipped", folder);
                continue;
            }
            Map<String, Source> index = new HashMap<>();
            try (DirectoryStream<Path> files = Files.newDirectoryStream(folder)) {
                for (Path f : files) {
                    String key = baseName(f.getFileName().toString());
                    if (key != null && Files.isRegularFile(f)) {
                        index.putIfAbsent(key, new OnDisk(f));
                    }
                }
            }
            indexes.add(index);
        }
        return new LabelLibrary(indexes);
    }

    /** {@code 03L-906-022-CBE.lbl} / {@code .LBL} / {@code .clb} → {@code 03l-906-022-cbe}; other extensions null. */
    static String baseName(String fileName) {
        String lower = fileName.strip().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".lbl") || lower.endsWith(".clb")) {
            return lower.substring(0, lower.length() - 4);
        }
        return null;
    }

    /**
     * Looks up the labels for one module.
     *
     * @param address the module address, 0x01..0xFF
     * @param partNumber the part number it reports (software part number, as VCDS uses it)
     * @param hardwarePartNumber its hardware part number, if known (for {@code HW} redirects)
     */
    public LabelLookup lookup(int address, String partNumber, Optional<String> hardwarePartNumber) {
        Objects.requireNonNull(hardwarePartNumber, "hardwarePartNumber");
        if (address < 1 || address > 0xFF) {
            throw new IllegalArgumentException("module address must be 01..FF: " + address);
        }
        List<String> trail = new ArrayList<>();
        Optional<PartNumber> part = PartNumber.parse(partNumber);
        if (part.isEmpty()) {
            trail.add("no usable part number");
            return LabelLookup.none(trail);
        }
        Optional<PartNumber> hw = hardwarePartNumber.flatMap(PartNumber::parse);
        PartNumber p = part.get();
        Optional<Source> file = search(address, p);
        if (file.isPresent()) {
            return resolve(file.get(), p, hw, trail);
        }
        trail.add("no label file for " + p.dashed() + String.format(" at %02X", address));
        if (hw.isPresent() && !hw.get().equals(p)) {
            Optional<Source> byHardware = search(address, hw.get());
            if (byHardware.isPresent()) {
                trail.add("by hardware part number " + hw.get().dashed());
                return resolve(byHardware.get(), hw.get(), hw, trail);
            }
            trail.add("no label file for hardware part number " + hw.get().dashed());
        }
        return LabelLookup.none(trail);
    }

    /** The first candidate file for this part number, folder by folder. */
    private Optional<Source> search(int address, PartNumber p) {
        List<String> candidates = new ArrayList<>();
        candidates.add(String.format("TEST-%02X", address));
        if (!p.suffix().isEmpty()) {
            candidates.add(p.dashed());
        }
        candidates.add(p.dashedRoot());
        candidates.add(String.format("%s-%02X", p.prefix(), address));
        for (Map<String, Source> folder : folders) {
            for (String candidate : candidates) {
                Source file = folder.get(candidate.toLowerCase(Locale.ROOT));
                if (file != null) {
                    return Optional.of(file);
                }
            }
        }
        return Optional.empty();
    }

    private LabelLookup resolve(Source first, PartNumber part, Optional<PartNumber> hw, List<String> trail) {
        Source current = first;
        for (int hop = 0; ; hop++) {
            Optional<LabelFile> parsed = read(current);
            if (parsed.isEmpty()) {
                trail.add(current.fileName() + " (unreadable)");
                return LabelLookup.none(trail);
            }
            LabelFile file = parsed.get();
            trail.add(file.name());
            Optional<LabelFile.Redirect> redirect = file.redirectFor(part, hw);
            if (redirect.isEmpty()) {
                return new LabelLookup(Optional.of(MeasuringLabels.of(file.name(), file.groups())), trail);
            }
            if (hop >= MAX_REDIRECTS) {
                trail.add("more than " + MAX_REDIRECTS + " redirects; using " + file.name());
                return new LabelLookup(Optional.of(MeasuringLabels.of(file.name(), file.groups())), trail);
            }
            Optional<Source> target = findAnywhere(redirect.get().target());
            if (target.isEmpty()) {
                trail.add("redirect target " + redirect.get().target() + " not found; using " + file.name());
                return new LabelLookup(Optional.of(MeasuringLabels.of(file.name(), file.groups())), trail);
            }
            if (target.get().equals(current)) {
                return new LabelLookup(Optional.of(MeasuringLabels.of(file.name(), file.groups())), trail);
            }
            current = target.get();
        }
    }

    private Optional<Source> findAnywhere(String fileName) {
        String key = baseName(fileName);
        if (key == null) {
            key = fileName.strip().toLowerCase(Locale.ROOT);
        }
        for (Map<String, Source> folder : folders) {
            Source p = folder.get(key);
            if (p != null) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    private Optional<LabelFile> read(Source source) {
        LabelFile cached = cache.get(source);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            LabelFile file = LabelFile.parse(source.fileName(), source.read());
            cache.putIfAbsent(source, file);
            return Optional.of(file);
        } catch (IOException e) {
            log.warn("could not read label file {}: {}", source.fileName(), e.toString());
            return Optional.empty();
        }
    }

    /** Windows-1252; bytes it doesn't define become U+FFFD instead of failing the file. */
    static String decode(byte[] bytes) throws CharacterCodingException {
        return LabelFile.CHARSET.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }
}
