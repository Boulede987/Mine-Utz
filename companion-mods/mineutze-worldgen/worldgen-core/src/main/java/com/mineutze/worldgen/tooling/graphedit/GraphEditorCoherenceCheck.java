package com.mineutze.worldgen.tooling.graphedit;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphJson;
import com.mineutze.worldgen.coherence.PeriodicCoherenceProof;
import com.mineutze.worldgen.coherence.RawRuleTable;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The graph editor's "Coherence proof" button - same relationship {@link
 * GraphEditorDeepCheck} has to {@code GeometricFeasibilityProof}: runs {@link
 * PeriodicCoherenceProof}'s own real, independent existence-proof search against THIS
 * tool's currently SAVED graph ({@link GraphEditorFiles#DATA_FILE}), not the built
 * classpath resource, so it reflects in-progress edits.
 *
 * <p>This check is fully independent of the live generator by design (see {@code
 * com.mineutze.worldgen.coherence}'s own package doc) - unlike Deep Check, it does NOT
 * exercise {@code HexMeshBuilder}/{@code WeightedVertexPlacement} at all. A success here is
 * a definitive proof the graph admits SOME infinite valid world, not evidence about
 * whether the live generator specifically can find one.
 *
 * <p>Separate JVM process (see {@code GraphEditorServer#handleCoherenceCheck}), same
 * reasoning as the test-runner and Deep Check buttons.
 */
final class GraphEditorCoherenceCheck {

    private static final Path REPORT_PATH = Path.of("doc", "graph-coherence-existence-proof-report-editor.txt");

    private GraphEditorCoherenceCheck() {
    }

    public static void main(String[] args) throws IOException {
        BiomeGraphData data = BiomeGraphJson.load(GraphEditorFiles.DATA_FILE);
        RawRuleTable table = RawRuleTable.from(data);
        PeriodicCoherenceProof.runProof(
            table, REPORT_PATH, "Graph editor's current saved graph (" + GraphEditorFiles.DATA_FILE.toAbsolutePath() + ")"
        );
        // Same convention GraphEditorDeepCheck's own doc explains - GraphEditorServer's
        // report-link detection (shared with the test-runner buttons) looks for exactly
        // this shape in stdout, and runProof already prints its own, but a guaranteed one
        // right at the end (after any inconclusive rowCount-sweep chatter) keeps it easy
        // to find even when the earlier lines scroll past.
        System.out.println("Report link: " + REPORT_PATH.toAbsolutePath().toUri());
    }
}
