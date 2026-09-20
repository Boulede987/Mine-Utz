package com.mineutze.worldgen.coherence;

import com.mineutze.worldgen.biomegraph.BiomeId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.mineutze.worldgen.coherence.ToroidalTestTables.A;
import static com.mineutze.worldgen.coherence.ToroidalTestTables.B;
import static com.mineutze.worldgen.coherence.ToroidalTestTables.C;
import static com.mineutze.worldgen.coherence.ToroidalTestTables.threeColoring;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BiomeMapRasterizerTest {

    private static final ToroidalGrid GRID = new ToroidalGrid(6, 6);
    private static final HexTorusGeometry GEOMETRY = new HexTorusGeometry(6, 6, 300);
    private static final List<BiomeId> ORDER = List.of(A, B, C);
    private static final int SAMPLES_PER_SIDE = 60;

    private static Map<ToroidalPos, BiomeId> pattern() {
        return threeColoring(GRID, A, B, C);
    }

    private static int[] samplesPerCell(BiomeMap map) {
        int[] counts = new int[GRID.cellCount()];
        for (int j = 0; j < map.size(); j++) {
            for (int i = 0; i < map.size(); i++) {
                counts[map.cellAt(i, j)]++;
            }
        }
        return counts;
    }

    /**
     * 60 samples per side over a 6x6 tile: 3,600 samples for 36 cells, so 100 each. The sample lattice
     * is a whole number of steps per cell, but in this symmetric test lattice many samples fall exactly on a
     * hexagon edge and floating-point rounding tips each one either way, so shares differ by a few samples
     * (measured 97 to 103) - never by more than that, and never by losing a sample.
     */
    @Test
    void withoutAWarpEveryCellReceivesAboutTheSameNumberOfSamples() {
        BiomeMap map = BiomeMapRasterizer.rasterize(GRID, pattern(), GEOMETRY, DivergenceFreeWarp.none(GEOMETRY), SAMPLES_PER_SIDE, ORDER);

        int[] counts = samplesPerCell(map);

        for (int count : counts) {
            assertEquals(100, count, 5);
        }
        assertEquals(SAMPLES_PER_SIDE * SAMPLES_PER_SIDE, java.util.Arrays.stream(counts).sum());
    }

    @Test
    void eachSampleCarriesTheBiomeOfTheCellItCameFrom() {
        Map<ToroidalPos, BiomeId> pattern = pattern();
        BiomeMap map = BiomeMapRasterizer.rasterize(GRID, pattern, GEOMETRY, DivergenceFreeWarp.none(GEOMETRY), SAMPLES_PER_SIDE, ORDER);

        for (int j = 0; j < map.size(); j += 7) {
            for (int i = 0; i < map.size(); i += 5) {
                int cell = map.cellAt(i, j);
                BiomeId expected = pattern.get(new ToroidalPos(cell % GRID.width(), cell / GRID.width()));
                assertEquals(expected, map.biomes().get(map.biomeAt(i, j)), "sample " + i + "," + j);
            }
        }
    }

    @Test
    void aWarpMovesSamplesBetweenCellsButLosesNone() {
        DivergenceFreeWarp warp = new DivergenceFreeWarp(GEOMETRY, 3, new DivergenceFreeWarp.Settings(16, 60, 800, 40, 0.8));
        BiomeMap map = BiomeMapRasterizer.rasterize(GRID, pattern(), GEOMETRY, warp, SAMPLES_PER_SIDE, ORDER);

        int[] counts = samplesPerCell(map);

        assertEquals(SAMPLES_PER_SIDE * SAMPLES_PER_SIDE, java.util.Arrays.stream(counts).sum());
        assertTrue(java.util.Arrays.stream(counts).anyMatch(count -> count != 100), "a strong warp should shift at least one border sample");
    }

    @Test
    void rasterizingTwiceGivesTheSameMapDespiteTheParallelWork() {
        DivergenceFreeWarp warp = new DivergenceFreeWarp(GEOMETRY, 4, new DivergenceFreeWarp.Settings(16, 60, 800, 40, 0.8));

        BiomeMap first = BiomeMapRasterizer.rasterize(GRID, pattern(), GEOMETRY, warp, SAMPLES_PER_SIDE, ORDER);
        BiomeMap second = BiomeMapRasterizer.rasterize(GRID, pattern(), GEOMETRY, warp, SAMPLES_PER_SIDE, ORDER);

        assertArrayEquals(first.biomeIndices(), second.biomeIndices());
        assertArrayEquals(first.cells(), second.cells());
    }

    /** The map is nothing but the field sampled on a lattice: every sample must equal the field's answer at that sample's position. */
    @Test
    void everySampleEqualsTheFieldAtItsPosition() {
        DivergenceFreeWarp warp = new DivergenceFreeWarp(GEOMETRY, 5, new DivergenceFreeWarp.Settings(16, 60, 800, 30, 0.8, 4));
        BiomeMap map = BiomeMapRasterizer.rasterize(GRID, pattern(), GEOMETRY, warp, SAMPLES_PER_SIDE, ORDER);
        RealizedBiomeField field = new RealizedBiomeField(GRID, pattern(), GEOMETRY, warp, ORDER);

        for (int j = 0; j < SAMPLES_PER_SIDE; j += 3) {
            for (int i = 0; i < SAMPLES_PER_SIDE; i += 2) {
                double u = (i + 0.5) / SAMPLES_PER_SIDE;
                double v = (j + 0.5) / SAMPLES_PER_SIDE;
                double x = u * GEOMETRY.tileVectorAX() + v * GEOMETRY.tileVectorBX();
                double y = u * GEOMETRY.tileVectorAY() + v * GEOMETRY.tileVectorBY();
                assertEquals(field.cellIndexAt(x, y), map.cellAt(i, j), "sample " + i + "," + j);
                assertEquals(field.biomeIndexAt(x, y), map.biomeAt(i, j), "sample " + i + "," + j);
            }
        }
    }

    @Test
    void sampleAreasAddUpToTheTile() {
        BiomeMap map = BiomeMapRasterizer.rasterize(GRID, pattern(), GEOMETRY, DivergenceFreeWarp.none(GEOMETRY), SAMPLES_PER_SIDE, ORDER);

        assertEquals(GEOMETRY.tileAreaBlocks2(), map.sampleAreaBlocks2() * SAMPLES_PER_SIDE * SAMPLES_PER_SIDE, 1e-3);
    }

    @Test
    void indexingWrapsAroundTheTile() {
        BiomeMap map = BiomeMapRasterizer.rasterize(GRID, pattern(), GEOMETRY, DivergenceFreeWarp.none(GEOMETRY), SAMPLES_PER_SIDE, ORDER);

        assertEquals(map.biomeAt(0, 0), map.biomeAt(SAMPLES_PER_SIDE, 0));
        assertEquals(map.biomeAt(0, 0), map.biomeAt(0, SAMPLES_PER_SIDE));
        assertEquals(map.biomeAt(SAMPLES_PER_SIDE - 1, 5), map.biomeAt(-1, 5));
    }

    @Test
    void rejectsABiomeMissingFromTheColorOrder() {
        assertThrows(
            IllegalArgumentException.class,
            () -> BiomeMapRasterizer.rasterize(GRID, pattern(), GEOMETRY, DivergenceFreeWarp.none(GEOMETRY), SAMPLES_PER_SIDE, List.of(A, B))
        );
    }

    @Test
    void rejectsAPatternThatDoesNotCoverTheGrid() {
        Map<ToroidalPos, BiomeId> incomplete = pattern();
        incomplete.remove(new ToroidalPos(0, 0));

        assertThrows(
            IllegalArgumentException.class,
            () -> BiomeMapRasterizer.rasterize(GRID, incomplete, GEOMETRY, DivergenceFreeWarp.none(GEOMETRY), SAMPLES_PER_SIDE, ORDER)
        );
    }

    @Test
    void rejectsANonPositiveSampleCount() {
        assertThrows(
            IllegalArgumentException.class,
            () -> BiomeMapRasterizer.rasterize(GRID, pattern(), GEOMETRY, DivergenceFreeWarp.none(GEOMETRY), 0, ORDER)
        );
    }

    @Test
    void differentCellsDoGetDifferentBiomes() {
        BiomeMap map = BiomeMapRasterizer.rasterize(GRID, pattern(), GEOMETRY, DivergenceFreeWarp.none(GEOMETRY), SAMPLES_PER_SIDE, ORDER);

        assertNotEquals(map.biomeAt(0, 0), map.biomeAt(10, 0));
    }
}
