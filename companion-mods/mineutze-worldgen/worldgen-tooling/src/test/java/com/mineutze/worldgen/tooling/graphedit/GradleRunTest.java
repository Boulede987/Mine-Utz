package com.mineutze.worldgen.tooling.graphedit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static com.mineutze.worldgen.tooling.graphedit.LinePrinter.CHILD_PREFIX;
import static com.mineutze.worldgen.tooling.graphedit.LinePrinter.printsAndExits;
import static com.mineutze.worldgen.tooling.graphedit.LinePrinter.printsForever;
import static com.mineutze.worldgen.tooling.graphedit.LinePrinter.printsForeverWithChild;
import static com.mineutze.worldgen.tooling.graphedit.RunWaits.waitUntil;
import static com.mineutze.worldgen.tooling.graphedit.RunWaits.waitUntilFinished;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GradleRunTest {

    private final List<GradleRun> started = new ArrayList<>();

    private GradleRun start(List<String> command) {
        GradleRun run = GradleRun.start("printer", command);
        started.add(run);
        return run;
    }

    @AfterEach
    void stopEveryRun() {
        started.forEach(GradleRun::stop);
    }

    @Test
    void showsLinesWhileTheRunIsStillGoing() throws InterruptedException {
        GradleRun run = start(printsForever());

        waitUntil(() -> run.lineCount() >= 1, "a first line");

        assertFalse(run.finished(), "a run printing for ever must still be running when its first line shows");
    }

    @Test
    void linesFromReturnsOnlyLinesAfterTheGivenIndex() throws InterruptedException {
        GradleRun run = start(printsAndExits(3, 0));
        waitUntilFinished(run);

        assertEquals(List.of("line 2", "line 3"), run.linesFrom(1));
    }

    @Test
    void keepsTheExitCode() throws InterruptedException {
        GradleRun run = start(printsAndExits(1, 7));
        waitUntilFinished(run);

        assertEquals(OptionalInt.of(7), run.exitCode());
    }

    @Test
    void stopMarksTheRunStopped() throws InterruptedException {
        GradleRun run = start(printsForever());
        waitUntil(() -> run.lineCount() >= 1, "a first line");

        run.stop();
        waitUntilFinished(run);

        assertTrue(run.stopped());
    }

    /** Gradle forks the real work into child JVMs: Stop must not leave them running. */
    @Test
    void stopAlsoEndsChildProcesses() throws InterruptedException {
        GradleRun run = start(printsForeverWithChild());
        waitUntil(() -> run.lineCount() >= 1 && run.linesFrom(0).get(0).startsWith(CHILD_PREFIX), "the child's pid");
        long childPid = Long.parseLong(run.linesFrom(0).get(0).substring(CHILD_PREFIX.length()));

        run.stop();

        waitUntil(() -> ProcessHandle.of(childPid).map(handle -> !handle.isAlive()).orElse(true), "the child process to end");
    }

    @Test
    void aRunThatEndsByItselfIsNotStopped() throws InterruptedException {
        GradleRun run = start(printsAndExits(1, 0));
        waitUntilFinished(run);

        assertFalse(run.stopped());
    }

    /** Stop pressed just as the run ended must not turn a real result into STOPPED. */
    @Test
    void stopAfterTheRunEndedChangesNothing() throws InterruptedException {
        GradleRun run = start(printsAndExits(1, 0));
        waitUntilFinished(run);

        assertFalse(run.stop());
        assertFalse(run.stopped());
    }

    @Test
    void eachRunGetsItsOwnNumber() throws InterruptedException {
        GradleRun first = start(printsAndExits(1, 0));
        GradleRun second = start(printsAndExits(1, 0));

        assertNotEquals(first.number(), second.number());
    }
}
