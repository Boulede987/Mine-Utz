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

    /** User decision 2026-10-31: top 3 loosenings, up to 10 min. */
    public static final Duration EDITOR_VERIFY_TIME = Duration.ofMinutes(10);
    private static final int LOOSENINGS_TRIED = 3;
    /** Each bound one-sided at 97.5%, so the printed range is a two-sided 95% range. */
    private static final double BOUND_CONFIDENCE = 0.975;
    private static final double PERCENT_PER_UNIT = 100.0;

    private TightnessVerify() {
    }

    /** Coarse ranking of an UNPROVEN verdict; nothing for other outcomes. */
    public static void saveRanking(Path file, FeasibilityVerdict verdict, BiomeGraphData catalog) {
        verdict.tightness().ifPresent(stages -> TightnessFile.write(file, stages.coarse(), catalog));
    }

    /** @throws RankingUnavailable no ranking saved, or saved for another version of the graph */
    public static String verify(Path rankingFile, BiomeGraphData catalog, int threads, WorldBiomePlan.ProgressListener progress) {
        return verify(rankingFile, catalog, PlanSettings.DEFAULT, EDITOR_VERIFY_TIME, threads, progress);
    }

    static String verify(Path rankingFile, BiomeGraphData catalog, PlanSettings settings, Duration wallTime, int threads, WorldBiomePlan.ProgressListener progress) {
        TightnessRanking ranking = TightnessFile.read(rankingFile, catalog);
        List<LoosenedGraph> loosenings = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (RankedObstacle ranked : ranking.entries()) {
            if (loosenings.size() == LOOSENINGS_TRIED) {
                break;
            }
            sortObstacle(ranked, catalog, loosenings, skipped);
        }
        if (loosenings.isEmpty()) {
            return "Verify run: nothing to measure - none of the ranked obstacles can be loosened in the graph." + skippedLine(skipped);
        }
        List<ArmResult> results = new SolveRateTrial(settings, threads, wallTime, BOUND_CONFIDENCE).run(catalog, loosenings, progress);
        return report(results) + skippedLine(skipped);
    }

    private static void sortObstacle(RankedObstacle ranked, BiomeGraphData catalog, List<LoosenedGraph> loosenings, List<String> skipped) {
        Optional<LoosenedGraph> loosened = Loosening.of(ranked.obstacle(), catalog);
        loosened.ifPresentOrElse(loosenings::add, () -> skipped.add(ranked.obstacle().describe()));
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
