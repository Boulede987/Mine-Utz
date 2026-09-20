package com.mineutze.worldgen.coherence;

import com.mineutze.worldgen.biomegraph.BiomeId;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.mineutze.worldgen.coherence.ToroidalTestTables.A;
import static com.mineutze.worldgen.coherence.ToroidalTestTables.B;
import static com.mineutze.worldgen.coherence.ToroidalTestTables.C;
import static com.mineutze.worldgen.coherence.ToroidalTestTables.everyCellHolds;
import static com.mineutze.worldgen.coherence.ToroidalTestTables.threeColoring;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PatternClustersTest {

    @Test
    void aProperColoringMakesEveryCellItsOwnCluster() {
        ToroidalGrid grid = new ToroidalGrid(6, 6);

        PatternClusters clusters = PatternClusters.of(grid, threeColoring(grid, A, B, C));

        assertEquals(36, clusters.clusterCount());
        for (int size : clusters.cellsInCluster()) {
            assertEquals(1, size);
        }
    }

    @Test
    void aSingleBiomeEverywhereIsOneClusterHoldingEveryCell() {
        ToroidalGrid grid = new ToroidalGrid(6, 6);

        PatternClusters clusters = PatternClusters.of(grid, everyCellHolds(grid, A));

        assertEquals(1, clusters.clusterCount());
        assertEquals(36, clusters.cellsInCluster()[0]);
    }

    @Test
    void twoBandsAreTwoClusters() {
        ToroidalGrid grid = new ToroidalGrid(6, 6);
        Map<ToroidalPos, BiomeId> bands = new LinkedHashMap<>();
        for (ToroidalPos pos : grid.allPositionsInFillOrder()) {
            bands.put(pos, pos.q() < 3 ? A : B);
        }

        PatternClusters clusters = PatternClusters.of(grid, bands);

        assertEquals(2, clusters.clusterCount());
        assertEquals(18, clusters.cellsInCluster()[0]);
        assertEquals(18, clusters.cellsInCluster()[1]);
        assertNotEquals(clusters.clusterOfCell()[0], clusters.clusterOfCell()[5]);
    }

    /** Two cells of the same biome that do not touch are two clusters, not one: a cluster is a connected region. */
    @Test
    void sameBiomeCellsThatDoNotTouchAreSeparateClusters() {
        ToroidalGrid grid = new ToroidalGrid(6, 6);
        Map<ToroidalPos, BiomeId> pattern = new LinkedHashMap<>();
        for (ToroidalPos pos : grid.allPositionsInFillOrder()) {
            boolean isolatedA = (pos.q() == 0 && pos.r() == 0) || (pos.q() == 3 && pos.r() == 3);
            pattern.put(pos, isolatedA ? A : B);
        }

        PatternClusters clusters = PatternClusters.of(grid, pattern);

        assertEquals(3, clusters.clusterCount());
    }

    @Test
    void clusterOfCellIsIndexedByRowTimesWidthPlusColumn() {
        ToroidalGrid grid = new ToroidalGrid(6, 6);
        Map<ToroidalPos, BiomeId> pattern = threeColoring(grid, A, B, C);

        PatternClusters clusters = PatternClusters.of(grid, pattern);

        assertEquals(36, clusters.clusterOfCell().length);
        assertNotEquals(clusters.clusterOfCell()[0], clusters.clusterOfCell()[1]);
    }

    @Test
    void totalCellsIsTheGridSize() {
        ToroidalGrid grid = new ToroidalGrid(6, 6);

        assertEquals(36, PatternClusters.of(grid, everyCellHolds(grid, A)).totalCells());
    }
}
