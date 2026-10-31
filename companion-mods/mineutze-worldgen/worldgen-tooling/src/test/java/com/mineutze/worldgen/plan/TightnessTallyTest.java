package com.mineutze.worldgen.plan;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.mineutze.worldgen.plan.ToroidalTestTables.A;
import static com.mineutze.worldgen.plan.ToroidalTestTables.B;
import static com.mineutze.worldgen.plan.ToroidalTestTables.C;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TightnessTallyTest {

    private static final Obstacle PAIR = new LawObstacle(new IllegalPairLaw(A, B));
    private static final Obstacle MISSING = new MissingBiome(C);

    @Test
    void countsHowManyFailedAttemptsEachObstacleBlocked() {
        TightnessTally tally = new TightnessTally();
        tally.add(Set.of(PAIR, MISSING));
        tally.add(Set.of(PAIR));

        TightnessRanking ranking = tally.ranking();

        assertEquals(List.of(new RankedObstacle(PAIR, 2), new RankedObstacle(MISSING, 1)), ranking.entries());
    }

    @Test
    void countsFailedAttempts() {
        TightnessTally tally = new TightnessTally();
        tally.add(Set.of(PAIR));
        tally.add(Set.of());

        assertEquals(2, tally.ranking().failedAttempts());
    }

    @Test
    void tiesAreOrderedByDescriptionForStableOutput() {
        TightnessTally first = new TightnessTally();
        first.add(Set.of(MISSING, PAIR));
        TightnessTally second = new TightnessTally();
        second.add(Set.of(PAIR, MISSING));

        assertEquals(first.ranking(), second.ranking());
    }

    @Test
    void anEmptyTallyHasNoEntries() {
        assertEquals(List.of(), new TightnessTally().ranking().entries());
    }
}
