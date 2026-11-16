package io.github.samsanjaysatish.macvagdiag.can;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A parsed VCDS text report (VCDS 26.9, as saved by the user's laptop on 2026-11-13): an Auto-Scan or a single
 * module's fault-code read. Test fixture only, for comparing our results with VCDS's.
 *
 * <p>What it reads:
 * <ul>
 *   <li>Header: the VIN line, if any.</li>
 *   <li>Auto-Scan status table: {@code 01-Engine -- Status: Malfunction 0010}.</li>
 *   <li>Each {@code Address xx: name} block: its info lines ({@code Part No SW: ... HW: ...}, {@code Component:},
 *       {@code Coding:}, ...; the single-module form's right-aligned keys too), "Cannot be reached", and its faults.</li>
 *   <li>Each fault: the VCDS number and description, then the code line. KWP2000:
 *       {@code P2BA6 - 000 - SCR Catalyst Malfunction - MIL ON} or {@code 004 - No Signal/Communication}; UDS:
 *       {@code B103E 1B [00001000] - Resistance Too High} followed by a status-words line
 *       ({@code Intermittent - Confirmed - Tested Since Memory Clear}). Then the {@code Freeze Frame:} blocks with
 *       their {@code label: value} lines, in order.</li>
 * </ul>
 * Descriptions (fault texts, failure-type texts) come from VCDS's own database; they are kept as text only.
 */
public record VcdsLog(Optional<String> vin, List<StatusLine> statusTable, List<Module> modules) {

    /** One line of the Auto-Scan status table. */
    public record StatusLine(String address, String name, String status, String bits) {
        /** E.g. {@code Malfunction 0010}, as VCDS prints it. */
        public String text() {
            return status + " " + bits;
        }
    }

    /** One {@code Address xx:} block. */
    public record Module(String address, String name, Map<String, String> info, boolean cannotBeReached,
            List<Fault> faults) {

        public Module {
            info = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(info));
            faults = List.copyOf(faults);
        }

        public Optional<String> info(String key) {
            return Optional.ofNullable(info.get(key));
        }

        /** {@code Part No SW} (or {@code Part No}, or the single-module {@code Control Module Part Number}). */
        public Optional<String> partNumber() {
            return info("Part No SW").or(() -> info("Part No")).or(() -> info("Control Module Part Number"));
        }

        /** {@code Coding} (or the single-module {@code Software Coding}). */
        public Optional<String> coding() {
            return info("Coding").or(() -> info("Software Coding"));
        }

        /** {@code Component} (or the single-module {@code Component and/or Version}). */
        public Optional<String> component() {
            return info("Component").or(() -> info("Component and/or Version"));
        }
    }

    /**
     * One fault as VCDS shows it.
     *
     * @param number the VCDS number, e.g. {@code 011174} (KWP2000), {@code 9453074} or {@code 0022} (UDS)
     * @param description VCDS's text for the number (its database)
     * @param code the P-code / SAE code if shown: {@code P2BA6} (KWP engine), {@code B103E} (UDS)
     * @param faultType KWP2000 only: the 3 digits after the code, e.g. {@code 000}
     * @param failureType UDS only: VCDS's failure type, e.g. {@code 1B} (from its database, not always the DTC's byte)
     * @param statusBits UDS only: the status byte VCDS prints in brackets, e.g. {@code 00001000}
     * @param flags KWP2000: the words after the text ({@code MIL ON}, {@code Intermittent}); UDS: the status-words
     *     line split at {@code " - "} ({@code Intermittent}, {@code Confirmed}, {@code Tested Since Memory Clear})
     * @param freezeFrames each {@code Freeze Frame:} block's {@code label: value} lines, in order
     */
    public record Fault(String number, String description, Optional<String> code, Optional<String> faultType,
            Optional<String> failureType, Optional<String> statusBits, String codeText, List<String> flags,
            List<List<Field>> freezeFrames) {

        public Fault {
            flags = List.copyOf(flags);
            freezeFrames = freezeFrames.stream().map(List::copyOf).toList();
        }

        /** Every freeze-frame line of every block, in order. */
        public List<Field> freezeFrameFields() {
            return freezeFrames.stream().flatMap(List::stream).toList();
        }

        /** The first freeze-frame value with this label, e.g. {@code Mileage} → {@code 362256 km}. */
        public Optional<String> freezeFrame(String label) {
            return freezeFrameFields().stream().filter(f -> f.label().equals(label)).map(Field::value).findFirst();
        }
    }

    /** {@code label: value}, e.g. {@code RPM} / {@code 828 /min}. */
    public record Field(String label, String value) {
        /** As VCDS prints it: {@code RPM: 828 /min}. */
        @Override
        public String toString() {
            return label + ": " + value;
        }
    }

    public VcdsLog {
        Objects.requireNonNull(vin, "vin");
        statusTable = List.copyOf(statusTable);
        modules = List.copyOf(modules);
    }

    public Optional<Module> module(String address) {
        return modules.stream().filter(m -> m.address().equalsIgnoreCase(address)).findFirst();
    }

    public Optional<StatusLine> status(String address) {
        return statusTable.stream().filter(s -> s.address().equalsIgnoreCase(address)).findFirst();
    }

    /** Every fault of every module. */
    public int faultCount() {
        return modules.stream().mapToInt(m -> m.faults().size()).sum();
    }

    private static final Pattern VIN = Pattern.compile("^VIN: (\\S+)");
    private static final Pattern STATUS = Pattern.compile("^([0-9A-F]{2})-(.+?) -- Status: (.+) ([01]{4})\\s*$");
    private static final Pattern ADDRESS = Pattern.compile("^\\s*Address ([0-9A-F]{2}): (.+?)(?:\\s{2,}Labels:.*)?\\s*$");
    private static final Pattern FAULT_COUNT = Pattern.compile("^(\\d+) Faults? Found:\\s*$");
    private static final Pattern FAULT = Pattern.compile("^(\\d{4,7}) - (.*?)\\s*$");
    private static final Pattern KWP_CODE = Pattern.compile("^(?:([PBCU][0-9A-F]{4}) - )?(\\d{3}) - (.*?)\\s*$");
    private static final Pattern UDS_CODE = Pattern.compile("^([PBCU][0-9A-F]{4}) ([0-9A-F]{2}) \\[([01]{8})\\] - (.*?)\\s*$");
    private static final Pattern FIELD = Pattern.compile("^\\s+([^:]+?): ?(.*?)\\s*$");
    /** Splits {@code Part No SW: 03L 997 030 M    HW: ...} before each next {@code Key:}; values may hold runs of spaces. */
    private static final Pattern INFO_SPLIT = Pattern.compile("\\s{3,}(?=[A-Za-z][A-Za-z #./-]*:)");
    /** The words VCDS appends to a KWP2000 code line. */
    private static final Set<String> KWP_FLAGS = Set.of("MIL ON", "Intermittent");

    /** Parses a whole report ({@code \n} line ends; see {@link RealCaptures#vcdsLog}). */
    public static VcdsLog parse(String text) {
        List<String> lines = text.lines().toList();
        Optional<String> vin = Optional.empty();
        List<StatusLine> table = new ArrayList<>();
        List<Module> modules = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            Matcher m;
            if (vin.isEmpty() && (m = VIN.matcher(line)).find()) {
                vin = Optional.of(m.group(1));
            } else if ((m = STATUS.matcher(line)).matches()) {
                table.add(new StatusLine(m.group(1), m.group(2).trim(), m.group(3).trim(), m.group(4)));
            } else if ((m = ADDRESS.matcher(line)).matches()) {
                int[] next = {i + 1};
                modules.add(module(m.group(1), m.group(2).trim(), lines, next));
                i = next[0];
                continue;
            }
            i++;
        }
        return new VcdsLog(vin, table, modules);
    }

    private static boolean endOfModule(String line) {
        return line.startsWith("-----") || line.startsWith("End---") || ADDRESS.matcher(line).matches();
    }

    private static Module module(String address, String name, List<String> lines, int[] at) {
        Map<String, String> info = new LinkedHashMap<>();
        boolean cannotBeReached = false;
        List<Fault> faults = new ArrayList<>();
        int i = at[0];
        // Info lines: until a blank line, the fault header or the end of the block.
        for (; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank() || endOfModule(line) || FAULT_COUNT.matcher(line).matches()
                    || line.startsWith("No fault code found")) {
                break;
            }
            if (line.trim().equals("Cannot be reached")) {
                cannotBeReached = true;
                continue;
            }
            for (String part : INFO_SPLIT.split(line.trim())) {
                int colon = part.indexOf(':');
                if (colon > 0) {
                    info.putIfAbsent(part.substring(0, colon).trim(), part.substring(colon + 1).trim());
                }
            }
        }
        // The rest of the block: subsystems (skipped), faults.
        for (; i < lines.size() && !endOfModule(lines.get(i)); i++) {
            String line = lines.get(i);
            if (line.trim().equals("Cannot be reached")) {
                cannotBeReached = true;
            }
            Matcher f = FAULT.matcher(line);
            if (f.matches()) {
                int[] next = {i + 1};
                faults.add(fault(f.group(1), f.group(2), lines, next));
                i = next[0] - 1;
            }
        }
        at[0] = i;
        return new Module(address, name, info, cannotBeReached, faults);
    }

    private static Fault fault(String number, String description, List<String> lines, int[] at) {
        int i = at[0];
        String codeLine = lines.get(i++).trim();
        Optional<String> code = Optional.empty();
        Optional<String> faultType = Optional.empty();
        Optional<String> failureType = Optional.empty();
        Optional<String> statusBits = Optional.empty();
        String codeText;
        List<String> flags = new ArrayList<>();
        Matcher u = UDS_CODE.matcher(codeLine);
        Matcher k = KWP_CODE.matcher(codeLine);
        if (u.matches()) {
            code = Optional.of(u.group(1));
            failureType = Optional.of(u.group(2));
            statusBits = Optional.of(u.group(3));
            codeText = u.group(4);
            // Optional "[New feature! ...]" notes, then the status-words line.
            while (i < lines.size() && lines.get(i).trim().startsWith("[")) {
                i++;
            }
            if (i < lines.size() && !lines.get(i).trim().startsWith("Freeze Frame:") && !lines.get(i).isBlank()) {
                flags.addAll(List.of(lines.get(i++).trim().split(" - ")));
            }
        } else if (k.matches()) {
            code = Optional.ofNullable(k.group(1));
            faultType = Optional.of(k.group(2));
            String rest = k.group(3);
            boolean stripped = true;
            while (stripped) {
                stripped = false;
                for (String flag : KWP_FLAGS) {
                    if (rest.endsWith(" - " + flag)) {
                        flags.addFirst(flag);
                        rest = rest.substring(0, rest.length() - flag.length() - 3);
                        stripped = true;
                    }
                }
            }
            codeText = rest;
        } else {
            throw new IllegalArgumentException("Unrecognised VCDS fault code line after " + number + ": " + codeLine);
        }
        List<List<Field>> freezeFrames = new ArrayList<>();
        List<Field> current = null;
        for (; i < lines.size(); i++) {
            String line = lines.get(i);
            if (endOfModule(line) || FAULT.matcher(line).matches() || line.startsWith("Readiness:")) {
                break;
            }
            if (line.trim().equals("Freeze Frame:")) {
                current = new ArrayList<>();
                freezeFrames.add(current);
                continue;
            }
            Matcher f = FIELD.matcher(line);
            if (current != null && !line.isBlank() && f.matches()) {
                current.add(new Field(f.group(1).trim(), f.group(2)));
            }
        }
        at[0] = i;
        return new Fault(number, description, code, faultType, failureType, statusBits, codeText, flags, freezeFrames);
    }
}
