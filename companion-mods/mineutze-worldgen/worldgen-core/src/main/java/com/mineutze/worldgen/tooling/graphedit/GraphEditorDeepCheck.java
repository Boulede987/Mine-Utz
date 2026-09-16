package com.mineutze.worldgen.tooling.graphedit;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphJson;
import com.mineutze.worldgen.export.GeometricFeasibilityProof;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The graph editor's "Deep check" button (2026-09-15, real user request after asking
 * "does it check the patterns we determined and fixed, which resulted in dead ends?" -
 * live warnings only ever ran the two cheap {@code BridgeMotifChecker} screens, never the
 * real exhaustive solver). Runs {@link GeometricFeasibilityProof}'s own real, exhaustive
 * check - same engine, same completeness guarantee as {@code ModBiomeGraph.OVERWORLD}'s
 * own {@code geometricFeasibilityProof} gradle task - but against THIS tool's currently
 * SAVED graph ({@link GraphEditorFiles#DATA_FILE}), not the hardcoded production catalog
 * (this tool's data file and {@code ModBiomeGraph} are still separate - see {@link
 * GraphEditorServer}'s own doc), so it actually reflects your in-progress edits.
 *
 * <p>Separate JVM process (see {@code GraphEditorServer#handleDeepCheck}), not called
 * in-process from the HTTP server - same reasoning as the existing test-runner buttons:
 * keeps a slow, heavy check off the live server's own request-handling threads.
 *
 * <p><b>NOT included</b>: {@code DeadEndFrequencySimulation} (real measured dead-end
 * rate, ~35-40 minutes at 100,000 trials) - too slow for an interactive button, stays a
 * separate, manual, long-running gradle task outside this tool.
 */
final class GraphEditorDeepCheck {

    private static final Path REPORT_PATH = Path.of("doc", "geometric-feasibility-proof-report-editor.txt");

    private GraphEditorDeepCheck() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        BiomeGraphData data = BiomeGraphJson.load(GraphEditorFiles.DATA_FILE);
        BiomeGraph graph = BiomeGraphJson.toBiomeGraph(data);
        GeometricFeasibilityProof.runProof(
            graph, REPORT_PATH, "Graph editor's current saved graph (" + GraphEditorFiles.DATA_FILE.toAbsolutePath() + ")"
        );
        // A real file:// URI (not just the raw path runProof itself already printed) -
        // GraphEditorServer's own report-link detection (same mechanism the test-runner
        // buttons use) looks for exactly this shape in stdout.
        System.out.println("Report link: " + REPORT_PATH.toAbsolutePath().toUri());
    }
}
