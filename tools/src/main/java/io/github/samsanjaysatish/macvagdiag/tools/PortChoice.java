package io.github.samsanjaysatish.macvagdiag.tools;

import io.github.samsanjaysatish.macvagdiag.can.SlcanPortDiscovery.Candidate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The serial port the logger will use, and the firmware build for the capture header.
 *
 * <p>The firmware version comes from the USB product description ({@code "CANable2 <git version> <remote>"}
 * for the stock firmware), never from sending {@code V}: the logger sends nothing to the adapter just to
 * learn its version.
 *
 * @param path e.g. {@code /dev/cu.usbmodem14101}
 * @param firmware firmware description for the header, or {@code unknown}
 */
record PortChoice(String path, String firmware) {

    static final String UNKNOWN_FIRMWARE = "unknown";

    /**
     * Uses {@code requested} if given; otherwise the only CANable (USB {@code 16D0:117E}) among
     * {@code candidates}. Zero or several CANables without {@code --port} is an error.
     */
    static PortChoice resolve(Optional<String> requested, List<Candidate> candidates) throws UsageException {
        if (requested.isPresent()) {
            String path = requested.get();
            Optional<Candidate> match = candidates.stream().filter(c -> path.equals(c.path())).findFirst();
            return new PortChoice(path, match.map(PortChoice::firmwareOf).orElse(UNKNOWN_FIRMWARE));
        }
        List<Candidate> canables = candidates.stream().filter(Candidate::isCanable).toList();
        return switch (canables.size()) {
            case 0 -> throw new UsageException("no CANable found (USB 16D0:117E). Plug it in, or pass --port; "
                    + "run 'list-ports' to see candidate ports");
            case 1 -> new PortChoice(canables.getFirst().path(), firmwareOf(canables.getFirst()));
            default -> throw new UsageException("more than one CANable found; pass --port with one of: "
                    + canables.stream().map(Candidate::path).collect(Collectors.joining(", ")));
        };
    }

    private static String firmwareOf(Candidate candidate) {
        String description = candidate.description();
        if (description == null || description.isBlank()) {
            return UNKNOWN_FIRMWARE;
        }
        String oneLine = description.strip().replaceAll("\\s+", " ");
        return candidate.isCanable() ? oneLine : UNKNOWN_FIRMWARE + " (USB description: " + oneLine + ")";
    }
}
