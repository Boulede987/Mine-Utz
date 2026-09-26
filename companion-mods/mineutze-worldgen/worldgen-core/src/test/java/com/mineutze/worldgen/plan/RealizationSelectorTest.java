package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.mineutze.worldgen.plan.ToroidalTestTables.A;
import static com.mineutze.worldgen.plan.ToroidalTestTables.B;
import static com.mineutze.worldgen.plan.ToroidalTestTables.C;
import static com.mineutze.worldgen.plan.ToroidalTestTables.fullyLegalTableWithWidths;
import static com.mineutze.worldgen.plan.ToroidalTestTables.tableWithNoLegalTriangle;
import static com.mineutze.worldgen.plan.ToroidalTestTables.threeColoring;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealizationSelectorTest {

    private static final ToroidalGrid GRID = new ToroidalGrid(6, 6);
    private static final HexTorusGeometry GEOMETRY = new HexTorusGeometry(6, 6, 300);
    private static final int SAMPLES = 120;
    private static final double TOLERANCE = 0.10;
    private static final double[] NO_WARP_FIRST = {0, 30};

    private static RawRuleTable table() {
        return fullyLegalTableWithWidths(300, 700, A, B, C);
    }

    private static Map<ToroidalPos, BiomeId> pattern() {
        return threeColoring(GRID, A, B, C);
    }

    private static RealizationSelector.Selection select(RawRuleTable table, double[] strengths, long worldSeed) {
        return RealizationSelector.select(table, GRID, pattern(), GEOMETRY, worldSeed, settingsWithStrengths(strengths));
    }

    /** {@link PlanSettings} with this test's fixed grid/geometry/samples/tolerance and the strengths under test; the pattern-search fields are irrelevant here (only {@link RealizationSelector} reads this settings object in these tests) so they keep {@link PlanSettings#DEFAULT}'s. */
    private static PlanSettings settingsWithStrengths(double[] strengths) {
        return new PlanSettings(
            GRID.width(), 300, 1.3, strengths, SAMPLES, TOLERANCE, PlanSettings.DEFAULT.patternStepsPerAttempt(), PlanSettings.DEFAULT.maxPatternAttempts(), PlanSettings.DEFAULT.patternSchedule()
        );
    }

    @Test
    void theFirstCandidateThatVerifiesIsChosen() {
        RealizationSelector.Selection selection = select(table(), NO_WARP_FIRST, 1L);

        assertEquals(0.0, selection.strength(), 0.0);
        assertEquals(1, selection.candidatesTried());
    }

    /** A warp of 400 blocks per wave shreds a tile only 1,559 blocks across, so it fails verification; the next candidate, no warp, passes. */
    @Test
    void aCandidateThatFailsVerificationIsSkippedForTheNextOne() {
        RealizationSelector.Selection selection = select(table(), new double[] {400, 0}, 2L);

        assertEquals(0.0, selection.strength(), 0.0);
        assertEquals(2, selection.candidatesTried());
    }

    @Test
    void theChosenRealizationCarriesItsCleanReport() {
        RealizationSelector.Selection selection = select(table(), NO_WARP_FIRST, 3L);

        assertTrue(selection.report().areaViolations().isEmpty());
        assertTrue(selection.report().illegalContactSamples().isEmpty());
        assertEquals(0, selection.report().splitClusters());
        assertEquals(0, selection.report().mergedComponents());
        assertEquals(0, selection.report().vanishedClusters());
    }

    @Test
    void sameWorldSeedAndCandidatesGiveTheSameWarp() {
        double[] first = new double[2];
        double[] second = new double[2];

        select(table(), new double[] {20}, 4L).warp().displace(500, 500, first);
        select(table(), new double[] {20}, 4L).warp().displace(500, 500, second);

        assertEquals(first[0], second[0], 0.0);
        assertEquals(first[1], second[1], 0.0);
    }

    /** Every biome here must cover 25 cells but the pattern gives each cell its own cluster, so no strength can satisfy the size ranges: the selector must fail loudly, naming what it tried. */
    @Test
    void whenNoCandidateVerifiesItFailsLoudlyNamingTheWorldSeedAndEachAttempt() {
        RawRuleTable impossible = fullyLegalTableWithWidths(1_500, 1_500, A, B, C);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> select(impossible, new double[] {20, 10}, 424242L));

        assertTrue(failure.getMessage().contains("424242"), failure.getMessage());
        assertTrue(failure.getMessage().contains("strength 20"), failure.getMessage());
        assertTrue(failure.getMessage().contains("strength 10"), failure.getMessage());
    }

    private static BiomeMapVerifier.Report report(int vanished, int split, int merged, int areaViolations, int illegalContactPairs) {
        return report(vanished, split, merged, areaViolations, illegalContactPairs, 0);
    }

    private static BiomeMapVerifier.Report report(int vanished, int split, int merged, int areaViolations, int illegalContactPairs, int shortBorders) {
        List<BiomeMapVerifier.AreaViolation> violations = java.util.Collections.nCopies(areaViolations, new BiomeMapVerifier.AreaViolation(A, 1, 2, 3));
        Map<String, Integer> contacts = new java.util.TreeMap<>();
        for (int pair = 0; pair < illegalContactPairs; pair++) {
            contacts.put("pair" + pair, 1);
        }
        List<BiomeMapVerifier.BorderShortfall> shortfalls = java.util.Collections.nCopies(shortBorders, new BiomeMapVerifier.BorderShortfall(A, "test:b >= 1"));
        return new BiomeMapVerifier.Report(5, violations, contacts, shortfalls, 100, new double[0], vanished, split, merged);
    }

    @Test
    void aCleanReportPasses() {
        assertTrue(RealizationSelector.passes(report(0, 0, 0, 0, 0)));
    }

    @Test
    void anyOneProblemFailsTheReport() {
        assertEquals(false, RealizationSelector.passes(report(1, 0, 0, 0, 0)), "a vanished cluster");
        assertEquals(false, RealizationSelector.passes(report(0, 1, 0, 0, 0)), "a split cluster");
        assertEquals(false, RealizationSelector.passes(report(0, 0, 1, 0, 0)), "a merged region");
        assertEquals(false, RealizationSelector.passes(report(0, 0, 0, 1, 0)), "a region outside its size range");
        assertEquals(false, RealizationSelector.passes(report(0, 0, 0, 0, 1)), "an illegal contact");
        assertEquals(false, RealizationSelector.passes(report(0, 0, 0, 0, 0, 1)), "a region short of its required border");
    }

    /** Only A-B is legal, so the plain hexagon pattern already has A touching C and B touching C: no strength can pass. */
    @Test
    void aPatternWhoseBiomesTouchIllegallyIsRejectedEvenWithNoWarp() {
        RawRuleTable illegal = tableWithNoLegalTriangle(300, 700);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> select(illegal, new double[] {0}, 7L));

        assertTrue(failure.getMessage().contains("illegal contact pairs 2"), failure.getMessage());
    }

    @Test
    void rejectsAnEmptyCandidateList() {
        assertThrows(IllegalArgumentException.class, () -> select(table(), new double[0], 5L));
    }

    @Test
    void differentWorldSeedsGetDifferentWarps() {
        double[] first = new double[2];
        double[] second = new double[2];

        select(table(), new double[] {20}, 10L).warp().displace(500, 500, first);
        select(table(), new double[] {20}, 11L).warp().displace(500, 500, second);

        assertTrue(first[0] != second[0] || first[1] != second[1]);
    }

    /** In the plain 3-coloring every A cell shares 3 edges with B; asking for 8 (half of that is still more than 3) can never be met, whatever the warp. */
    @Test
    void aPatternWhoseClusterHasTooLittleOfItsRequiredBorderIsRejected() {
        RawRuleTable demanding = ToroidalTestTables.fullyLegalTableRequiring(300, 700, A, B, 8, A, B, C);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> select(demanding, new double[] {0}, 8L));

        assertTrue(failure.getMessage().contains("short required borders 12"), failure.getMessage());
    }

    @Test
    void aPatternWhoseClusterHasEnoughOfItsRequiredBorderIsChosen() {
        RawRuleTable satisfied = ToroidalTestTables.fullyLegalTableRequiring(300, 700, A, B, 3, A, B, C);

        RealizationSelector.Selection selection = select(satisfied, new double[] {0}, 9L);

        assertTrue(selection.report().borderShortfalls().isEmpty(), selection.report().borderShortfalls().toString());
    }
}
