package com.mineutze.worldgen.tooling.graphedit;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/** Bounded waits for GradleRun tests: a run that never ends fails the test instead of hanging the suite. */
final class RunWaits {

    private static final Duration PATIENCE = Duration.ofSeconds(20);
    private static final long POLL_INTERVAL_MILLIS = 20;

    private RunWaits() {
    }

    static void waitUntil(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + PATIENCE.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out after " + PATIENCE + " waiting for " + what);
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
    }

    static void waitUntilFinished(GradleRun run) throws InterruptedException {
        waitUntil(run::finished, "run " + run.label() + " to finish");
    }
}
