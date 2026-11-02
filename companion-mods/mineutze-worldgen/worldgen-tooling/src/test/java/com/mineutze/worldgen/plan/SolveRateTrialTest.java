package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static com.mineutze.worldgen.plan.ToroidalTestTables.A;
import static com.mineutze.worldgen.plan.ToroidalTestTables.B;
import static com.mineutze.worldgen.plan.ToroidalTestTables.C;
import static com.mineutze.worldgen.plan.ToroidalTestTables.fullyLegalCatalog;
import static com.mineutze.worldgen.plan.ToroidalTestTables.noLegalTriangleCatalog;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SolveRateTrialTest {

    private static final PlanSettings SMALL_COARSE = new PlanSettings(8, 300, 1.3, new double[] {20, 0}, 208, 0.10, 3_000_000, 60, ToroidalSizedPatternGenerator.DEFAULT_SCHEDULE);
    private static final PlanSettings TINY_GRID = new PlanSettings(4, 300, 1.3, new double[] {20, 0}, 104, 0.10, 20_000, 200, ToroidalSizedPatternGenerator.DEFAULT_SCHEDULE);
    private static final Duration FEW_SECONDS = Duration.ofSeconds(4);

    private static List<ArmResult> run(PlanSettings settings, BiomeGraphData baseline, List<LoosenedGraph> arms) {
        return new SolveRateTrial(settings, 2, FEW_SECONDS, 0.95).run(baseline, arms, message -> { });
    }

    @Test
    void theFirstResultIsTheGraphAsIs() {
        List<ArmResult> results = run(SMALL_COARSE, fullyLegalCatalog(500, 700, A, B, C), List.of());

        assertEquals(1, results.size());
        assertEquals(SolveRateTrial.BASELINE_LABEL, results.get(0).label());
    }

    @Test
    void aLooseningThatMakesTheGraphSolvableImproves() {
        BiomeGraphData neverSolves = noLegalTriangleCatalog(500, 800);
        LoosenedGraph solvable = new LoosenedGraph("make every pair legal", fullyLegalCatalog(500, 800, A, B, C));

        List<ArmResult> results = run(SMALL_COARSE, neverSolves, List.of(solvable));

        assertTrue(results.get(1).improves(), results.toString());
        assertFalse(results.get(0).improves(), results.toString());
    }

    @Test
    void anIdenticalGraphDoesNotImprove() {
        BiomeGraphData graph = fullyLegalCatalog(500, 700, A, B, C);

        List<ArmResult> results = run(SMALL_COARSE, graph, List.of(new LoosenedGraph("same graph", graph)));

        assertFalse(results.get(1).improves(), results.toString());
    }

    @Test
    void aLooseningTheGeneratorRejectsIsReportedNotRun() {
        BiomeGraphData tooBigForTheGrid = fullyLegalCatalog(1500, 1600, A, B, C);

        List<ArmResult> results = run(TINY_GRID, fullyLegalCatalog(300, 400, A, B, C), List.of(new LoosenedGraph("too big", tooBigForTheGrid)));

        assertTrue(results.get(1).rejection().isPresent(), results.toString());
        assertTrue(results.get(1).evidence().isEmpty(), results.toString());
    }

    @Test
    void refusesWhenTheGraphAsIsIsRejected() {
        BiomeGraphData tooBigForTheGrid = fullyLegalCatalog(1500, 1600, A, B, C);

        assertThrows(IllegalArgumentException.class, () -> run(TINY_GRID, tooBigForTheGrid, List.of()));
    }

    @Test
    void improvingNeedsTheArmsLowBoundAboveTheBaselinesHighBound() {
        assertTrue(SolveRateTrial.improves(0.21, 0.20));
        assertFalse(SolveRateTrial.improves(0.20, 0.20));
    }

    @Test
    void everyRunnableArmGetsAttempts() {
        BiomeGraphData graph = fullyLegalCatalog(500, 700, A, B, C);

        List<ArmResult> results = run(SMALL_COARSE, graph, List.of(new LoosenedGraph("same graph", graph)));

        assertTrue(results.stream().allMatch(result -> result.evidence().orElseThrow().trials() > 0), results.toString());
    }

    @Test
    void stopsAtItsDeadline() {
        long start = System.nanoTime();

        run(SMALL_COARSE, noLegalTriangleCatalog(500, 800), List.of());

        assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(15)) < 0);
    }
}
