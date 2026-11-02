package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static com.mineutze.worldgen.plan.ToroidalTestTables.A;
import static com.mineutze.worldgen.plan.ToroidalTestTables.B;
import static com.mineutze.worldgen.plan.ToroidalTestTables.C;
import static com.mineutze.worldgen.plan.ToroidalTestTables.noLegalTriangleCatalog;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TightnessVerifyTest {

    private static final BiomeGraphData NEVER_SOLVES = noLegalTriangleCatalog(500, 800);
    private static final PlanSettings SMALL_COARSE = new PlanSettings(8, 300, 1.3, new double[] {20, 0}, 208, 0.10, 3_000_000, 60, ToroidalSizedPatternGenerator.DEFAULT_SCHEDULE);

    private static void writeRanking(Path file) {
        TightnessFile.write(file, new TightnessRanking(List.of(
            new RankedObstacle(new ShapeLimit(A), 9),
            new RankedObstacle(new LawObstacle(new IllegalPairLaw(A, C)), 8),
            new RankedObstacle(new LawObstacle(new IllegalPairLaw(B, C)), 7)
        ), 10), NEVER_SOLVES);
    }

    private static String verify(Path file) {
        return TightnessVerify.verify(file, NEVER_SOLVES, SMALL_COARSE, Duration.ofSeconds(4), 2, message -> { });
    }

    @Test
    void reportsTheGraphAsIsAndEachLoosening(@TempDir Path folder) {
        Path file = folder.resolve("tightness.json");
        writeRanking(file);

        String report = verify(file);

        assertTrue(report.contains(SolveRateTrial.BASELINE_LABEL), report);
        assertTrue(report.contains("legal neighbours"), report);
    }

    @Test
    void namesObstaclesItCannotLoosenInTheGraph(@TempDir Path folder) {
        Path file = folder.resolve("tightness.json");
        writeRanking(file);

        String report = verify(file);

        assertTrue(report.contains("shape limit"), report);
    }

    @Test
    void labelsTheResultAsAMeasurement(@TempDir Path folder) {
        Path file = folder.resolve("tightness.json");
        writeRanking(file);

        assertTrue(verify(file).contains("measured"));
    }

    @Test
    void nothingLoosenableReturnsWithoutRunning(@TempDir Path folder) {
        Path file = folder.resolve("tightness.json");
        TightnessFile.write(file, new TightnessRanking(List.of(new RankedObstacle(new ShapeLimit(A), 9)), 10), NEVER_SOLVES);
        long start = System.nanoTime();

        String report = verify(file);

        assertTrue(report.contains("nothing to measure"), report);
        assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(2)) < 0);
    }

    @Test
    void savesARankingOnlyForAnUnprovenVerdict(@TempDir Path folder) {
        Path file = folder.resolve("tightness.json");
        FeasibilityVerdict infeasible = FeasibilityVerdict.infeasible(InfeasibilityProof.rejectedByGenerator("no"), Evidence.EMPTY, 0.01, Duration.ZERO);

        TightnessVerify.saveRanking(file, infeasible, NEVER_SOLVES);

        assertFalse(Files.exists(file));
    }
}
