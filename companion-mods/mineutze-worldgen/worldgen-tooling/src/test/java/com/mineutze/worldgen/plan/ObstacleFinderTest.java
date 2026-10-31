package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeId;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static com.mineutze.worldgen.plan.ToroidalTestTables.A;
import static com.mineutze.worldgen.plan.ToroidalTestTables.B;
import static com.mineutze.worldgen.plan.ToroidalTestTables.C;
import static com.mineutze.worldgen.plan.ToroidalTestTables.everyCellHolds;
import static com.mineutze.worldgen.plan.ToroidalTestTables.fullyLegalTableRequiring;
import static com.mineutze.worldgen.plan.ToroidalTestTables.fullyLegalTableWithWidths;
import static com.mineutze.worldgen.plan.ToroidalTestTables.tableWithNoLegalTriangle;
import static com.mineutze.worldgen.plan.ToroidalTestTables.threeColoring;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObstacleFinderTest {

    private static final ToroidalGrid GRID = new ToroidalGrid(6, 6);

    private static ClusterSizeModel model(RawRuleTable table) {
        return new ClusterSizeModel(table, 300, new ShapeRule(ShapeRule.NO_PERIMETER_LIMIT, Integer.MAX_VALUE, Integer.MAX_VALUE, 2));
    }

    private static Set<Obstacle> obstacles(RawRuleTable table, Map<ToroidalPos, BiomeId> pattern) {
        return ObstacleFinder.of(table, GRID, model(table), pattern);
    }

    /** Left half A, right half B on the 6x6 torus. */
    private static Map<ToroidalPos, BiomeId> twoBands() {
        Map<ToroidalPos, BiomeId> bands = new LinkedHashMap<>();
        for (ToroidalPos pos : GRID.allPositionsInFillOrder()) {
            bands.put(pos, pos.q() < 3 ? A : B);
        }
        return bands;
    }

    @Test
    void illegalNeighboursTouchingAreTheirPairLaw() {
        Set<Obstacle> found = obstacles(tableWithNoLegalTriangle(300, 3000), threeColoring(GRID, A, B, C));

        assertTrue(found.contains(new LawObstacle(new IllegalPairLaw(A, C))), found.toString());
        assertTrue(found.contains(new LawObstacle(new IllegalPairLaw(B, C))), found.toString());
    }

    @Test
    void absentBiomesAreMissing() {
        Set<Obstacle> found = obstacles(fullyLegalTableWithWidths(300, 3000, A, B, C), everyCellHolds(GRID, A));

        assertTrue(found.contains(new MissingBiome(B)), found.toString());
        assertTrue(found.contains(new MissingBiome(C)), found.toString());
    }

    @Test
    void aPatchOutsideItsRangeIsItsSizeLaw() {
        RawRuleTable table = fullyLegalTableWithWidths(300, 600, A, B, C);

        Set<Obstacle> found = obstacles(table, twoBands());

        assertTrue(found.stream().anyMatch(obstacle -> obstacle instanceof LawObstacle law && law.law() instanceof SizeRangeLaw size && size.biome().equals(A)), found.toString());
    }

    @Test
    void aPatchShortOfItsRequiredBorderIsItsBorderLaw() {
        RawRuleTable table = fullyLegalTableRequiring(300, 3000, A, C, 2, A, B, C);

        Set<Obstacle> found = obstacles(table, twoBands());

        assertTrue(found.stream().anyMatch(obstacle -> obstacle instanceof LawObstacle law && law.law() instanceof RequiredBorderLaw border && border.biome().equals(A)), found.toString());
    }

    /** Review finding 2026-10-31: a patch too compact for its perimeter floor (floored beaches) must be reported. */
    @Test
    void aPatchUnderItsPerimeterFloorIsAShapeLimit() {
        RawRuleTable table = fullyLegalTableWithWidths(300, 3000, A, B, C);
        ShapeRule noLimits = new ShapeRule(ShapeRule.NO_PERIMETER_LIMIT, Integer.MAX_VALUE, Integer.MAX_VALUE, 2);
        ShapeRule mustBeLong = new ShapeRule(ShapeRule.NO_PERIMETER_LIMIT, 1.5, Integer.MAX_VALUE, Integer.MAX_VALUE, 2);
        ClusterSizeModel flooredA = new ClusterSizeModel(table, 300, noLimits, Map.of(A, mustBeLong));

        Set<Obstacle> found = ObstacleFinder.of(table, GRID, flooredA, twoBands());

        assertTrue(found.contains(new ShapeLimit(A)), found.toString());
    }

    @Test
    void aValidPatternHasNoObstacle() {
        RawRuleTable table = fullyLegalTableWithWidths(300, 3000, A, B, C);

        assertEquals(Set.of(), obstacles(table, threeColoring(GRID, A, B, C)));
    }

    @Test
    void obstaclesNameTheirBiomes() {
        Set<Obstacle> found = obstacles(fullyLegalTableWithWidths(300, 3000, A, B, C), everyCellHolds(GRID, A));

        assertTrue(found.stream().allMatch(obstacle -> obstacle.describe().contains("test:")), found.toString());
    }
}
