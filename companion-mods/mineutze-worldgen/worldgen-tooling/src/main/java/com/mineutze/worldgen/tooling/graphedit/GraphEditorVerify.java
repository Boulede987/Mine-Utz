package com.mineutze.worldgen.tooling.graphedit;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphJson;
import com.mineutze.worldgen.plan.RankingUnavailable;
import com.mineutze.worldgen.plan.TightnessVerify;

/**
 * Graph editor's "Verify" button. Prints {@code VERIFY: <status>} for the editor UI - a measurement has no pass/fail.
 * Own JVM like the Solve check: uses every core.
 */
final class GraphEditorVerify {

    private static final int EXIT_NO_RANKING = 2;
    private static final int EXIT_CRASH = 3;

    private GraphEditorVerify() {
    }

    public static void main(String[] args) {
        try {
            BiomeGraphData catalog = BiomeGraphJson.load(GraphEditorFiles.DATA_FILE);
            int threads = Runtime.getRuntime().availableProcessors();
            String report = TightnessVerify.verify(GraphEditorFiles.TIGHTNESS_RANKING, catalog, threads, message -> System.out.println("  " + message));
            System.out.println("VERIFY: MEASURED");
            System.out.println(report);
        } catch (RankingUnavailable missing) {
            System.out.println("VERIFY: NO RANKING");
            System.out.println(missing.getMessage());
            System.exit(EXIT_NO_RANKING);
        } catch (RuntimeException | Error crash) {
            System.out.println("VERIFY: CRASH");
            System.out.println("Verify crashed (graph " + GraphEditorFiles.DATA_FILE.toAbsolutePath() + ", ranking " + GraphEditorFiles.TIGHTNESS_RANKING.toAbsolutePath() + "):");
            crash.printStackTrace(System.out);
            System.exit(EXIT_CRASH);
        }
    }
}
