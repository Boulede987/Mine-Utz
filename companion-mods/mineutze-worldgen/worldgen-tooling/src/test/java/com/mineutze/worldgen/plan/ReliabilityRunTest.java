package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.mineutze.worldgen.plan.ToroidalTestTables.A;
import static com.mineutze.worldgen.plan.ToroidalTestTables.B;
import static com.mineutze.worldgen.plan.ToroidalTestTables.C;
import static com.mineutze.worldgen.plan.ToroidalTestTables.fullyLegalCatalog;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReliabilityRunTest {

    private static final BiomeGraphData SOLVABLE = fullyLegalCatalog(500, 700, A, B, C);
    /** Same coarse 8x8@300 + fine 16x16@150 settings FeasibilityCheckTest proves solvable. */
    private static final PlanSettings SMALL_MULTIGRID = new PlanSettings(
        8, 300, 1.3, new double[] {20, 0}, 208, 0.10, 3_000_000, 60, ToroidalSizedPatternGenerator.DEFAULT_SCHEDULE,
        new PlanSettings.FineResolution(16, 150, 3_000_000L, 5, ToroidalSizedPatternGenerator.DEFAULT_SCHEDULE, Map.of())
    );
    /** One search step per attempt: every attempt fails. */
    private static final PlanSettings NO_ATTEMPT_CAN_SOLVE = new PlanSettings(8, 300, 1.3, new double[] {20, 0}, 208, 0.10, 1, 60, ToroidalSizedPatternGenerator.DEFAULT_SCHEDULE);
    private static final Duration FEW_SECONDS = Duration.ofSeconds(4);
    /** FeasibilityCheckTest builds this graph's first world in well under this: the rest proves the run went on. */
    private static final Duration PAST_THE_FIRST_WORLD = Duration.ofSeconds(15);
    private static final int THREADS = 2;

    private static ReliabilityReport run(PlanSettings settings, List<String> lines) {
        return run(settings, FEW_SECONDS, lines);
    }

    private static ReliabilityReport run(PlanSettings settings, Duration wallTime, List<String> lines) {
        return ReliabilityRun.run(SOLVABLE, settings, FeasibilityBudget.EDITOR_DEFAULT, wallTime, THREADS, lines::add);
    }

    /** The Solve check stops at the first world; this run must use its whole time for a tighter range. */
    @Test
    void keepsAttemptingAfterTheFirstWorld() {
        ReliabilityReport report = run(SMALL_MULTIGRID, PAST_THE_FIRST_WORLD, new ArrayList<>());

        assertTrue(report.evidence().realization().successes() >= 1, report.summary());
        assertTrue(report.elapsed().compareTo(PAST_THE_FIRST_WORLD) >= 0, report.summary());
    }

    @Test
    void statesTheVerdictAgainstTheTarget() {
        ReliabilityReport report = run(SMALL_MULTIGRID, new ArrayList<>());

        assertTrue(report.summary().contains("target 1.000%: " + report.verdict().label()), report.summary());
    }

    @Test
    void ranksTheFailuresItSaw() {
        ReliabilityReport report = run(NO_ATTEMPT_CAN_SOLVE, new ArrayList<>());

        assertEquals(report.evidence().coarse().failures(), report.tightness().coarse().failedAttempts(), report.summary());
        assertTrue(report.tightness().coarse().failedAttempts() > 0, report.summary());
    }

    /** Stop kills the process: the last "so far" line is what the user keeps. */
    @Test
    void reportsTheRangeSoFarWhileRunning() {
        List<String> lines = new ArrayList<>();

        run(NO_ATTEMPT_CAN_SOLVE, lines);

        assertTrue(lines.stream().anyMatch(line -> line.startsWith("so far:") && line.contains("target")), lines.toString());
    }
}
