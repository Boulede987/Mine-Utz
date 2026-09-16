package com.mineutze.worldgen.coherence;

import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.coherence.FailureModeCharacterization.Domination;
import com.mineutze.worldgen.coherence.PeriodicRowLayout.Cell;
import com.mineutze.worldgen.coherence.PeriodicRowLayout.Row;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailureModeCharacterizationTest {

    @Test
    void dominationOfPicksTheWidestSingleBiome() {
        BiomeId a = new BiomeId("test", "a");
        BiomeId b = new BiomeId("test", "b");
        Row row = new Row(List.of(new Cell(a, 8, 0), new Cell(b, 2, 8)));
        Domination domination = FailureModeCharacterization.dominationOf(row);
        assertEquals(a, domination.dominantBiome());
        assertEquals(0.8, domination.fraction(), 1e-9);
    }

    @Test
    void dominationOfSumsRepeatedAppearancesOfTheSameBiome() {
        BiomeId a = new BiomeId("test", "a");
        BiomeId b = new BiomeId("test", "b");
        BiomeId c = new BiomeId("test", "c");
        // A appears twice (3+3=6), total row width 10 -> A dominates at 0.6, not any single cell's own 0.3.
        Row row = new Row(List.of(new Cell(a, 3, 0), new Cell(b, 2, 3), new Cell(a, 3, 5), new Cell(c, 2, 8)));
        Domination domination = FailureModeCharacterization.dominationOf(row);
        assertEquals(a, domination.dominantBiome());
        assertEquals(0.6, domination.fraction(), 1e-9);
    }

    @Test
    void flagsAsRiskyIsTrueAtOrAboveTheThreshold() {
        BiomeId a = new BiomeId("test", "a");
        BiomeId b = new BiomeId("test", "b");
        Row risky = new Row(List.of(new Cell(a, 9, 0), new Cell(b, 1, 9))); // 0.9 dominance
        assertTrue(FailureModeCharacterization.flagsAsRisky(risky));
    }

    @Test
    void flagsAsRiskyIsTrueExactlyAtTheThreshold() {
        BiomeId a = new BiomeId("test", "a");
        BiomeId b = new BiomeId("test", "b");
        Row exactlyHalf = new Row(List.of(new Cell(a, 5, 0), new Cell(b, 5, 5))); // 0.5 exactly - threshold is ">=", so flagged
        assertTrue(FailureModeCharacterization.flagsAsRisky(exactlyHalf));
    }

    @Test
    void flagsAsRiskyIsFalseBelowTheThreshold() {
        BiomeId a = new BiomeId("test", "a");
        BiomeId b = new BiomeId("test", "b");
        BiomeId c = new BiomeId("test", "c");
        // A: 4, B: 3, C: 3 -> max fraction 0.4, clearly below the 0.5 threshold.
        Row lowDomination = new Row(List.of(new Cell(a, 4, 0), new Cell(b, 3, 4), new Cell(c, 3, 7)));
        assertFalse(FailureModeCharacterization.flagsAsRisky(lowDomination));
    }

    @Test
    void dominantHubGraphIsStructurallyAStarTopology() {
        RawRuleTable table = FailureModeCharacterization.dominantHubGraph();
        assertEquals(5, table.allBiomeIds().size());
        BiomeId hub = new BiomeId("synthetic", "dominant_hub");
        long hubDegree = table.allBiomeIds().stream().filter(id -> table.isLegal(hub, id)).count();
        assertEquals(4, hubDegree, "hub should be legal to exactly its 4 leaves");
        // No two leaves should be legal to each other - a real star, not a denser graph.
        List<BiomeId> leaves = table.allBiomeIds().stream().filter(id -> !id.equals(hub)).toList();
        for (BiomeId leafA : leaves) {
            for (BiomeId leafB : leaves) {
                if (!leafA.equals(leafB)) {
                    assertFalse(table.isLegal(leafA, leafB), leafA + " and " + leafB + " should not be legal to each other");
                }
            }
        }
    }

    /**
     * Real regression check, not just a structural assumption: confirms the "sure to fail"
     * fixture actually behaves as documented (an anchor row can be built, but no successor
     * ever can) - catches it if a future change accidentally makes this fixture stop being
     * pathological, same as this class's own doc claims.
     */
    @Test
    void dominantHubGraphReliablyProducesARowWithNoSuccessor() {
        RawRuleTable table = FailureModeCharacterization.dominantHubGraph();
        double rowHeightBlocks = PeriodicCoherenceProof.defaultRowHeightBlocks(table);
        Random random = new Random(42);
        Row anchor = PeriodicCoherenceProof.tryBuildAnchorRow(table, new java.util.HashSet<>(), 6, 16, rowHeightBlocks, random, 300);
        assertTrue(anchor != null, "the star topology should still allow building ONE row");
        Row successor = PeriodicCoherenceProof.tryBuildLegalRow(
            table, new java.util.HashSet<>(), anchor, null, 6, 16, rowHeightBlocks, anchor.totalWidthBlocks(), random, 2000
        );
        assertEquals(null, successor, "the dominant-hub graph should never find a legal successor");
    }
}
