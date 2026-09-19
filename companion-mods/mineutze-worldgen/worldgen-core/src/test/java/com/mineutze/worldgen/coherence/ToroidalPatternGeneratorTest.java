package com.mineutze.worldgen.coherence;

import com.mineutze.worldgen.biomegraph.BiomeGraphJson;
import com.mineutze.worldgen.biomegraph.BiomeId;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static com.mineutze.worldgen.coherence.ToroidalTestTables.A;
import static com.mineutze.worldgen.coherence.ToroidalTestTables.B;
import static com.mineutze.worldgen.coherence.ToroidalTestTables.C;
import static com.mineutze.worldgen.coherence.ToroidalTestTables.D;
import static com.mineutze.worldgen.coherence.ToroidalTestTables.fullyLegalTable;
import static com.mineutze.worldgen.coherence.ToroidalTestTables.tableWithNoLegalTriangle;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToroidalPatternGeneratorTest {

    private static final int REAL_GRID_SIZE = 24;

    private static RawRuleTable realCatalog() {
        return RawRuleTable.from(BiomeGraphJson.loadResource(RawRuleTable.class, "/biomegraph/biome-graph.json"));
    }

    @Test
    void generatesALegalPatternCoveringEveryCatalogBiome() {
        RawRuleTable table = realCatalog();
        ToroidalGrid grid = new ToroidalGrid(REAL_GRID_SIZE, REAL_GRID_SIZE);

        Map<ToroidalPos, BiomeId> pattern = ToroidalPatternGenerator.generate(table, grid, 42L);

        assertDoesNotThrow(() -> ToroidalIdentityLocalSearch.verify(table, grid, pattern, Set.copyOf(table.allBiomeIds())));
    }

    @Test
    void sameWorldSeedAlwaysYieldsTheSamePattern() {
        RawRuleTable table = fullyLegalTable(A, B, C, D);
        ToroidalGrid grid = new ToroidalGrid(4, 4);

        Map<ToroidalPos, BiomeId> first = ToroidalPatternGenerator.generate(table, grid, 99L);
        Map<ToroidalPos, BiomeId> second = ToroidalPatternGenerator.generate(table, grid, 99L);

        assertEquals(first, second);
    }

    /** The variety worry, made measurable: two different worlds must not be near-copies of each other with a few cells swapped. */
    @Test
    void differentWorldSeedsYieldPatternsThatDifferInMostCells() {
        RawRuleTable table = realCatalog();
        ToroidalGrid grid = new ToroidalGrid(REAL_GRID_SIZE, REAL_GRID_SIZE);

        Map<ToroidalPos, BiomeId> first = ToroidalPatternGenerator.generate(table, grid, 1L);
        Map<ToroidalPos, BiomeId> second = ToroidalPatternGenerator.generate(table, grid, 2L);

        long differingCells = first.keySet().stream().filter(pos -> !first.get(pos).equals(second.get(pos))).count();
        double differingFraction = (double) differingCells / grid.cellCount();
        assertTrue(differingFraction >= 0.5, "only " + differingCells + " of " + grid.cellCount() + " cells differ between two different world seeds");
    }

    @Test
    void failsLoudlyWithFullReproContextWhenNoLegalPatternExists() {
        RawRuleTable table = tableWithNoLegalTriangle();
        ToroidalGrid grid = new ToroidalGrid(4, 4);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> ToroidalPatternGenerator.generate(table, grid, 777L, 20_000L));

        String message = failure.getMessage();
        assertTrue(message.contains("777"), "message must name the world seed: " + message);
        assertTrue(message.contains("4x4"), "message must name the grid size: " + message);
        assertTrue(message.contains("within 20000 steps"), "message must name the step budget itself, not just the steps taken: " + message);
    }

    @Test
    void rejectsANonPositiveStepBudget() {
        RawRuleTable table = fullyLegalTable(A, B, C, D);
        ToroidalGrid grid = new ToroidalGrid(4, 4);

        assertThrows(IllegalArgumentException.class, () -> ToroidalPatternGenerator.generate(table, grid, 1L, 0L));
    }
}
