package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static com.mineutze.worldgen.plan.ToroidalTestTables.A;
import static com.mineutze.worldgen.plan.ToroidalTestTables.B;
import static com.mineutze.worldgen.plan.ToroidalTestTables.C;
import static com.mineutze.worldgen.plan.ToroidalTestTables.fullyLegalCatalog;
import static com.mineutze.worldgen.plan.ToroidalTestTables.noLegalTriangleCatalog;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeasibilityEstimatorTest {

    private static final BiomeGraphData SOLVABLE = fullyLegalCatalog(500, 700, A, B, C);
    private static final BiomeGraphData NO_LEGAL_TRIANGLE = noLegalTriangleCatalog(500, 800);

    /** Same coarse 8x8@300 + fine 16x16@150 settings WorldBiomePlanTest proves solvable. */
    private static final PlanSettings SMALL_MULTIGRID = new PlanSettings(
        8, 300, 1.3, new double[] {20, 0}, 208, 0.10, 3_000_000, 60, ToroidalSizedPatternGenerator.DEFAULT_SCHEDULE,
        new PlanSettings.FineResolution(16, 150, 3_000_000L, 5, ToroidalSizedPatternGenerator.DEFAULT_SCHEDULE, Map.of())
    );
    /** Short coarse attempts with production's own 200-try budget, so an impossible graph is rejected after a few hundred cheap attempts. */
    private static final PlanSettings SHORT_ATTEMPTS = new PlanSettings(8, 300, 1.3, new double[] {20, 0}, 208, 0.10, 20_000, 200, ToroidalSizedPatternGenerator.DEFAULT_SCHEDULE);
    /** Attempts far too long to finish, so only the deadline can end the estimate. */
    private static final PlanSettings ENDLESS_ATTEMPTS = new PlanSettings(8, 300, 1.3, new double[] {20, 0}, 208, 0.10, 1_000_000_000_000L, 200, ToroidalSizedPatternGenerator.DEFAULT_SCHEDULE);
    private static final PlanSettings TINY_GRID = new PlanSettings(4, 300, 1.3, new double[] {20, 0}, 104, 0.10, 20_000, 200, ToroidalSizedPatternGenerator.DEFAULT_SCHEDULE);

    private static final FeasibilityBudget ONE_MINUTE = new FeasibilityBudget(Duration.ofMinutes(1), 4, 0.01, 0.99);

    private static FeasibilityVerdict estimate(BiomeGraphData catalog, PlanSettings settings, FeasibilityBudget budget) {
        return FeasibilityEstimator.estimate(catalog, settings, budget, message -> { });
    }

    @Test
    void aSolvableGraphPasses() {
        FeasibilityVerdict verdict = estimate(SOLVABLE, SMALL_MULTIGRID, ONE_MINUTE);

        assertEquals(FeasibilityVerdict.Outcome.PASS, verdict.outcome(), verdict.summary());
    }

    @Test
    void aPassSaysTheRenderIsNotCertified() {
        FeasibilityVerdict verdict = estimate(SOLVABLE, SMALL_MULTIGRID, ONE_MINUTE);

        assertTrue(verdict.summary().contains("not certified"), verdict.summary());
    }

    @Test
    void aPassIsBackedByCertifiedBoundsUnderTheTarget() {
        FeasibilityVerdict verdict = estimate(SOLVABLE, SMALL_MULTIGRID, ONE_MINUTE);

        assertTrue(verdict.certifiedWorldFailureUpperBound() <= ONE_MINUTE.maxWorldFailureProbability(), verdict.summary());
        assertTrue(verdict.evidence().realization().successes() >= 1, verdict.summary());
    }

    @Test
    void aGraphWithNoLegalTilingFails() {
        FeasibilityVerdict verdict = estimate(NO_LEGAL_TRIANGLE, SHORT_ATTEMPTS, ONE_MINUTE);

        assertEquals(FeasibilityVerdict.Outcome.FAIL, verdict.outcome(), verdict.summary());
    }

    @Test
    void aFailNamesTheBiomesTheClosestAttemptIsStuckOn() {
        FeasibilityVerdict verdict = estimate(NO_LEGAL_TRIANGLE, SHORT_ATTEMPTS, ONE_MINUTE);

        assertTrue(verdict.closestFailureReport().orElseThrow().contains(C.toString()), verdict.summary());
    }

    @Test
    void aGraphTheGeneratorRejectsFailsWithoutSolving() {
        BiomeGraphData tooBigForTheGrid = fullyLegalCatalog(1500, 1600, A, B, C);

        FeasibilityVerdict verdict = estimate(tooBigForTheGrid, TINY_GRID, ONE_MINUTE);

        assertEquals(FeasibilityVerdict.Outcome.FAIL, verdict.outcome(), verdict.summary());
        assertEquals(0, verdict.evidence().coarse().trials());
        assertTrue(verdict.rejection().isPresent(), verdict.summary());
    }

    @Test
    void runningOutOfTimeUndecidedIsBorderline() {
        FeasibilityBudget shortBudget = new FeasibilityBudget(Duration.ofMillis(300), 2, 0.01, 0.99);

        FeasibilityVerdict verdict = estimate(NO_LEGAL_TRIANGLE, ENDLESS_ATTEMPTS, shortBudget);

        assertEquals(FeasibilityVerdict.Outcome.BORDERLINE, verdict.outcome(), verdict.summary());
    }

    @Test
    void theDeadlineStopsAttemptsStillRunning() {
        FeasibilityBudget shortBudget = new FeasibilityBudget(Duration.ofMillis(300), 2, 0.01, 0.99);

        FeasibilityVerdict verdict = estimate(NO_LEGAL_TRIANGLE, ENDLESS_ATTEMPTS, shortBudget);

        assertTrue(verdict.elapsed().compareTo(Duration.ofSeconds(5)) < 0, "took " + verdict.elapsed());
    }
}
