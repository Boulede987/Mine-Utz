package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.AdjacencyRule;
import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static com.mineutze.worldgen.regionsolve.TestBiomeNodes.id;
import static com.mineutze.worldgen.regionsolve.TestBiomeNodes.node;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BorderBlendTest {

    private static final BiomeId DESERT = id("desert");
    private static final BiomeId SAVANNA = id("savanna");
    private static final BiomeId PLAINS = id("plains");
    private static final MacroGridConfig GRID = new MacroGridConfig(64, 16);

    private static final Map<BiomeId, Double> VALUE_BY_BIOME = Map.of(DESERT, 0.0, SAVANNA, 10.0, PLAINS, 100.0);

    private static double valueOf(BiomeId biome) {
        return VALUE_BY_BIOME.get(biome);
    }

    private static BiomeGraph graphWithBlendWidths(int desertSavannaBlend, int desertPlainsBlend) {
        return new BiomeGraph(
            List.of(node("desert", 200, 300, false), node("savanna", 200, 300, false), node("plains", 100, 200, true)),
            List.of(
                AdjacencyRule.allow(DESERT, SAVANNA, desertSavannaBlend),
                AdjacencyRule.allow(PLAINS, DESERT, desertPlainsBlend),
                AdjacencyRule.allow(PLAINS, SAVANNA, desertPlainsBlend)
            )
        );
    }

    /** desert everywhere except one savanna cell directly east. */
    private static BiomeId biomeAtDesertEastOfSavannaBorder(MacroCellPos cell) {
        return cell.x() == 1 && cell.z() == 0 ? SAVANNA : DESERT;
    }

    @Test
    void farFromAnyBorderEqualsCenterCellValueExactly() {
        BiomeGraph graph = graphWithBlendWidths(10, 10);

        double value = BorderBlend.blendedValue(
            32, 32, GRID, graph, BorderBlendTest::biomeAtDesertEastOfSavannaBorder, BorderBlendTest::valueOf
        );

        assertEquals(0.0, value, "cell center, far from the east border, must be pure desert");
    }

    @Test
    void rightAtTheBorderIsFullyTheNeighborsValue() {
        BiomeGraph graph = graphWithBlendWidths(10, 10);

        // blockX=63 is the last column of cell (0,0), directly against the savanna cell (1,0).
        double value = BorderBlend.blendedValue(
            63, 32, GRID, graph, BorderBlendTest::biomeAtDesertEastOfSavannaBorder, BorderBlendTest::valueOf
        );

        assertEquals(10.0, value, 0.001, "right at the shared edge should read as fully the neighbor's value");
    }

    @Test
    void blendIsMonotonicApproachingTheBorder() {
        BiomeGraph graph = graphWithBlendWidths(10, 10);

        double far = BorderBlend.blendedValue(50, 32, GRID, graph, BorderBlendTest::biomeAtDesertEastOfSavannaBorder, BorderBlendTest::valueOf);
        double mid = BorderBlend.blendedValue(58, 32, GRID, graph, BorderBlendTest::biomeAtDesertEastOfSavannaBorder, BorderBlendTest::valueOf);
        double near = BorderBlend.blendedValue(62, 32, GRID, graph, BorderBlendTest::biomeAtDesertEastOfSavannaBorder, BorderBlendTest::valueOf);

        assertTrue(far < mid && mid < near, "value should increase monotonically toward the higher-valued neighbor");
    }

    @Test
    void widerBlendWidthReachesFurtherFromTheBorder() {
        BiomeGraph narrowGraph = graphWithBlendWidths(5, 10);
        BiomeGraph wideGraph = graphWithBlendWidths(30, 10);

        // localX=53, cellSize=64: 10 blocks from the east border, still in the east half so the
        // east neighbor (savanna) is the one being blended toward. Inside a 30-wide blend, outside a 5-wide one.
        double narrow = BorderBlend.blendedValue(53, 32, GRID, narrowGraph, BorderBlendTest::biomeAtDesertEastOfSavannaBorder, BorderBlendTest::valueOf);
        double wide = BorderBlend.blendedValue(53, 32, GRID, wideGraph, BorderBlendTest::biomeAtDesertEastOfSavannaBorder, BorderBlendTest::valueOf);

        assertEquals(0.0, narrow, "10 blocks from the border is outside a 5-block blend zone");
        assertTrue(wide > 0.0, "10 blocks from the border is inside a 30-block blend zone");
    }

    @Test
    void sameBiomeOnBothSidesNeverBlends() {
        BiomeGraph graph = graphWithBlendWidths(10, 10);

        // All-desert field: even right at a "border" between two desert cells, nothing to blend.
        double value = BorderBlend.blendedValue(63, 32, GRID, graph, cell -> DESERT, BorderBlendTest::valueOf);

        assertEquals(0.0, value);
    }

    @Test
    void cornerBlendsBetweenAllFourTouchingCells() {
        BiomeGraph graph = graphWithBlendWidths(20, 20);
        // desert (0,0) | savanna (1,0)
        // plains  (0,1) | plains  (1,1)
        Function<MacroCellPos, BiomeId> biomeAt = cell -> {
            if (cell.x() == 0 && cell.z() == 0) {
                return DESERT;
            }
            if (cell.x() == 1 && cell.z() == 0) {
                return SAVANNA;
            }
            return PLAINS;
        };

        // Right at the shared corner (last column/row of cell (0,0)): should be pulled toward
        // savanna (east) and plains (south/diagonal), not stay pure desert.
        double value = BorderBlend.blendedValue(63, 63, GRID, graph, biomeAt, BorderBlendTest::valueOf);

        assertTrue(value > 0.0, "corner point should blend away from pure desert (0.0) toward its other 3 neighbors");
    }

    @Test
    void throwsWhenAnOrthogonalNeighborIsIllegallyAdjacent() {
        // A cross-region solve race once let a forest cell end up directly touching a
        // desert one with no rule between them, and this crashed world gen - which is the
        // intended behavior (a real bug surfacing loudly beats silently blending as if
        // nothing were wrong). desert/forest deliberately has no rule here, simulating
        // that "shouldn't happen but did" state to confirm it still throws, not degrades.
        BiomeId forest = id("forest");
        BiomeGraph graph = new BiomeGraph(
            List.of(node("desert", 200, 300, false), node("forest", 200, 300, false), node("plains", 100, 200, true)),
            List.of(AdjacencyRule.allow(PLAINS, DESERT, 10), AdjacencyRule.allow(PLAINS, forest, 10))
        );
        Map<BiomeId, Double> values = Map.of(DESERT, 0.0, forest, 10.0, PLAINS, 100.0);

        assertThrows(IllegalArgumentException.class, () -> BorderBlend.blendedValue(
            63, 32, GRID, graph, cell -> cell.x() == 1 ? forest : DESERT, values::get
        ));
    }
}
