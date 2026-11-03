package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.Arrays;
import java.util.List;

import static com.mineutze.worldgen.plan.ToroidalTestTables.A;
import static com.mineutze.worldgen.plan.ToroidalTestTables.B;
import static com.mineutze.worldgen.plan.ToroidalTestTables.C;
import static com.mineutze.worldgen.plan.ToroidalTestTables.noLegalTriangleCatalog;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifyPicksTest {

    private static final BiomeGraphData CATALOG = noLegalTriangleCatalog(500, 800);
    private static final TightnessRanking RANKING = new TightnessRanking(List.of(
        new RankedObstacle(new ShapeLimit(A), 9),
        new RankedObstacle(new LawObstacle(new IllegalPairLaw(A, C)), 8),
        new RankedObstacle(new LawObstacle(new IllegalPairLaw(B, C)), 7),
        new RankedObstacle(new LawObstacle(new IllegalPairLaw(A, B)), 6),
        new RankedObstacle(new LawObstacle(new SizeRangeLaw(A, 1, 10)), 5)
    ), 10);
    private static final int RANK_OF_SHAPE_LIMIT = 1;

    private static List<String> labelsOf(VerifyPicks picks) {
        return picks.loosenings().stream().map(LoosenedGraph::label).toList();
    }

    private static void assertRefused(Executable pick, String reason) {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, pick);
        assertTrue(refused.getMessage().contains(reason), refused.getMessage());
    }

    @Test
    void loosensExactlyThePickedLawsInPickOrder() {
        VerifyPicks picks = VerifyPicks.of(RANKING, CATALOG, List.of(5, 3));

        List<String> labels = labelsOf(picks);

        assertEquals(2, labels.size());
        assertTrue(labels.get(0).startsWith("widen " + A), labels.toString());
        assertTrue(labels.get(1).contains(B + " and " + C), labels.toString());
    }

    @Test
    void acceptsThreePicks() {
        assertEquals(VerifyPicks.MAX_PICKS, VerifyPicks.of(RANKING, CATALOG, List.of(2, 4, 5)).loosenings().size());
    }

    @Test
    void refusesNoPick() {
        assertRefused(() -> VerifyPicks.of(RANKING, CATALOG, List.of()), "pick 1 to " + VerifyPicks.MAX_PICKS);
    }

    @Test
    void refusesMoreThanThreePicks() {
        assertRefused(() -> VerifyPicks.of(RANKING, CATALOG, List.of(2, 3, 4, 5)), "pick 1 to " + VerifyPicks.MAX_PICKS);
    }

    @Test
    void refusesTheSameRankTwice() {
        assertRefused(() -> VerifyPicks.of(RANKING, CATALOG, List.of(2, 2)), "twice");
    }

    @Test
    void refusesAMissingRank() {
        assertRefused(() -> VerifyPicks.of(RANKING, CATALOG, Arrays.asList(2, null)), "missing rank");
    }

    @Test
    void refusesARankBelowOne() {
        assertRefused(() -> VerifyPicks.of(RANKING, CATALOG, List.of(0)), "outside the ranking");
    }

    @Test
    void refusesARankPastTheRanking() {
        assertRefused(() -> VerifyPicks.of(RANKING, CATALOG, List.of(6)), "outside the ranking");
    }

    @Test
    void refusesALawThatCannotBeLoosenedNamingIt() {
        assertRefused(() -> VerifyPicks.of(RANKING, CATALOG, List.of(RANK_OF_SHAPE_LIMIT)), "shape limit");
    }

    @Test
    void picksMadeByTheUserSkipNothing() {
        assertEquals(List.of(), VerifyPicks.of(RANKING, CATALOG, List.of(2)).skipped());
    }

    @Test
    void theDefaultTakesTheTopThreeLoosenable() {
        List<String> labels = labelsOf(VerifyPicks.topLoosenable(RANKING, CATALOG));

        assertEquals(VerifyPicks.MAX_PICKS, labels.size());
        assertTrue(labels.get(0).contains(A + " and " + C), labels.toString());
        assertTrue(labels.get(2).contains(A + " and " + B), labels.toString());
    }

    @Test
    void theDefaultNamesWhatItPassedOver() {
        assertEquals(List.of(new ShapeLimit(A).describe()), VerifyPicks.topLoosenable(RANKING, CATALOG).skipped());
    }

    /** Empty, not refused: the caller reports "nothing to measure" with what was skipped. */
    @Test
    void theDefaultWithNothingLoosenableIsEmpty() {
        TightnessRanking shapeOnly = new TightnessRanking(List.of(new RankedObstacle(new ShapeLimit(A), 9)), 10);

        VerifyPicks picks = VerifyPicks.topLoosenable(shapeOnly, CATALOG);

        assertEquals(List.of(), picks.loosenings());
        assertEquals(List.of(new ShapeLimit(A).describe()), picks.skipped());
    }
}
