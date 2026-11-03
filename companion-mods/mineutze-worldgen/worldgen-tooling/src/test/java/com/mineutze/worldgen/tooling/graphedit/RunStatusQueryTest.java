package com.mineutze.worldgen.tooling.graphedit;

import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RunStatusQueryTest {

    @Test
    void readsRunAndFrom() {
        assertEquals(new RunStatusQuery(OptionalInt.of(3), 12), RunStatusQuery.parse("run=3&from=12"));
    }

    @Test
    void runIsOptional() {
        assertEquals(new RunStatusQuery(OptionalInt.empty(), 0), RunStatusQuery.parse("from=0"));
    }

    @Test
    void refusesAMissingFrom() {
        assertThrows(IllegalArgumentException.class, () -> RunStatusQuery.parse("run=3"));
    }

    @Test
    void refusesNoQuery() {
        assertThrows(IllegalArgumentException.class, () -> RunStatusQuery.parse(null));
    }

    @Test
    void refusesAnUnknownKey() {
        assertThrows(IllegalArgumentException.class, () -> RunStatusQuery.parse("frm=5"));
    }

    @Test
    void refusesANonNumber() {
        assertThrows(IllegalArgumentException.class, () -> RunStatusQuery.parse("from=abc"));
    }

    @Test
    void refusesANegativeIndex() {
        assertThrows(IllegalArgumentException.class, () -> RunStatusQuery.parse("from=-1"));
    }

    @Test
    void refusesAKeyWithoutValue() {
        assertThrows(IllegalArgumentException.class, () -> RunStatusQuery.parse("from"));
    }

    @Test
    void keepsThePagesIndexForTheRunItAskedAbout() throws InterruptedException {
        GradleRun run = finishedRun();

        assertEquals(5, new RunStatusQuery(OptionalInt.of(run.number()), 5).fromIndexIn(run));
    }

    /** Another tab started a new run: this page must get that run from its first line, not from its old index. */
    @Test
    void startsFromTheFirstLineOfAnotherRun() throws InterruptedException {
        GradleRun run = finishedRun();

        assertEquals(0, new RunStatusQuery(OptionalInt.of(run.number() + 1), 5).fromIndexIn(run));
    }

    @Test
    void startsFromTheFirstLineWhenNoRunIsNamed() throws InterruptedException {
        GradleRun run = finishedRun();

        assertEquals(0, new RunStatusQuery(OptionalInt.empty(), 5).fromIndexIn(run));
    }

    private static GradleRun finishedRun() throws InterruptedException {
        GradleRun run = GradleRun.start("printer", LinePrinter.printsAndExits(1, 0));
        RunWaits.waitUntilFinished(run);
        return run;
    }
}
