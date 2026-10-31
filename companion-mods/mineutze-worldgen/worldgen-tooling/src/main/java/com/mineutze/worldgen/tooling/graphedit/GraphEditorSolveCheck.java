package com.mineutze.worldgen.tooling.graphedit;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphJson;
import com.mineutze.worldgen.plan.BuiltWorld;
import com.mineutze.worldgen.plan.FeasibilityBudget;
import com.mineutze.worldgen.plan.FeasibilityCheck;
import com.mineutze.worldgen.plan.FeasibilityVerdict;

import java.io.IOException;
import java.nio.file.Files;

/**
 * Picture written before the verdict line, so a picture failure shows only {@code VERDICT: CRASH}; stale picture deleted first.
 * Graph editor's "Solve check" button: {@link FeasibilityCheck} verdict on saved graph ({@link GraphEditorFiles#DATA_FILE}).
 * Prints {@code VERDICT: <outcome>} line editor UI reads, and {@code WORLD_PICTURE: <path>} when a world was built.
 * Crash gets own verdict + exit code, never mistaken for INFEASIBLE.
 *
 * <p>Own JVM (see {@code GraphEditorServer#handleSolveCheck}), never on HTTP request threads: uses every core.
 */
final class GraphEditorSolveCheck {

    private static final int EXIT_FEASIBLE = 0;
    private static final int EXIT_INFEASIBLE = 1;
    private static final int EXIT_UNPROVEN = 2;
    private static final int EXIT_CRASH = 3;

    private GraphEditorSolveCheck() {
    }

    public static void main(String[] args) {
        int exitCode;
        try {
            exitCode = checkSavedGraph();
        } catch (IOException | RuntimeException crash) {
            System.out.println("VERDICT: CRASH");
            System.out.println("Solve check crashed (graph " + GraphEditorFiles.DATA_FILE.toAbsolutePath() + ", picture " + GraphEditorFiles.VERDICT_WORLD_PICTURE.toAbsolutePath() + "):");
            crash.printStackTrace(System.out);
            exitCode = EXIT_CRASH;
        }
        if (exitCode != EXIT_FEASIBLE) {
            System.exit(exitCode);
        }
    }

    private static int checkSavedGraph() throws IOException {
        Files.deleteIfExists(GraphEditorFiles.VERDICT_WORLD_PICTURE);
        BiomeGraphData catalog = BiomeGraphJson.load(GraphEditorFiles.DATA_FILE);
        System.out.println("Solve check of " + GraphEditorFiles.DATA_FILE.toAbsolutePath() + ": " + catalog.nodes().size() + " biomes.");
        FeasibilityVerdict verdict = FeasibilityCheck.check(catalog, FeasibilityBudget.EDITOR_DEFAULT, message -> System.out.println("  " + message));
        verdict.builtWorld().ifPresent(GraphEditorSolveCheck::writeWorldPicture);
        printVerdict(verdict);
        return exitCodeOf(verdict.outcome());
    }

    private static void printVerdict(FeasibilityVerdict verdict) {
        System.out.println("VERDICT: " + verdict.outcome());
        System.out.println(verdict.summary());
    }

    private static void writeWorldPicture(BuiltWorld world) {
        world.writePicture(GraphEditorFiles.VERDICT_WORLD_PICTURE);
        System.out.println("WORLD_PICTURE: " + GraphEditorFiles.VERDICT_WORLD_PICTURE.toAbsolutePath());
    }

    private static int exitCodeOf(FeasibilityVerdict.Outcome outcome) {
        return switch (outcome) {
            case FEASIBLE -> EXIT_FEASIBLE;
            case INFEASIBLE -> EXIT_INFEASIBLE;
            case UNPROVEN -> EXIT_UNPROVEN;
        };
    }
}
