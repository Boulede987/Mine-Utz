package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** The editor's entry to the tightness ranking and the Verify run (ranking types stay package-private). */
public final class TightnessVerify {

    /** User decision 2026-10-31: up to 10 min (how many loosenings: VerifyPicks). */
    public static final Duration EDITOR_VERIFY_TIME = Duration.ofMinutes(10);
    public static final int MAX_PICKS = VerifyPicks.MAX_PICKS;
    /** Each bound one-sided at 97.5%, so the printed range is a two-sided 95% range. */
    private static final double BOUND_CONFIDENCE = 0.975;
    private static final double PERCENT_PER_UNIT = 100.0;

    private TightnessVerify() {
    }

    /** Coarse ranking whenever attempts failed (UNPROVEN, or FEASIBLE short of perfect); nothing otherwise. */
    public static void saveRanking(Path file, FeasibilityVerdict verdict, BiomeGraphData catalog) {
        verdict.tightness().ifPresent(stages -> TightnessFile.write(file, stages.coarse(), catalog));
    }

    /**
     * Coarse ranking of a Reliability run when coarse attempts failed (Verify measures coarse attempts; refinement failures
     * are in the report text only); otherwise the old file is removed - it would describe a smaller, older sample.
     */
    public static void saveRanking(Path file, ReliabilityReport report, BiomeGraphData catalog) {
        if (report.tightness().coarse().failedAttempts() > 0) {
            TightnessFile.write(file, report.tightness().coarse(), catalog);
            return;
        }
        TightnessFile.delete(file);
    }

    /** On an edited graph: entries it no longer states removed. @throws RankingUnavailable no usable ranking saved */
    public static RankingView choices(Path rankingFile, BiomeGraphData catalog) {
        SavedRanking saved = TightnessFile.readSaved(rankingFile, catalog);
        List<RankedObstacle> entries = saved.ranking().entries();
        List<RankingChoice> choices = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            choices.add(choiceAt(index, entries.get(index), catalog));
        }
        return new RankingView(choices, saved.ranking().failedAttempts(), saved.fingerprint(), saved.measuredOnThisGraph(), saved.removedByEdits());
    }

    private static RankingChoice choiceAt(int index, RankedObstacle ranked, BiomeGraphData catalog) {
        Optional<String> loosening = Loosening.of(ranked.obstacle(), catalog).map(LoosenedGraph::label);
        return new RankingChoice(index + 1, ranked.obstacle().describe(), ranked.attemptsBroken(), loosening);
    }

    /**
     * @throws IllegalArgumentException picks refused (none, over MAX_PICKS, duplicate, outside the ranking, not
     *     loosenable), or picked on another ranking than the saved one (a newer Solve check replaced it)
     * @throws RankingUnavailable no usable ranking saved
     */
    public static void checkPicks(Path rankingFile, BiomeGraphData catalog, List<Integer> ranks, String pickedOn) {
        picksOn(TightnessFile.readSaved(rankingFile, catalog), catalog, ranks, pickedOn);
    }

    /** Measures exactly the picked ranks (counted from 1), re-checked here: the file may change after the server's check. */
    public static String verify(Path rankingFile, BiomeGraphData catalog, List<Integer> ranks, String pickedOn, int threads, WorldBiomePlan.ProgressListener progress) {
        return verify(rankingFile, catalog, ranks, pickedOn, PlanSettings.DEFAULT, EDITOR_VERIFY_TIME, threads, progress);
    }

    static String verify(Path rankingFile, BiomeGraphData catalog, List<Integer> ranks, String pickedOn, PlanSettings settings, Duration wallTime, int threads, WorldBiomePlan.ProgressListener progress) {
        VerifyPicks picks = picksOn(TightnessFile.readSaved(rankingFile, catalog), catalog, ranks, pickedOn);
        return measure(picks, catalog, settings, wallTime, threads, progress);
    }

    private static VerifyPicks picksOn(SavedRanking saved, BiomeGraphData catalog, List<Integer> ranks, String pickedOn) {
        saved.requireFingerprint(pickedOn);
        return VerifyPicks.of(saved.ranking(), catalog, ranks);
    }

    /** @throws RankingUnavailable no usable ranking saved */
    public static String verify(Path rankingFile, BiomeGraphData catalog, int threads, WorldBiomePlan.ProgressListener progress) {
        return verify(rankingFile, catalog, PlanSettings.DEFAULT, EDITOR_VERIFY_TIME, threads, progress);
    }

    static String verify(Path rankingFile, BiomeGraphData catalog, PlanSettings settings, Duration wallTime, int threads, WorldBiomePlan.ProgressListener progress) {
        VerifyPicks picks = VerifyPicks.topLoosenable(TightnessFile.read(rankingFile, catalog), catalog);
        return measure(picks, catalog, settings, wallTime, threads, progress);
    }

    private static String measure(VerifyPicks picks, BiomeGraphData catalog, PlanSettings settings, Duration wallTime, int threads, WorldBiomePlan.ProgressListener progress) {
        if (picks.loosenings().isEmpty()) {
            return "Verify run: nothing to measure - none of the ranked obstacles can be loosened in the graph." + skippedLine(picks.skipped());
        }
        List<ArmResult> results = new SolveRateTrial(settings, threads, wallTime, BOUND_CONFIDENCE).run(catalog, picks.loosenings(), progress);
        return report(results) + skippedLine(picks.skipped());
    }

    /** A false IMPROVES needs both ranges to miss the true rate: under 5% per loosening guaranteed, far lower in practice. */
    private static String report(List<ArmResult> results) {
        StringBuilder report = new StringBuilder("Verify run (measured, not a proof): coarse attempts per graph, solved k of n, 95% range of the solve rate."
            + " IMPROVES = its whole range above the graph-as-is range (chance of a false IMPROVES under 5% per loosening):");
        results.forEach(result -> report.append("\n  - ").append(lineOf(result)));
        return report.toString();
    }

    private static String skippedLine(List<String> skipped) {
        return skipped.isEmpty() ? "" : "\nSkipped, not loosenable in the graph: " + String.join("; ", skipped);
    }

    private static String lineOf(ArmResult result) {
        if (result.rejection().isPresent()) {
            return result.label() + ": the world generator rejects this graph: " + result.rejection().get();
        }
        StageEvidence evidence = result.evidence().orElseThrow();
        double low = BinomialConfidence.lowerBound(evidence.successes(), evidence.trials(), BOUND_CONFIDENCE);
        double high = BinomialConfidence.upperBound(evidence.successes(), evidence.trials(), BOUND_CONFIDENCE);
        return String.format(Locale.ROOT, "%s: solved %s (%.1f%%..%.1f%%)%s", result.label(), evidence.describe(), low * PERCENT_PER_UNIT, high * PERCENT_PER_UNIT,
            result.improves() ? "  IMPROVES on the graph as is" : "");
    }
}
