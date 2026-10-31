package com.mineutze.worldgen.tooling.graphedit;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphJson;
import com.mineutze.worldgen.plan.FeasibilityBudget;
import com.mineutze.worldgen.plan.FeasibilityEstimator;
import com.mineutze.worldgen.plan.FeasibilityVerdict;

import java.io.IOException;

/**
 * Graph editor's "Solve check" button: {@link FeasibilityEstimator} verdict on saved graph ({@link GraphEditorFiles#DATA_FILE}).
 * Prints {@code VERDICT: <outcome>} line editor UI reads. Crash gets own verdict + exit code, never mistaken for FAIL.
 *
 * <p>Own JVM (see {@code GraphEditorServer#handleSolveCheck}), never on HTTP request threads: uses every core.
 */
final class GraphEditorSolveCheck {

    private static final int EXIT_PASS = 0;
    private static final int EXIT_FAIL = 1;
    private static final int EXIT_BORDERLINE = 2;
    private static final int EXIT_CRASH = 3;

    private GraphEditorSolveCheck() {
    }

    public static void main(String[] args) {
        int exitCode;
        try {
            exitCode = checkSavedGraph();
        } catch (IOException | RuntimeException crash) {
            System.out.println("VERDICT: CRASH");
            System.out.println("Solve check crashed on " + GraphEditorFiles.DATA_FILE.toAbsolutePath() + ":");
            crash.printStackTrace(System.out);
            exitCode = EXIT_CRASH;
        }
        if (exitCode != EXIT_PASS) {
            System.exit(exitCode);
        }
    }

    private static int checkSavedGraph() throws IOException {
        BiomeGraphData catalog = BiomeGraphJson.load(GraphEditorFiles.DATA_FILE);
        System.out.println("Solve check of " + GraphEditorFiles.DATA_FILE.toAbsolutePath() + ": " + catalog.nodes().size() + " biomes.");
        FeasibilityVerdict verdict = FeasibilityEstimator.estimate(catalog, FeasibilityBudget.EDITOR_DEFAULT, message -> System.out.println("  " + message));
        printVerdict(verdict);
        return exitCodeOf(verdict.outcome());
    }

    private static void printVerdict(FeasibilityVerdict verdict) {
        System.out.println("VERDICT: " + verdict.outcome());
        System.out.println(verdict.summary());
    }

    private static int exitCodeOf(FeasibilityVerdict.Outcome outcome) {
        return switch (outcome) {
            case PASS -> EXIT_PASS;
            case FAIL -> EXIT_FAIL;
            case BORDERLINE -> EXIT_BORDERLINE;
        };
    }
}
