package com.mineutze.worldgen.coherence;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphJson;
import com.mineutze.worldgen.biomegraph.BiomeId;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.mineutze.worldgen.coherence.ToroidalTestTables.A;
import static com.mineutze.worldgen.coherence.ToroidalTestTables.B;
import static com.mineutze.worldgen.coherence.ToroidalTestTables.C;
import static com.mineutze.worldgen.coherence.ToroidalTestTables.D;
import static com.mineutze.worldgen.coherence.ToroidalTestTables.fullyLegalTable;
import static com.mineutze.worldgen.coherence.ToroidalTestTables.tableWithNoLegalTriangle;

class ToroidalIdentityLocalSearchTest {

    private static final long GENEROUS_MILLIS = 10_000;
    private static final long STALL_STEPS = 500_000;
    private static final long SEED = 1234L;

    /** A proper 3-coloring of the hex torus: color = (q - r) mod 3 differs across every neighbor direction. Only well-defined when both sides are multiples of 3. */
    private static Map<ToroidalPos, BiomeId> threeColoring(ToroidalGrid grid, BiomeId first, BiomeId second, BiomeId third) {
        List<BiomeId> colors = List.of(first, second, third);
        Map<ToroidalPos, BiomeId> assignment = new LinkedHashMap<>();
        for (ToroidalPos pos : grid.allPositionsInFillOrder()) {
            assignment.put(pos, colors.get(Math.floorMod(pos.q() - pos.r(), 3)));
        }
        return assignment;
    }

    private static Map<ToroidalPos, BiomeId> everyCellHolds(ToroidalGrid grid, BiomeId biome) {
        Map<ToroidalPos, BiomeId> assignment = new LinkedHashMap<>();
        for (ToroidalPos pos : grid.allPositionsInFillOrder()) {
            assignment.put(pos, biome);
        }
        return assignment;
    }

    @Test
    void verifyAcceptsALegalTilingThatCoversTheRequiredBiomes() {
        ToroidalGrid grid = new ToroidalGrid(3, 3);
        RawRuleTable table = fullyLegalTable(A, B, C);

        assertDoesNotThrow(() -> ToroidalIdentityLocalSearch.verify(table, grid, threeColoring(grid, A, B, C), Set.of(A, B, C)));
    }

    @Test
    void verifyRejectsTwoAdjacentCellsHoldingTheSameBiome() {
        ToroidalGrid grid = new ToroidalGrid(3, 3);
        RawRuleTable table = fullyLegalTable(A, B, C);

        IllegalStateException failure = assertThrows(
            IllegalStateException.class, () -> ToroidalIdentityLocalSearch.verify(table, grid, everyCellHolds(grid, A), Set.of())
        );

        assertTrue(failure.getMessage().contains("illegal adjacency"), failure.getMessage());
    }

    @Test
    void verifyRejectsAnAdjacencyThatIsNotInTheRuleTable() {
        ToroidalGrid grid = new ToroidalGrid(3, 3);
        RawRuleTable table = tableWithNoLegalTriangle();
        Map<ToroidalPos, BiomeId> assignment = threeColoring(grid, A, B, C);

        assertThrows(IllegalStateException.class, () -> ToroidalIdentityLocalSearch.verify(table, grid, assignment, Set.of()));
    }

    @Test
    void verifyRejectsATilingThatMissesARequiredBiome() {
        ToroidalGrid grid = new ToroidalGrid(3, 3);
        RawRuleTable table = fullyLegalTable(A, B, C, D);

        IllegalStateException failure = assertThrows(
            IllegalStateException.class, () -> ToroidalIdentityLocalSearch.verify(table, grid, threeColoring(grid, A, B, C), Set.of(A, B, C, D))
        );

        assertTrue(failure.getMessage().contains(D.toString()), "message should name the missing biome: " + failure.getMessage());
    }

    @Test
    void verifyRejectsAnAssignmentThatDoesNotCoverEveryCell() {
        ToroidalGrid grid = new ToroidalGrid(3, 3);
        RawRuleTable table = fullyLegalTable(A, B, C);
        Map<ToroidalPos, BiomeId> partial = threeColoring(grid, A, B, C);
        partial.remove(new ToroidalPos(0, 0));

        assertThrows(IllegalStateException.class, () -> ToroidalIdentityLocalSearch.verify(table, grid, partial, Set.of()));
    }

