package com.mineutze.worldgen.plan;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Independent attempts on fixed thread count, results in completion order. Every attempt counts, none cancelled for
 * another's success - solve-rate estimate needs that. Single caller thread: submit/take/running not thread-safe.
 */
final class IndependentAttemptPool<T> implements AutoCloseable {

    private final ExecutorService executor;
    private final CompletionService<T> completions;
    private int running;

    IndependentAttemptPool(int threads) {
        if (threads <= 0) {
            throw new IllegalArgumentException("IndependentAttemptPool: threads must be positive, got " + threads);
        }
        this.executor = Executors.newFixedThreadPool(threads, IndependentAttemptPool::daemonThread);
        this.completions = new ExecutorCompletionService<>(executor);
    }

    void submit(Callable<T> attempt) {
        completions.submit(attempt);
        running++;
    }

    /** Next finished result, or empty if none finishes within {@code maxWait}. A failed attempt throws, never skipped. */
    Optional<T> takeCompleted(Duration maxWait) {
        if (running == 0) {
            throw new IllegalStateException("IndependentAttemptPool: nothing is running, no result can ever arrive");
        }
        Future<T> finished = pollFor(maxWait);
        if (finished == null) {
            return Optional.empty();
        }
        running--;
        return Optional.of(resultOf(finished));
    }

    int running() {
        return running;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    /** Daemon: attempt ignoring interrupts must not keep JVM alive past verdict. */
    private static Thread daemonThread(Runnable work) {
        Thread thread = new Thread(work, "independent-attempt");
        thread.setDaemon(true);
        return thread;
    }

    private Future<T> pollFor(Duration maxWait) {
        try {
            return completions.poll(maxWait.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("IndependentAttemptPool: interrupted while waiting for an attempt", interrupted);
        }
    }

    private static <R> R resultOf(Future<R> finished) {
        try {
            return finished.get();
        } catch (ExecutionException failure) {
            throw new IllegalStateException("IndependentAttemptPool: an attempt failed: " + failure.getCause(), failure.getCause());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("IndependentAttemptPool: interrupted while reading a finished attempt", interrupted);
        }
    }
}
