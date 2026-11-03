package com.mineutze.worldgen.tooling.graphedit;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphJson;
import com.mineutze.worldgen.plan.ReliabilityReport;
import com.mineutze.worldgen.plan.ReliabilityRun;
import com.mineutze.worldgen.plan.TightnessVerify;

import java.util.List;

/**
 * Graph editor's "Reliability run" button. Prints {@code RELIABILITY: MET | NOT MET | UNDECIDED} for the editor UI, and
 * saves the ranking of the failures it saw for Verify. Own JVM like the Solve check: uses every core.
 */
final class GraphEditorReliability {

    private static final int EXIT_MET = 0;
    private static final int EXIT_NOT_MET = 1;
    private static final int EXIT_UNDECIDED = 2;
    private static final int EXIT_CRASH = 3;

    private GraphEditorReliability() {
    }

    public static void main(String[] args) {
        int exitCode;
        try {
            exitCode = measureSavedGraph(requestFrom(args));
        } catch (RuntimeException | Error crash) {
            System.out.println("RELIABILITY: CRASH");
            System.out.println("Reliability run crashed (graph " + GraphEditorFiles.DATA_FILE.toAbsolutePath() + ", ranking " + GraphEditorFiles.TIGHTNESS_RANKING.toAbsolutePath() + "):");
            crash.printStackTrace(System.out);
            exitCode = EXIT_CRASH;
        }
        if (exitCode != EXIT_MET) {
            System.exit(exitCode);
        }
    }

    static ReliabilityRequest requestFrom(String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("GraphEditorReliability: expected one argument, the minutes, got " + List.of(args));
        }
        return ReliabilityRequest.fromArgument(args[0]);
    }

    /** Old ranking kept until the run ends: a stopped run leaves the previous list in place. */
    private static int measureSavedGraph(ReliabilityRequest request) {
        BiomeGraphData catalog = BiomeGraphJson.load(GraphEditorFiles.DATA_FILE);
        System.out.println("Reliability run of " + GraphEditorFiles.DATA_FILE.toAbsolutePath() + ": " + catalog.nodes().size() + " biomes, " + request.minutes() + " min.");
        ReliabilityReport report = ReliabilityRun.run(catalog, request.wallTime(), Runtime.getRuntime().availableProcessors(), message -> System.out.println("  " + message));
        System.out.println("RELIABILITY: " + report.verdict().label());
        System.out.println(report.summary());
        if (!rankingSavedAfterVerdict(report, catalog)) {
            return EXIT_CRASH;
        }
        return switch (report.verdict()) {
            case MET -> EXIT_MET;
            case NOT_MET -> EXIT_NOT_MET;
            case UNDECIDED -> EXIT_UNDECIDED;
        };
    }

    /** After the verdict line: a failed save must not hide the verdict, but must not pass as success either (exit code). */
    private static boolean rankingSavedAfterVerdict(ReliabilityReport report, BiomeGraphData catalog) {
        try {
            TightnessVerify.saveRanking(GraphEditorFiles.TIGHTNESS_RANKING, report, catalog);
            return true;
        } catch (RuntimeException failure) {
            System.out.println("RANKING NOT SAVED (" + GraphEditorFiles.TIGHTNESS_RANKING.toAbsolutePath() + "): " + failure);
            return false;
        }
    }
}
