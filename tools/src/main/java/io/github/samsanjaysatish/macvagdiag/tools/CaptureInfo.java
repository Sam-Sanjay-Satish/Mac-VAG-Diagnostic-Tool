package io.github.samsanjaysatish.macvagdiag.tools;

import io.github.samsanjaysatish.macvagdiag.can.CanFrame;
import io.github.samsanjaysatish.macvagdiag.can.Capture;
import io.github.samsanjaysatish.macvagdiag.can.CapturedFrame;
import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Summary of a capture, for checking a sniffing session before leaving the car.
 *
 * @param metadata the header's {@code # key: value} lines
 * @param frameCount number of frames
 * @param first timestamp of the first frame
 * @param last timestamp of the last frame
 * @param largestGap longest silence between consecutive frames (a long one may mean a dropped connection)
 * @param idCounts frames per CAN ID, standard IDs first, then by ID
 */
record CaptureInfo(Map<String, String> metadata, int frameCount, Optional<Instant> first, Optional<Instant> last,
                   Duration largestGap, SortedMap<CanId, Long> idCounts) {

    /** A CAN ID with its format. Prints as 3 hex digits (standard) or 8 (extended), as in candump. */
    record CanId(int id, boolean extended) implements Comparable<CanId> {

        private static final Comparator<CanId> ORDER =
                Comparator.comparing(CanId::extended).thenComparingInt(CanId::id);

        @Override
        public int compareTo(CanId other) {
            return ORDER.compare(this, other);
        }

        @Override
        public String toString() {
            return extended ? String.format("%08X", id) : String.format("%03X", id);
        }
    }

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS xxx", Locale.ROOT);

    CaptureInfo {
        metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        idCounts = Collections.unmodifiableSortedMap(new TreeMap<>(idCounts));
    }

    static CaptureInfo of(Capture capture) {
        SortedMap<CanId, Long> counts = new TreeMap<>();
        Duration largestGap = Duration.ZERO;
        Instant previous = null;
        for (CapturedFrame f : capture.frames()) {
            CanFrame frame = f.frame();
            counts.merge(new CanId(frame.id(), frame.isExtended()), 1L, Long::sum);
            if (previous != null) {
                Duration gap = Duration.between(previous, f.timestamp());
                if (gap.compareTo(largestGap) > 0) {
                    largestGap = gap;
                }
            }
            previous = f.timestamp();
        }
        Optional<Instant> first = capture.frames().stream().findFirst().map(CapturedFrame::timestamp);
        Optional<Instant> last = Optional.ofNullable(capture.frames().isEmpty() ? null
                : capture.frames().getLast().timestamp());
        return new CaptureInfo(capture.metadata(), capture.frames().size(), first, last, largestGap, counts);
    }

    /** Last minus first timestamp; zero for fewer than two frames. */
    Duration duration() {
        if (first.isEmpty() || last.isEmpty()) {
            return Duration.ZERO;
        }
        Duration d = Duration.between(first.get(), last.get());
        return d.isNegative() ? Duration.ZERO : d;
    }

    void print(PrintStream out, String name, ZoneId zone) {
        out.println("Capture:      " + name);
        metadata.forEach((key, value) -> out.println("  " + key + ": " + value));
        out.println("Frames:       " + frameCount);
        out.println("First frame:  " + first.map(t -> TIMESTAMP.format(t.atZone(zone))).orElse("-"));
        out.println("Last frame:   " + last.map(t -> TIMESTAMP.format(t.atZone(zone))).orElse("-"));
        out.println("Duration:     " + seconds(duration()));
        out.println("Largest gap:  " + seconds(largestGap));
        out.println("Distinct IDs: " + idCounts.size());
        if (!idCounts.isEmpty()) {
            out.println(String.format(Locale.ROOT, "  %-8s  %s", "ID", "frames"));
            idCounts.forEach((id, count) -> out.println(String.format(Locale.ROOT, "  %-8s  %d", id, count)));
        }
    }

    private static String seconds(Duration d) {
        return String.format(Locale.ROOT, "%.3f s", d.toNanos() / 1e9);
    }
}
