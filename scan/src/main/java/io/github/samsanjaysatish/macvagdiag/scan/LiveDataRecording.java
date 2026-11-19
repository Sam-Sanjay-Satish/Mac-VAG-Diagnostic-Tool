package io.github.samsanjaysatish.macvagdiag.scan;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Chosen live values read repeatedly for a while: a summary per value, and every sample in a CSV file.
 *
 * @param id the recording's ID (also the CSV file's name)
 * @param module the module
 * @param status how it went ({@link LiveDataSnapshot.Status}; {@code PARTIAL} if it ended early)
 * @param labelSource the label file the names came from; empty without labels
 * @param series one summary per requested value, in request order
 * @param rounds how many times every chosen group was read
 * @param requested the duration asked for
 * @param recorded how long it really ran
 * @param file the CSV with every sample (time, then one column per value), if one was written
 * @param notes readable notes (why it ended early, what failed)
 * @param limitHit a limit that ended it early, if any
 * @param requests requests sent to the car
 * @param startedAt when it started
 * @param finishedAt when it ended
 */
public record LiveDataRecording(String id, ModuleAddress module, LiveDataSnapshot.Status status,
        Optional<String> labelSource, List<LiveSeries> series, int rounds, Duration requested, Duration recorded,
        Optional<Path> file, List<String> notes, Optional<LimitHit> limitHit, int requests, Instant startedAt,
        Instant finishedAt) {

    public LiveDataRecording {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(labelSource, "labelSource");
        series = List.copyOf(series);
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(recorded, "recorded");
        Objects.requireNonNull(file, "file");
        notes = List.copyOf(notes);
        Objects.requireNonNull(limitHit, "limitHit");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(finishedAt, "finishedAt");
    }
}
