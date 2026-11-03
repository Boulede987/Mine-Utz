package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static com.mineutze.worldgen.plan.ToroidalTestTables.A;
import static com.mineutze.worldgen.plan.ToroidalTestTables.B;
import static com.mineutze.worldgen.plan.ToroidalTestTables.C;
import static com.mineutze.worldgen.plan.ToroidalTestTables.fullyLegalCatalog;
import static com.mineutze.worldgen.plan.ToroidalTestTables.noLegalTriangleCatalog;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeasibilityCheckTest {

    private static final BiomeGraphData SOLVABLE = fullyLegalCatalog(500, 700, A, B, C);
    private static final BiomeGraphData NO_LEGAL_TRIANGLE = noLegalTriangleCatalog(500, 800);

    /** Same coarse 8x8@300 + fine 16x16@150 settings WorldBiomePlanTest proves solvable. */
    private static final PlanSettings SMALL_MULTIGRID = new PlanSettings(
        8, 300, 1.3, new double[] {20, 0}, 208, 0.10, 3_000_000, 60, ToroidalSizedPatternGenerator.DEFAULT_SCHEDULE,
        new PlanSettings.FineResolution(16, 150, 3_000_000L, 5, ToroidalSizedPatternGenerator.DEFAULT_SCHEDULE, Map.of())
    );
    /** One search step per attempt: no attempt can solve, while the graph itself has no contradiction - only the deadline ends it. */
    private static final PlanSettings NO_ATTEMPT_CAN_SOLVE = new PlanSettings(8, 300, 1.3, new double[] {20, 0}, 208, 0.10, 1, 60, ToroidalSizedPatternGenerator.DEFAULT_SCHEDULE);
    private static final PlanSettings TINY_GRID = new PlanSettings(4, 300, 1.3, new double[] {20, 0}, 104, 0.10, 20_000, 200, ToroidalSizedPatternGenerator.DEFAULT_SCHEDULE);

    private static final FeasibilityBudget ONE_MINUTE = new FeasibilityBudget(Duration.ofMinutes(1), 4, 0.01, 0.99);
    private static final FeasibilityBudget FEW_SECONDS = new FeasibilityBudget(Duration.ofSeconds(3), 2, 0.01, 0.99);

    private static FeasibilityVerdict check(BiomeGraphData catalog, PlanSettings settings, FeasibilityBudget budget) {
        return FeasibilityCheck.check(catalog, settings, budget, message -> { });
    }

    @Test
    void aSolvableGraphIsFeasible() {
        FeasibilityVerdict verdict = check(SOLVABLE, SMALL_MULTIGRID, ONE_MINUTE);

        assertEquals(FeasibilityVerdict.Outcome.FEASIBLE, verdict.outcome(), verdict.summary());
    }

    @Test
    void aFeasibleVerdictCarriesTheWorldItBuilt() {
        FeasibilityVerdict verdict = check(SOLVABLE, SMALL_MULTIGRID, ONE_MINUTE);

        assertTrue(verdict.builtWorld().isPresent(), verdict.summary());
    }

    @Test
    void theBuiltWorldCanBeSavedAsAPicture(@TempDir Path folder) throws Exception {
        BuiltWorld world = check(SOLVABLE, SMALL_MULTIGRID, ONE_MINUTE).builtWorld().orElseThrow();
        Path picture = folder.resolve("nested").resolve("world.png");

        world.writePicture(picture);

        assertTrue(Files.size(picture) > 0, picture.toString());
    }

    @Test
    void aGraphWithNoLegalCornerIsInfeasibleWithAProof() {
        FeasibilityVerdict verdict = check(NO_LEGAL_TRIANGLE, SMALL_MULTIGRID, ONE_MINUTE);

        assertEquals(FeasibilityVerdict.Outcome.INFEASIBLE, verdict.outcome(), verdict.summary());
        assertFalse(verdict.proof().orElseThrow().conflicts().isEmpty(), verdict.summary());
    }

    @Test
    void aProvenGraphIsDecidedWithoutSearching() {
        FeasibilityVerdict verdict = check(NO_LEGAL_TRIANGLE, SMALL_MULTIGRID, ONE_MINUTE);

        assertEquals(0, verdict.evidence().coarse().trials(), verdict.summary());
    }

    @Test
    void anInfeasibleSummaryNamesTheConflictingLaws() {
        FeasibilityVerdict verdict = check(NO_LEGAL_TRIANGLE, SMALL_MULTIGRID, ONE_MINUTE);

        assertTrue(verdict.summary().contains(C.toString()), verdict.summary());
    }

    @Test
    void aGraphTheGeneratorRejectsIsInfeasibleWithItsMessage() {
        FeasibilityVerdict verdict = check(fullyLegalCatalog(1500, 1600, A, B, C), TINY_GRID, ONE_MINUTE);

        assertEquals(FeasibilityVerdict.Outcome.INFEASIBLE, verdict.outcome(), verdict.summary());
        assertTrue(verdict.proof().orElseThrow().generatorRejection().isPresent(), verdict.summary());
    }

    @Test
    void noWorldBuiltAndNoContradictionIsUnproven() {
        FeasibilityVerdict verdict = check(SOLVABLE, NO_ATTEMPT_CAN_SOLVE, FEW_SECONDS);

        assertEquals(FeasibilityVerdict.Outcome.UNPROVEN, verdict.outcome(), verdict.summary());
    }

    /** User finding 2026-10-31: a probability headline with zero worlds built misled. Facts only. */
    @Test
    void anUnprovenHeadlineStatesFactsNotProbabilities() {
        FeasibilityVerdict verdict = check(SOLVABLE, NO_ATTEMPT_CAN_SOLVE, FEW_SECONDS);

        String headline = verdict.summary().lines().findFirst().orElseThrow();
        assertTrue(headline.startsWith("UNPROVEN"), headline);
        assertFalse(headline.contains("%"), headline);
    }

    @Test
    void anUnprovenVerdictRanksWhatBlockedTheFailedAttempts() {
        FeasibilityVerdict verdict = check(SOLVABLE, NO_ATTEMPT_CAN_SOLVE, FEW_SECONDS);

        TightnessRanking ranking = verdict.tightness().orElseThrow().coarse();
        assertTrue(ranking.failedAttempts() > 0, verdict.summary());
        assertFalse(ranking.entries().isEmpty(), verdict.summary());
    }

    @Test
    void anUnprovenSummaryLabelsTheRankingAsAMeasurement() {
        FeasibilityVerdict verdict = check(SOLVABLE, NO_ATTEMPT_CAN_SOLVE, FEW_SECONDS);

        assertTrue(verdict.summary().contains("still broke"), verdict.summary());
        assertTrue(verdict.summary().contains("measured"), verdict.summary());
    }

    private static TightnessRanking noFailures() {
        return new TightnessRanking(List.of(), 0);
    }

    /** A FEASIBLE graph can still fail attempts (production reliability): those failures are ranked too. */
    @Test
    void aCoarseFailureKeepsTheRanking() {
        assertTrue(FeasibilityCheck.anyAttemptFailed(new StageTightness(new TightnessRanking(List.of(), 1), noFailures())));
    }

    @Test
    void aRefinementFailureKeepsTheRanking() {
        assertTrue(FeasibilityCheck.anyAttemptFailed(new StageTightness(noFailures(), new TightnessRanking(List.of(), 1))));
    }

    @Test
    void noFailureKeepsNoRanking() {
        assertFalse(FeasibilityCheck.anyAttemptFailed(new StageTightness(noFailures(), noFailures())));
    }

    @Test
    void aFeasibleSummaryListsTheLawsItsFailedAttemptsBroke() {
        BuiltWorld world = new BuiltWorld(Map.of(), new BiomeMap(1, new int[] {0}, new int[] {0}, List.of(A), 1.0, 1.0), 1);
        TightnessRanking coarse = new TightnessRanking(List.of(new RankedObstacle(new LawObstacle(new IllegalPairLaw(A, B)), 3)), 4);
        StageTightness tightness = new StageTightness(coarse, new TightnessRanking(List.of(), 0));
        Evidence evidence = new Evidence(new StageEvidence(5, 1, OptionalInt.empty()), StageEvidence.EMPTY, new StageEvidence(1, 1, OptionalInt.empty()));

        FeasibilityVerdict verdict = FeasibilityVerdict.feasible(world, evidence, new ReliabilityEstimate(0.0, 0.2, AttemptResult.Stage.COARSE), Optional.of(tightness), 0.01, Duration.ofSeconds(1));

        assertTrue(verdict.summary().contains(new IllegalPairLaw(A, B).describe() + ": still broke in 3 of 4"), verdict.summary());
    }

    @Test
    void anInfeasibleVerdictNeverCarriesARanking() {
        StageTightness tightness = new StageTightness(new TightnessRanking(List.of(), 1), new TightnessRanking(List.of(), 0));

        assertThrows(IllegalArgumentException.class, () -> new FeasibilityVerdict(FeasibilityVerdict.Outcome.INFEASIBLE, Optional.of(InfeasibilityProof.rejectedByGenerator("no")),
            Optional.empty(), Evidence.EMPTY, Optional.empty(), Optional.of(tightness), 0.01, Duration.ZERO));
    }

    @Test
    void anUnprovenCheckStopsAtTheDeadline() {
        FeasibilityVerdict verdict = check(SOLVABLE, NO_ATTEMPT_CAN_SOLVE, FEW_SECONDS);

        assertTrue(verdict.elapsed().compareTo(Duration.ofSeconds(15)) < 0, "took " + verdict.elapsed());
    }
}
