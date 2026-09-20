package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeId;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntBinaryOperator;

import static com.mineutze.worldgen.plan.ToroidalTestTables.A;
import static com.mineutze.worldgen.plan.ToroidalTestTables.B;
import static com.mineutze.worldgen.plan.ToroidalTestTables.C;
import static com.mineutze.worldgen.plan.ToroidalTestTables.fullyLegalTableRequiring;
import static com.mineutze.worldgen.plan.ToroidalTestTables.fullyLegalTableWithWidths;
import static com.mineutze.worldgen.plan.ToroidalTestTables.tableWithNoLegalTriangle;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BiomeMapVerifierTest {

    private static final int SIZE = 10;
    private static final double SAMPLE_AREA = 1_000;
    private static final double TOLERANCE = 0.10;
    private static final List<BiomeId> ORDER = List.of(A, B, C);

    /** Builds a map whose biome at sample (i, j) is {@code biomeOf(i, j)} (an index into A, B, C) and whose cell is {@code cellOf(i, j)}. */
    private static BiomeMap map(IntBinaryOperator biomeOf, IntBinaryOperator cellOf) {
        int[] biomes = new int[SIZE * SIZE];
        int[] cells = new int[SIZE * SIZE];
        for (int j = 0; j < SIZE; j++) {
            for (int i = 0; i < SIZE; i++) {
                biomes[j * SIZE + i] = biomeOf.applyAsInt(i, j);
                cells[j * SIZE + i] = cellOf.applyAsInt(i, j);
            }
        }
        return new BiomeMap(SIZE, biomes, cells, ORDER, SAMPLE_AREA, 10);
    }

    private static BiomeMap columns(int firstBiome, int secondBiome, int splitAtColumn) {
        return map((i, j) -> i < splitAtColumn ? firstBiome : secondBiome, (i, j) -> 0);
    }

    @Test
    void aWholeTileOfOneBiomeIsOneComponentCoveringTheTile() {
        BiomeMap wholeTile = map((i, j) -> 0, (i, j) -> 0);
        RawRuleTable table = fullyLegalTableWithWidths(300, 400, A, B, C);

        BiomeMapVerifier.Report report = BiomeMapVerifier.verify(wholeTile, table, TOLERANCE);

        assertEquals(1, report.componentCount());
        assertTrue(report.areaViolations().isEmpty(), report.areaViolations().toString());
    }

    @Test
    void twoBandsAreTwoComponents() {
        RawRuleTable table = fullyLegalTableWithWidths(250, 400, A, B, C);

        BiomeMapVerifier.Report report = BiomeMapVerifier.verify(columns(0, 1, 5), table, TOLERANCE);

        assertEquals(2, report.componentCount());
        assertTrue(report.areaViolations().isEmpty());
    }

    /** Each 5x10 band is 50,000 blocks^2; a 300-wide biome needs at least 58,457, and 10% of that is 5,846, so 52,611 is the least allowed: 50,000 is too small. */
    @Test
    void aComponentBelowTheMinimumByMoreThanTheToleranceIsAViolation() {
        RawRuleTable table = fullyLegalTableWithWidths(300, 400, A, B, C);

        BiomeMapVerifier.Report report = BiomeMapVerifier.verify(columns(0, 1, 5), table, TOLERANCE);

        assertEquals(2, report.areaViolations().size());
        assertEquals(50_000, report.areaViolations().get(0).areaBlocks2(), 1e-6);
        assertEquals(58_457 - 0.10 * 58_457, report.areaViolations().get(0).allowedMinBlocks2(), 1.0);
    }

    /** 53,000 is below the 58,457 minimum but within 10% of it (least allowed 52,611), so the tolerance forgives it. */
    @Test
    void aComponentBelowTheMinimumButWithinTheToleranceIsAccepted() {
        RawRuleTable table = fullyLegalTableWithWidths(300, 400, A, B, C);
        BiomeMap map = new BiomeMap(SIZE, columns(0, 1, 5).biomeIndices(), columns(0, 1, 5).cells(), ORDER, 1_060, 10);

        BiomeMapVerifier.Report report = BiomeMapVerifier.verify(map, table, TOLERANCE);

        assertTrue(report.areaViolations().isEmpty(), report.areaViolations().toString());
    }

    @Test
    void withNoToleranceTheSameComponentIsAViolation() {
        RawRuleTable table = fullyLegalTableWithWidths(300, 400, A, B, C);
        BiomeMap map = new BiomeMap(SIZE, columns(0, 1, 5).biomeIndices(), columns(0, 1, 5).cells(), ORDER, 1_060, 10);

        BiomeMapVerifier.Report report = BiomeMapVerifier.verify(map, table, 0.0);

        assertEquals(2, report.areaViolations().size());
    }

    /** The maximum for a 400-wide biome is 103,923 blocks^2; the tolerance is 10% of the MINIMUM (58,457 * 0.1 = 5,846), so up to 109,769 is accepted. */
    @Test
    void aComponentAboveTheMaximumIsAcceptedOnlyUpToTheToleranceOfTheMinimum() {
        RawRuleTable table = fullyLegalTableWithWidths(300, 400, A, B, C);
        BiomeMap within = new BiomeMap(SIZE, new int[SIZE * SIZE], new int[SIZE * SIZE], ORDER, 1_090, 10);
        BiomeMap beyond = new BiomeMap(SIZE, new int[SIZE * SIZE], new int[SIZE * SIZE], ORDER, 1_100, 10);

        assertTrue(BiomeMapVerifier.verify(within, table, TOLERANCE).areaViolations().isEmpty());
        assertEquals(1, BiomeMapVerifier.verify(beyond, table, TOLERANCE).areaViolations().size());
    }

    /** Contacts are counted over the six-neighbor lattice: each seam sample touches its right neighbor and its lower-left neighbor, so a seam of 10 rows has 20 contacts, and the tile has two seams. */
    @Test
    void contactsBetweenLegalNeighborsAreCountedNotFlagged() {
        RawRuleTable table = fullyLegalTableWithWidths(250, 400, A, B, C);

        BiomeMapVerifier.Report report = BiomeMapVerifier.verify(columns(0, 1, 5), table, TOLERANCE);

        assertTrue(report.illegalContactSamples().isEmpty());
        assertEquals(4 * SIZE, report.legalContactSamples());
    }

    @Test
    void contactsBetweenBiomesThatMayNotTouchAreReportedByPairWithTheirCount() {
        RawRuleTable table = tableWithNoLegalTriangle(250, 400);

        BiomeMapVerifier.Report report = BiomeMapVerifier.verify(columns(0, 2, 5), table, TOLERANCE);

        assertEquals(Map.of(A + " | " + C, 4 * SIZE), report.illegalContactSamples());
        assertEquals(0, report.legalContactSamples());
    }

    /** Grid 2x2 with A in the top row's two cells (indices 0, 1) and B in the bottom row's (2, 3); the map gives A the top 5 sample rows: exactly the expected half. */
    @Test
    void areaDriftIsZeroWhenEachRegionCoversExactlItsCellsShare() {
        ToroidalGrid grid = new ToroidalGrid(2, 2);
        PatternClusters clusters = PatternClusters.of(grid, twoRowPattern(grid));
        BiomeMap map = map((i, j) -> j < 5 ? 0 : 1, (i, j) -> j < 5 ? i % 2 : 2 + i % 2);
        RawRuleTable table = fullyLegalTableWithWidths(100, 800, A, B, C);

        BiomeMapVerifier.Report report = BiomeMapVerifier.verify(map, table, TOLERANCE, clusters);

        for (double drift : report.driftRatios()) {
            assertEquals(0.0, drift, 1e-9);
        }
        assertEquals(2, report.driftRatios().length);
    }

    @Test
    void areaDriftReportsHowMuchLargerOrSmallerARegionIsThanItsCells() {
        ToroidalGrid grid = new ToroidalGrid(2, 2);
        PatternClusters clusters = PatternClusters.of(grid, twoRowPattern(grid));
        BiomeMap map = map((i, j) -> j < 6 ? 0 : 1, (i, j) -> j < 6 ? i % 2 : 2 + i % 2);
        RawRuleTable table = fullyLegalTableWithWidths(100, 800, A, B, C);

        BiomeMapVerifier.Report report = BiomeMapVerifier.verify(map, table, TOLERANCE, clusters);

        double[] drifts = report.driftRatios().clone();
        java.util.Arrays.sort(drifts);
        assertEquals(-0.2, drifts[0], 1e-9);
        assertEquals(0.2, drifts[1], 1e-9);
    }

    @Test
    void aClusterWithNoRegionInTheMapIsReportedAsVanished() {
        ToroidalGrid grid = new ToroidalGrid(2, 2);
        PatternClusters clusters = PatternClusters.of(grid, twoRowPattern(grid));
        BiomeMap onlyTopBiome = map((i, j) -> 0, (i, j) -> i % 2);
        RawRuleTable table = fullyLegalTableWithWidths(100, 800, A, B, C);

        BiomeMapVerifier.Report report = BiomeMapVerifier.verify(onlyTopBiome, table, TOLERANCE, clusters);

        assertEquals(1, report.vanishedClusters());
    }

    /** Rows 0-1 and 5-6 of biome A are cut apart by B: one cluster of the pattern has become two regions. */
    @Test
    void aClusterCutIntoTwoRegionsIsReportedAsSplit() {
        ToroidalGrid grid = new ToroidalGrid(2, 2);
        PatternClusters clusters = PatternClusters.of(grid, twoRowPattern(grid));
        boolean[] isTopBiomeRow = {true, true, false, false, false, true, true, false, false, false};
        BiomeMap map = map((i, j) -> isTopBiomeRow[j] ? 0 : 1, (i, j) -> isTopBiomeRow[j] ? i % 2 : 2 + i % 2);
        RawRuleTable table = fullyLegalTableWithWidths(100, 800, A, B, C);

        BiomeMapVerifier.Report report = BiomeMapVerifier.verify(map, table, TOLERANCE, clusters);

        assertEquals(2, report.splitClusters());
    }

    /** Biome A is cut into two islands inside one connected sea of B: exactly ONE cluster is split, the other stays whole. */
    @Test
    void onlyTheClusterThatWasCutIsCountedAsSplit() {
        ToroidalGrid grid = new ToroidalGrid(2, 2);
        PatternClusters clusters = PatternClusters.of(grid, twoRowPattern(grid));
        IntBinaryOperator isIsland = (i, j) -> (i < 3 && j < 2) || (i >= 5 && i < 8 && j >= 5 && j < 7) ? 1 : 0;
        BiomeMap map = map((i, j) -> isIsland.applyAsInt(i, j) == 1 ? 0 : 1, (i, j) -> isIsland.applyAsInt(i, j) == 1 ? i % 2 : 2 + i % 2);
        RawRuleTable table = fullyLegalTableWithWidths(100, 800, A, B, C);

        BiomeMapVerifier.Report report = BiomeMapVerifier.verify(map, table, TOLERANCE, clusters);

        assertEquals(3, report.componentCount());
        assertEquals(1, report.splitClusters());
    }

    /** Two separate A clusters (cells 0 and 10 of a 4x4 grid) end up in one connected region of the map: they have merged. */
    @Test
    void twoClustersJoinedIntoOneRegionAreReportedAsMerged() {
        ToroidalGrid grid = new ToroidalGrid(4, 4);
        Map<ToroidalPos, BiomeId> pattern = new LinkedHashMap<>();
        for (ToroidalPos pos : grid.allPositionsInFillOrder()) {
            boolean isA = (pos.q() == 0 && pos.r() == 0) || (pos.q() == 2 && pos.r() == 2);
            pattern.put(pos, isA ? A : B);
        }
        PatternClusters clusters = PatternClusters.of(grid, pattern);
        BiomeMap map = map((i, j) -> 0, (i, j) -> i < 5 ? 0 : 10);
        RawRuleTable table = fullyLegalTableWithWidths(100, 800, A, B, C);

        BiomeMapVerifier.Report report = BiomeMapVerifier.verify(map, table, TOLERANCE, clusters);

        assertEquals(1, report.mergedComponents());
    }

    private static Map<ToroidalPos, BiomeId> twoRowPattern(ToroidalGrid grid) {
        Map<ToroidalPos, BiomeId> pattern = new LinkedHashMap<>();
        for (ToroidalPos pos : grid.allPositionsInFillOrder()) {
            pattern.put(pos, pos.r() == 0 ? A : B);
        }
        return pattern;
    }

    /** Two column bands in a 10-wide map share two seams of 10 rows: 40 contacts, and a border is measured as contacts x blocks per sample / 2 (about two contacts per sample length of a straight seam), 200 blocks here. */
    @Test
    void aRegionSharingLessBorderThanItsRequirementNeedsIsReportedWithBothLengths() {
        RawRuleTable table = fullyLegalTableRequiring(250, 400, A, B, 3, A, B, C);

        BiomeMapVerifier.Report report = BiomeMapVerifier.verify(columns(0, 1, 5), table, TOLERANCE, null, 100);

        assertEquals(1, report.borderShortfalls().size());
        BiomeMapVerifier.BorderShortfall shortfall = report.borderShortfalls().get(0);
        assertEquals(A, shortfall.biome());
        assertEquals(B, shortfall.required());
        assertEquals(200, shortfall.lengthBlocks(), 1e-9, "40 contacts x 10 blocks per sample / 2");
        assertEquals(300, shortfall.neededBlocks(), 1e-9, "3 edges x 100 blocks");
    }

    @Test
    void aRegionSharingAsMuchBorderAsItsRequirementNeedsIsNotReported() {
        RawRuleTable table = fullyLegalTableRequiring(250, 400, A, B, 2, A, B, C);

        BiomeMapVerifier.Report report = BiomeMapVerifier.verify(columns(0, 1, 5), table, TOLERANCE, null, 100);

        assertTrue(report.borderShortfalls().isEmpty(), report.borderShortfalls().toString());
    }

    @Test
    void aRegionTouchingNoneOfTheRequiredBiomeIsReportedWithZeroLength() {
        RawRuleTable table = fullyLegalTableRequiring(250, 400, A, C, 1, A, B, C);

        BiomeMapVerifier.Report report = BiomeMapVerifier.verify(columns(0, 1, 5), table, TOLERANCE, null, 100);

        assertEquals(1, report.borderShortfalls().size());
        assertEquals(0.0, report.borderShortfalls().get(0).lengthBlocks(), 1e-9);
    }

    @Test
    void theRequirementIsOneWayTheRequiredBiomeNeedsNothingBack() {
        RawRuleTable table = fullyLegalTableRequiring(250, 400, A, B, 2, A, B, C);

        BiomeMapVerifier.Report report = BiomeMapVerifier.verify(columns(1, 0, 5), table, TOLERANCE, null, 100);

        assertTrue(report.borderShortfalls().isEmpty(), "B beside A is enough for A: " + report.borderShortfalls());
    }

    @Test
    void withoutAnyRequirementNoShortfallIsEverReported() {
        RawRuleTable table = fullyLegalTableWithWidths(250, 400, A, B, C);

        assertTrue(BiomeMapVerifier.verify(columns(0, 1, 5), table, TOLERANCE, null, 100).borderShortfalls().isEmpty());
    }
}
