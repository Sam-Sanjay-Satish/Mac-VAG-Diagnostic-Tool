package io.github.samsanjaysatish.macvagdiag.scan;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * The single request queue: one worker thread runs jobs one at a time, in the order submitted (FIFO). Every job
 * that touches the adapter runs here, so all callers share one queue and the car sees one request at a time.
 * Callers block (in real time, not on the scan clock) until their job is done.
 */
final class RequestQueue {

    static final String WORKER_NAME = "macvagdiag-scan-worker";

    @FunctionalInterface
    interface Job<T> {
        T run() throws InterruptedException;
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, WORKER_NAME);
        t.setDaemon(true);
        return t;
    });

    /**
     * Runs {@code job} on the worker and waits for it. Exceptions from the job are rethrown as they were.
     *
     * @throws ScanRefusedException ({@code SERVICE_CLOSED}) if the queue is shut down
     * @throws InterruptedException if the calling thread is interrupted while waiting; a job not yet started is
     *     cancelled, a running one finishes normally (its session is closed as usual)
     */
    <T> T call(Job<T> job) throws InterruptedException {
        if (Thread.currentThread().getName().equals(WORKER_NAME)) {
            throw new IllegalStateException("A queue job can't wait for another job");
        }
        Future<T> f;
        try {
            f = worker.submit(job::run);
        } catch (RejectedExecutionException e) {
            throw ScanRefusedException.serviceClosed();
        }
        try {
            return f.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            if (cause instanceof InterruptedException) {
                throw ScanRefusedException.serviceClosed(); // the worker was interrupted: shutting down
            }
            throw new IllegalStateException(cause);
        } catch (CancellationException e) {
            throw ScanRefusedException.serviceClosed();
        } catch (InterruptedException e) {
            f.cancel(false);
            throw e;
        }
    }

    /** Stops taking jobs, cancels queued ones and interrupts the running one. */
    void shutdownNow() {
        worker.shutdownNow();
    }

    boolean awaitTermination(Duration timeout) throws InterruptedException {
        return worker.awaitTermination(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }
}
