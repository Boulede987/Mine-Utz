package com.mineutze.worldgen.tooling.graphedit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static com.mineutze.worldgen.tooling.graphedit.LinePrinter.printsAndExits;
import static com.mineutze.worldgen.tooling.graphedit.LinePrinter.printsForever;
import static com.mineutze.worldgen.tooling.graphedit.RunWaits.waitUntilFinished;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrentRunTest {

    private final CurrentRun current = new CurrentRun();

    @AfterEach
    void stopTheCurrentRun() {
        current.current().ifPresent(GradleRun::stop);
    }

    @Test
    void refusesASecondRunWhileOneIsGoing() {
        current.start("first", printsForever()).orElseThrow();

        Optional<GradleRun> second = current.start("second", printsAndExits(1, 0));

        assertTrue(second.isEmpty());
    }

    @Test
    void startsANewRunOnceTheLastOneFinished() throws InterruptedException {
        waitUntilFinished(current.start("first", printsAndExits(1, 0)).orElseThrow());

        Optional<GradleRun> second = current.start("second", printsAndExits(1, 0));

        assertTrue(second.isPresent());
    }

    /** A reloaded page asks for the current run instead of starting a new one. */
    @Test
    void theCurrentRunStaysAvailableAfterItFinished() throws InterruptedException {
        GradleRun run = current.start("only", printsAndExits(1, 0)).orElseThrow();
        waitUntilFinished(run);

        assertEquals(Optional.of(run), current.current());
    }

    @Test
    void nothingIsCurrentBeforeTheFirstRun() {
        assertEquals(Optional.empty(), current.current());
    }
}
