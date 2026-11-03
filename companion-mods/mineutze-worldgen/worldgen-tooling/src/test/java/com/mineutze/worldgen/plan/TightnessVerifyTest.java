package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.mineutze.worldgen.plan.ToroidalTestTables.A;
import static com.mineutze.worldgen.plan.ToroidalTestTables.B;
import static com.mineutze.worldgen.plan.ToroidalTestTables.C;
import static com.mineutze.worldgen.plan.ToroidalTestTables.noLegalTriangleCatalog;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TightnessVerifyTest {

    private static final BiomeGraphData NEVER_SOLVES = noLegalTriangleCatalog(500, 800);
    private static final PlanSettings SMALL_COARSE = new PlanSettings(8, 300, 1.3, new double[] {20, 0}, 208, 0.10, 3_000_000, 60, ToroidalSizedPatternGenerator.DEFAULT_SCHEDULE);

    private static final int SHAPE_ATTEMPTS = 9;
    private static final int A_C_ATTEMPTS = 8;
    private static final int B_C_ATTEMPTS = 7;
    private static final int FAILED_ATTEMPTS = 10;

    private static void writeRanking(Path file) {
        TightnessFile.write(file, new TightnessRanking(List.of(
            new RankedObstacle(new ShapeLimit(A), SHAPE_ATTEMPTS),
            new RankedObstacle(new LawObstacle(new IllegalPairLaw(A, C)), A_C_ATTEMPTS),
            new RankedObstacle(new LawObstacle(new IllegalPairLaw(B, C)), B_C_ATTEMPTS)
        ), FAILED_ATTEMPTS), NEVER_SOLVES);
    }

    private static void replaceRanking(Path file) {
        TightnessFile.write(file, new TightnessRanking(List.of(new RankedObstacle(new LawObstacle(new IllegalPairLaw(B, C)), 4)), 5), NEVER_SOLVES);
    }

    private static String verifyPicks(Path file, List<Integer> ranks, String pickedOn) {
        return TightnessVerify.verify(file, NEVER_SOLVES, ranks, pickedOn, SMALL_COARSE, Duration.ofSeconds(4), 2, message -> { });
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
    void choicesNumberEveryRankedLawFromOne(@TempDir Path folder) {
        Path file = folder.resolve("tightness.json");
        writeRanking(file);

        RankingView view = TightnessVerify.choices(file, NEVER_SOLVES);

        assertEquals(List.of(1, 2, 3), view.choices().stream().map(RankingChoice::rank).toList());
    }

    @Test
    void choicesKeepHowManyAttemptsEachLawBroke(@TempDir Path folder) {
        Path file = folder.resolve("tightness.json");
        writeRanking(file);

        RankingView view = TightnessVerify.choices(file, NEVER_SOLVES);

        assertEquals(List.of(SHAPE_ATTEMPTS, A_C_ATTEMPTS, B_C_ATTEMPTS), view.choices().stream().map(RankingChoice::attemptsBroken).toList());
        assertEquals(FAILED_ATTEMPTS, view.failedAttempts());
    }

    @Test
    void choicesSayHowVerifyWouldLoosenALaw(@TempDir Path folder) {
        Path file = folder.resolve("tightness.json");
        writeRanking(file);

        RankingChoice pair = TightnessVerify.choices(file, NEVER_SOLVES).choices().get(1);

        assertTrue(pair.loosening().orElseThrow().startsWith("make " + A + " and " + C), pair.toString());
    }

    @Test
    void choicesMarkALawVerifyCannotLoosen(@TempDir Path folder) {
        Path file = folder.resolve("tightness.json");
        writeRanking(file);

        RankingChoice shape = TightnessVerify.choices(file, NEVER_SOLVES).choices().get(0);

        assertTrue(shape.law().contains("shape limit"), shape.toString());
        assertEquals(Optional.empty(), shape.loosening());
    }

    /** Edited graph: list kept, minus laws the edit changed, marked as counted on the previous version. */
    @Test
    void choicesAfterAnEditKeepWhatTheGraphStillStates(@TempDir Path folder) {
        Path file = folder.resolve("tightness.json");
        writeRanking(file);
        BiomeGraphData withAcLegal = GraphEdits.withLegalPair(NEVER_SOLVES, A, C);

        RankingView view = TightnessVerify.choices(file, withAcLegal);

        assertFalse(view.measuredOnThisGraph());
        assertEquals(1, view.removedByEdits());
        assertEquals(List.of(new ShapeLimit(A).describe(), new IllegalPairLaw(B, C).describe()), view.choices().stream().map(RankingChoice::law).toList());
    }

    /** Picks made before an edit: rank 2 meant A/C, now it means B/C. */
    @Test
    void checkPicksRefusesPicksMadeBeforeAnEdit(@TempDir Path folder) {
        Path file = folder.resolve("tightness.json");
        writeRanking(file);
        String beforeEdit = TightnessVerify.choices(file, NEVER_SOLVES).fingerprint();
        BiomeGraphData withAcLegal = GraphEdits.withLegalPair(NEVER_SOLVES, A, C);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> TightnessVerify.checkPicks(file, withAcLegal, List.of(2), beforeEdit));

        assertTrue(refused.getMessage().contains("ranking changed"), refused.getMessage());
    }

    @Test
    void choicesCarryTheSavedRankingsFingerprint(@TempDir Path folder) throws IOException {
        Path file = folder.resolve("tightness.json");
        writeRanking(file);

        assertEquals(RankingFingerprint.of(Files.readString(file), WorldBiomePlan.fingerprintOf(NEVER_SOLVES)), TightnessVerify.choices(file, NEVER_SOLVES).fingerprint());
    }

    @Test
    void checkPicksAcceptsPicksMadeOnTheSavedRanking(@TempDir Path folder) {
        Path file = folder.resolve("tightness.json");
        writeRanking(file);
        String fingerprint = TightnessVerify.choices(file, NEVER_SOLVES).fingerprint();

        assertDoesNotThrow(() -> TightnessVerify.checkPicks(file, NEVER_SOLVES, List.of(2, 3), fingerprint));
    }

    /** A Solve check replaced the ranking after the page showed it: rank 1 is now another (loosenable) law. */
    @Test
    void checkPicksRefusesPicksMadeOnAnOlderRanking(@TempDir Path folder) {
        Path file = folder.resolve("tightness.json");
        writeRanking(file);
        String olderFingerprint = TightnessVerify.choices(file, NEVER_SOLVES).fingerprint();
        replaceRanking(file);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> TightnessVerify.checkPicks(file, NEVER_SOLVES, List.of(1), olderFingerprint));

        assertTrue(refused.getMessage().contains("ranking changed"), refused.getMessage());
    }

    @Test
    void checkPicksRefusesALawThatCannotBeLoosened(@TempDir Path folder) {
        Path file = folder.resolve("tightness.json");
        writeRanking(file);
        String fingerprint = TightnessVerify.choices(file, NEVER_SOLVES).fingerprint();

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> TightnessVerify.checkPicks(file, NEVER_SOLVES, List.of(1), fingerprint));

        assertTrue(refused.getMessage().contains("cannot be loosened"), refused.getMessage());
    }

    @Test
    void verifyMeasuresOnlyThePickedLaws(@TempDir Path folder) {
        Path file = folder.resolve("tightness.json");
        writeRanking(file);
        String fingerprint = TightnessVerify.choices(file, NEVER_SOLVES).fingerprint();

        String report = verifyPicks(file, List.of(3), fingerprint);

        assertTrue(report.contains("make " + B + " and " + C), report);
        assertFalse(report.contains("make " + A + " and " + C), report);
        assertFalse(report.contains("Skipped"), report);
    }

    /** The ranking can change between the server's check and the run reading it: the run checks again. */
    @Test
    void verifyRefusesPicksMadeOnAnOlderRankingWithoutRunning(@TempDir Path folder) {
        Path file = folder.resolve("tightness.json");
        writeRanking(file);
        String olderFingerprint = TightnessVerify.choices(file, NEVER_SOLVES).fingerprint();
        replaceRanking(file);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> verifyPicks(file, List.of(1), olderFingerprint));

        assertTrue(refused.getMessage().contains("ranking changed"), refused.getMessage());
    }

    @Test
    void theDefaultMeasuresAtMostThreeAndNamesWhatItSkipped(@TempDir Path folder) {
        Path file = folder.resolve("tightness.json");
        TightnessFile.write(file, new TightnessRanking(List.of(
            new RankedObstacle(new ShapeLimit(A), 9),
            new RankedObstacle(new LawObstacle(new IllegalPairLaw(A, C)), 8),
            new RankedObstacle(new LawObstacle(new IllegalPairLaw(B, C)), 7),
            new RankedObstacle(new LawObstacle(new IllegalPairLaw(A, B)), 6),
            new RankedObstacle(new LawObstacle(new SizeRangeLaw(A, 1, 10)), 5)
        ), FAILED_ATTEMPTS), NEVER_SOLVES);

        String report = verify(file);

        assertEquals(1 + VerifyPicks.MAX_PICKS, report.lines().filter(line -> line.startsWith("  - ")).count(), report);
        assertFalse(report.contains("widen " + A), report);
        assertTrue(report.contains("Skipped, not loosenable in the graph: " + new ShapeLimit(A).describe()), report);
    }

    @Test
    void savesNoRankingForAnInfeasibleVerdict(@TempDir Path folder) {
        Path file = folder.resolve("tightness.json");
        FeasibilityVerdict infeasible = FeasibilityVerdict.infeasible(InfeasibilityProof.rejectedByGenerator("no"), Evidence.EMPTY, 0.01, Duration.ZERO);

        TightnessVerify.saveRanking(file, infeasible, NEVER_SOLVES);

        assertFalse(Files.exists(file));
    }

    /** FEASIBLE short of perfect: its failures are what Verify can improve. */
    @Test
    void savesTheRankingOfAFeasibleVerdictWithFailedAttempts(@TempDir Path folder) {
        Path file = folder.resolve("tightness.json");
        BuiltWorld world = new BuiltWorld(Map.of(), new BiomeMap(1, new int[] {0}, new int[] {0}, List.of(A), 1.0, 1.0), 1);
        TightnessRanking coarse = new TightnessRanking(List.of(new RankedObstacle(new LawObstacle(new IllegalPairLaw(A, C)), 2)), 3);
        FeasibilityVerdict feasible = FeasibilityVerdict.feasible(world, Evidence.EMPTY, new ReliabilityEstimate(0.0, 0.2),
            Optional.of(new StageTightness(coarse, new TightnessRanking(List.of(), 0))), 0.01, Duration.ZERO);

        TightnessVerify.saveRanking(file, feasible, NEVER_SOLVES);

        assertEquals(coarse, TightnessFile.read(file, NEVER_SOLVES));
    }
}