    @Test
    void illegalEdgeDescriptionsListsEachIllegalEdgeExactlyOnce() {
        ToroidalGrid grid = new ToroidalGrid(3, 3);
        RawRuleTable table = fullyLegalTable(A, B, C);

        List<String> violations = ToroidalIdentityLocalSearch.illegalEdgeDescriptions(table, grid, everyCellHolds(grid, A));

        assertEquals(27, violations.size(), "a 3x3 hex torus has 9 cells * 6 neighbors / 2 = 27 edges, all illegal here");
        assertEquals(27, new HashSet<>(violations).size(), "no edge reported twice");
    }

    @Test
    void illegalEdgeDescriptionsIsEmptyForALegalTiling() {
        ToroidalGrid grid = new ToroidalGrid(3, 3);
        RawRuleTable table = fullyLegalTable(A, B, C);

        assertTrue(ToroidalIdentityLocalSearch.illegalEdgeDescriptions(table, grid, threeColoring(grid, A, B, C)).isEmpty());
    }

    @Test
    void searchSolvesASolvableInstanceAndTheResultPassesIndependentVerification() {
        ToroidalGrid grid = new ToroidalGrid(4, 4);
        RawRuleTable table = fullyLegalTable(A, B, C, D);

        ToroidalIdentityLocalSearch.Result result = ToroidalIdentityLocalSearch.search(table, grid, SEED, GENEROUS_MILLIS, STALL_STEPS);

        assertNotNull(result.assignment(), "a 4-biome complete graph on a 4x4 torus is solvable");
        assertEquals(16, result.assignment().size());
        assertDoesNotThrow(() -> ToroidalIdentityLocalSearch.verify(table, grid, result.assignment(), Set.of(A, B, C, D)));
        assertEquals(0, result.bestIllegalEdges());
        assertEquals(0, result.bestMissingBiomes());
    }

    @Test
    void searchOnlyRequiresTheBiomesItIsAskedToCover() {
        ToroidalGrid grid = new ToroidalGrid(4, 4);
        RawRuleTable table = fullyLegalTable(A, B, C, D);

        ToroidalIdentityLocalSearch.Result result = ToroidalIdentityLocalSearch.search(table, grid, SEED, GENEROUS_MILLIS, STALL_STEPS, Set.of(D));

        assertNotNull(result.assignment());
        assertTrue(result.assignment().containsValue(D), "the one required biome must appear");
        assertDoesNotThrow(() -> ToroidalIdentityLocalSearch.verify(table, grid, result.assignment(), Set.of(D)));
    }

    @Test
    void searchReportsFailureInsteadOfAFalseSolutionWhenNoLegalTilingExists() {
        ToroidalGrid grid = new ToroidalGrid(4, 4);
        RawRuleTable table = tableWithNoLegalTriangle();

        ToroidalIdentityLocalSearch.Result result = ToroidalIdentityLocalSearch.search(table, grid, SEED, 300, STALL_STEPS, Set.of());

        assertNull(result.assignment(), "with no mutually legal triple there is no legal hex tiling, so a 'solution' would be a lie");
        assertTrue(result.bestIllegalEdges() > 0, "the closest state must still contain a violation");
    }

    @Test
    void searchReturnsItsClosestStateWhenItFails() {
        ToroidalGrid grid = new ToroidalGrid(4, 4);
        RawRuleTable table = tableWithNoLegalTriangle();

        ToroidalIdentityLocalSearch.Result result = ToroidalIdentityLocalSearch.search(table, grid, SEED, 300, STALL_STEPS, Set.of());

        assertNotNull(result.closestAssignment());
        assertEquals(16, result.closestAssignment().size(), "the closest state assigns every cell");
        assertEquals(
            result.bestIllegalEdges(),
            ToroidalIdentityLocalSearch.illegalEdgeDescriptions(table, grid, result.closestAssignment()).size(),
            "the reported violation count must match the returned state"
        );
    }

    @Test
    void searchWithinStepsSolvesASolvableInstance() {
        ToroidalGrid grid = new ToroidalGrid(4, 4);
        RawRuleTable table = fullyLegalTable(A, B, C, D);

        ToroidalIdentityLocalSearch.Result result = ToroidalIdentityLocalSearch.searchWithinSteps(table, grid, SEED, 5_000_000, STALL_STEPS, Set.of(A, B, C, D));

        assertNotNull(result.assignment());
        assertDoesNotThrow(() -> ToroidalIdentityLocalSearch.verify(table, grid, result.assignment(), Set.of(A, B, C, D)));
    }

