package io.github.samsanjaysatish.macvagdiag.scan;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A running or finished Auto-Scan. Thread-safe: the web page can poll {@link #events()} / {@link #result()} while
 * the scan runs, and {@link #await()} blocks for the end.
 */
public final class AutoScanJob {

    private static final Logger log = LoggerFactory.getLogger(AutoScanJob.class);

    private final String id;
    private final Caller caller;
    private final Instant startedAt;
    private final int totalModules;
    private final ScanProgressListener listener;
    private final CompletableFuture<AutoScanResult> future = new CompletableFuture<>();
    private final List<ScanEvent> events = new CopyOnWriteArrayList<>();
    private final AtomicBoolean cancel = new AtomicBoolean();

    AutoScanJob(String id, Caller caller, Instant startedAt, int totalModules, ScanProgressListener listener) {
        this.id = Objects.requireNonNull(id, "id");
        this.caller = Objects.requireNonNull(caller, "caller");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.totalModules = totalModules;
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * A job that has already finished with {@code result}, with the progress events a real scan would have sent
     * (also delivered to {@code listener}). For fakes and front-end tests; it talks to no car.
     */
    public static AutoScanJob completed(AutoScanResult result, ScanProgressListener listener) {
        List<ModuleResult> modules = result.modules();
        AutoScanJob job = new AutoScanJob(result.scanId(), result.caller(), result.startedAt(), modules.size(),
                listener == null ? ScanProgressListener.NONE : listener);
        job.emit(new ScanEvent.Started(job.id, modules.size(), result.startedAt()));
        for (int i = 0; i < modules.size(); i++) {
            ModuleResult m = modules.get(i);
            job.emit(new ScanEvent.ModuleStarted(job.id, i + 1, modules.size(), m.module(), m.startedAt()));
            job.emit(new ScanEvent.ModuleFinished(job.id, i + 1, modules.size(), m, m.finishedAt()));
        }
        job.complete(result);
        return job;
    }

    /** A job that has already failed. For fakes and front-end tests. */
    public static AutoScanJob failed(String id, Caller caller, Instant at, RuntimeException failure) {
        AutoScanJob job = new AutoScanJob(id, caller, at, ModuleAddress.known().size(), ScanProgressListener.NONE);
        job.fail(failure, at);
        return job;
    }

    public String id() {
        return id;
    }

    public Caller caller() {
        return caller;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public int totalModules() {
        return totalModules;
    }

    /** Every progress event so far, in order. */
    public List<ScanEvent> events() {
        return List.copyOf(events);
    }

    public Optional<ScanEvent> lastEvent() {
        return events.isEmpty() ? Optional.empty() : Optional.of(events.getLast());
    }

    /** Modules finished so far. */
    public int completedModules() {
        return (int) events.stream().filter(e -> e instanceof ScanEvent.ModuleFinished).count();
    }

    /** The module being scanned right now, if any. */
    public Optional<ModuleAddress> currentModule() {
        return lastEvent().filter(e -> e instanceof ScanEvent.ModuleStarted)
                .map(e -> ((ScanEvent.ModuleStarted) e).module());
    }

    public boolean isDone() {
        return future.isDone();
    }

    /** The result, once finished successfully. */
    public Optional<AutoScanResult> result() {
        return future.isDone() && !future.isCompletedExceptionally() ? Optional.of(future.join()) : Optional.empty();
    }

    /** Why it failed (e.g. a {@link ScanRefusedException}), once failed. */
    public Optional<RuntimeException> failure() {
        if (!future.isCompletedExceptionally()) {
            return Optional.empty();
        }
        try {
            future.join();
            return Optional.empty();
        } catch (java.util.concurrent.CompletionException e) {
            return Optional.of(e.getCause() instanceof RuntimeException re ? re : e);
        }
    }

    /** Blocks until the scan ends. A failure is rethrown as it was, usually a {@link ScanRefusedException}. */
    public AutoScanResult await() throws InterruptedException {
        try {
            return future.get();
        } catch (ExecutionException e) {
            throw unwrap(e);
        }
    }

    /** {@link #await()} with a real-time limit. */
    public AutoScanResult await(Duration timeout) throws InterruptedException, TimeoutException {
        try {
            return future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (ExecutionException e) {
            throw unwrap(e);
        }
    }

    /** A read-only view of completion, for async front ends. */
    public CompletionStage<AutoScanResult> completion() {
        return future.minimalCompletionStage();
    }

    /**
     * Asks the scan to stop. It finishes the module it is on (its session is closed normally), marks the rest
     * skipped and completes with {@code cancelled = true}.
     */
    public void cancel() {
        cancel.set(true);
    }

    public boolean cancelRequested() {
        return cancel.get();
    }

    void emit(ScanEvent event) {
        events.add(event);
        try {
            listener.onEvent(event);
        } catch (RuntimeException e) {
            log.warn("Auto-Scan progress listener failed on {}", event, e);
        }
    }

    void complete(AutoScanResult result) {
        emit(new ScanEvent.Finished(id, result, result.finishedAt()));
        future.complete(result);
    }

    void fail(RuntimeException failure, Instant at) {
        emit(new ScanEvent.Failed(id, String.valueOf(failure.getMessage()), at));
        future.completeExceptionally(failure);
    }

    private static RuntimeException unwrap(ExecutionException e) {
        if (e.getCause() instanceof RuntimeException re) {
            return re;
        }
        if (e.getCause() instanceof Error err) {
            throw err;
        }
        return new IllegalStateException(e.getCause());
    }

    @Override
    public String toString() {
        return "AutoScanJob[" + id + ", " + (isDone() ? "done" : completedModules() + "/" + totalModules) + "]";
    }
}