    @Test
    void searchWithinStepsNeverExceedsItsStepBudget() {
        ToroidalGrid grid = new ToroidalGrid(4, 4);
        RawRuleTable table = tableWithNoLegalTriangle();
        long stepBudget = 50_000;

        ToroidalIdentityLocalSearch.Result result = ToroidalIdentityLocalSearch.searchWithinSteps(table, grid, SEED, stepBudget, STALL_STEPS, Set.of());

        assertNull(result.assignment());
        assertTrue(result.steps() <= stepBudget, "took " + result.steps() + " steps against a budget of " + stepBudget);
        assertTrue(result.bestIllegalEdges() > 0);
    }

    /** A step budget, unlike a wall-clock limit, must give the exact same outcome on every run and every machine - a world seed's result cannot depend on how busy the server was. */
    @Test
    void searchWithinStepsGivesTheIdenticalOutcomeOnEveryRunEvenWhenItFails() {
        ToroidalGrid grid = new ToroidalGrid(4, 4);
        RawRuleTable table = tableWithNoLegalTriangle();

        ToroidalIdentityLocalSearch.Result first = ToroidalIdentityLocalSearch.searchWithinSteps(table, grid, SEED, 30_000, STALL_STEPS, Set.of());
        ToroidalIdentityLocalSearch.Result second = ToroidalIdentityLocalSearch.searchWithinSteps(table, grid, SEED, 30_000, STALL_STEPS, Set.of());

        assertEquals(first.steps(), second.steps());
        assertEquals(first.closestAssignment(), second.closestAssignment());
    }

    @Test
    void sameSeedProducesTheSameAssignment() {
        ToroidalGrid grid = new ToroidalGrid(4, 4);
        RawRuleTable table = fullyLegalTable(A, B, C, D);

        Map<ToroidalPos, BiomeId> first = ToroidalIdentityLocalSearch.search(table, grid, SEED, GENEROUS_MILLIS, STALL_STEPS).assignment();
        Map<ToroidalPos, BiomeId> second = ToroidalIdentityLocalSearch.search(table, grid, SEED, GENEROUS_MILLIS, STALL_STEPS).assignment();

        assertEquals(first, second, "a world seed must always yield the same pattern");
    }

    /**
     * Regression: the exact-window repair can finish the whole problem in the MIDDLE of a step. The
     * rest of that step then tried to pick a conflicted cell from an empty set and crashed with
     * "bound must be positive". Found by a 150-run reliability sweep; this seed reproduced it. The
     * trigger depends on the search's exact random sequence, so if the search's tuning constants
     * change this seed may stop exercising the path - re-find one with the reliability runner.
     */
    @Test
    void searchSurvivesTheWindowRepairSolvingTheProblemMidStep() {
        BiomeGraphData data = BiomeGraphJson.loadResource(RawRuleTable.class, "/biomegraph/biome-graph.json");
        RawRuleTable table = RawRuleTable.from(data);
        ToroidalGrid grid = new ToroidalGrid(20, 20);

        ToroidalIdentityLocalSearch.Result result = assertDoesNotThrow(() -> ToroidalIdentityLocalSearch.search(table, grid, 7_000_022L, 30_000, STALL_STEPS));

        assertNotNull(result.assignment());
        assertDoesNotThrow(() -> ToroidalIdentityLocalSearch.verify(table, grid, result.assignment(), Set.copyOf(table.allBiomeIds())));
    }

    @Test
    void searchSolvesTheRealCatalogWithAllBiomesOnA24By24Torus() {
        BiomeGraphData data = BiomeGraphJson.loadResource(RawRuleTable.class, "/biomegraph/biome-graph.json");
        RawRuleTable table = RawRuleTable.from(data);
        ToroidalGrid grid = new ToroidalGrid(24, 24);

        ToroidalIdentityLocalSearch.Result result = ToroidalIdentityLocalSearch.search(table, grid, SEED, 30_000, STALL_STEPS);

        assertNotNull(result.assignment(), "measured: 50/50 seeds solved at this size, median 0.36s");
        assertDoesNotThrow(() -> ToroidalIdentityLocalSearch.verify(table, grid, result.assignment(), Set.copyOf(table.allBiomeIds())));
        assertEquals(table.allBiomeIds().size(), new HashSet<>(result.assignment().values()).size(), "every catalog biome present");
    }
}
