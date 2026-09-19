package com.mineutze.worldgen.reachability;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphJson;
import com.mineutze.worldgen.coherence.RawRuleTable;
import com.mineutze.worldgen.regionsolve.topology.HexGeometry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Same multi-origin stress shape as {@link JointClusterReachabilityProof}, this time
 * through {@link SizeAwareHexPromotionSimulator} - adds the SIZE dimension via self-
 * adjacency-as-clustering (see that simulator's own doc for why an independent size model,
 * not the live power-diagram mechanism, is the right choice: this tool proves properties
 * of the abstract rule GRAPH, not of the specific live algorithm - 2026-09-17 user
 * direction).
 */
public final class SizeAwareJointClusterReachabilityProof {

    private static final Path REPORT_PATH = Path.of("doc", "size-aware-joint-cluster-reachability-report.txt");
    private static final int GROWTH_CAP_HEX_DISTANCE = 6;
    private static final int RING_COUNT = 3;
    private static final int SPARSE_ORIGIN_COUNT = 15;

    // Real production hex-cell scale (325 circumradius, matches TriangleFieldGeometryRealization's
    // own "650-block undisplaced width (circumradius 325)" constant) via HexGeometry's general
    // regular-hexagon formula - reused ONLY for a realistic scale calibration against real
    // declared BiomeSizeRanges, not a dependency on any live algorithm CODE (general geometry
    // math is fine here, live mechanism code is not - see package doc). See
    // ClusterAreaMath.clusterWidthBlocks's own doc for why this raw AREA is never compared
    // directly against a biome's declared min/maxWidthBlocks - it must be converted back to
    // an equivalent WIDTH first (real 2026-09-17 fix - a real bug, not a hypothetical one).
    private static final double HEX_CIRCUMRADIUS_BLOCKS = 325;
    private static final double BASELINE_FACE_AREA_BLOCKS2 = HexGeometry.areaForCircumradius(HEX_CIRCUMRADIUS_BLOCKS);

    private SizeAwareJointClusterReachabilityProof() {
    }

    public static void main(String[] args) throws IOException {
        BiomeGraphData data = BiomeGraphJson.loadResource(RawRuleTable.class, "/biomegraph/biome-graph.json");
        RawRuleTable table = RawRuleTable.from(data);
        runProof(table, REPORT_PATH, "production ModBiomeGraph.OVERWORLD (src/main/resources/biomegraph/biome-graph.json)");
    }

    public static void runProof(RawRuleTable table, Path reportPath, String label) throws IOException {
        StringBuilder report = new StringBuilder();
        report.append("SIZE-AWARE JOINT-CLUSTER REACHABILITY - identity + self-adjacency-clustering size dimension\n");
        report.append("===========================================================================================\n\n");
        report.append("Against: ").append(label).append("\n");
        report.append("Growth cap: ").append(GROWTH_CAP_HEX_DISTANCE).append(" hex-rings\n");
        report.append("Baseline face area: ").append(BASELINE_FACE_AREA_BLOCKS2).append(" blocks² (real HEX_SIZE_BLOCKS=325 scale, general hexagon-area math, not live algorithm code)\n\n");

        ScenarioResult sparse = runScenario(
            table, "SPARSE (isolated origins, spacing 4x growth cap)", JointClusterReachabilityProof.lineOrigins(SPARSE_ORIGIN_COUNT, GROWTH_CAP_HEX_DISTANCE * 4)
        );
        ScenarioResult dense = runScenario(
            table, "DENSE (concentric rings, spacing 2 << growth cap)", JointClusterReachabilityProof.ringOrigins(2, RING_COUNT)
        );

        appendScenario(report, sparse);
        appendScenario(report, dense);

        report.append("--- Spacing sweep (fixed growth cap ").append(GROWTH_CAP_HEX_DISTANCE).append(", ring count ").append(RING_COUNT).append(") ---\n");
        System.out.println("Running spacing sweep...");
        for (int spacing = 1; spacing <= GROWTH_CAP_HEX_DISTANCE + 2; spacing++) {
            ScenarioResult result = runScenario(table, "spacing=" + spacing, JointClusterReachabilityProof.ringOrigins(spacing, RING_COUNT));
            String line = "  spacing=" + spacing + ": " + result.deadEnds() + "/" + result.origins().size() + " dead ends";
            report.append(line).append("\n");
            System.out.println(line);
        }

        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, report.toString(), StandardCharsets.UTF_8);
        System.out.println();
        System.out.println(sparse.label() + ": " + sparse.deadEnds() + "/" + sparse.origins().size() + " dead ends");
        System.out.println(dense.label() + ": " + dense.deadEnds() + "/" + dense.origins().size() + " dead ends");
        System.out.println("Report: " + reportPath.toAbsolutePath().toUri());
    }

    record ScenarioResult(String label, List<AxialPos> origins, List<AxialPos> deadEndOrigins, List<String> deadEndMessages) {
        int deadEnds() {
            return deadEndOrigins.size();
        }
    }

    private static ScenarioResult runScenario(RawRuleTable table, String label, List<AxialPos> origins) {
        SizeAwareHexPromotionSimulator simulator = new SizeAwareHexPromotionSimulator(table, GROWTH_CAP_HEX_DISTANCE, BASELINE_FACE_AREA_BLOCKS2);
        List<AxialPos> deadEndOrigins = new ArrayList<>();
        List<String> deadEndMessages = new ArrayList<>();
        for (AxialPos origin : origins) {
            SizeAwareHexPromotionSimulator.QueryResult result = simulator.query(origin);
            if (result.deadEnd()) {
                deadEndOrigins.add(origin);
                deadEndMessages.add(result.message());
            }
        }
        return new ScenarioResult(label, origins, deadEndOrigins, deadEndMessages);
    }

    private static void appendScenario(StringBuilder report, ScenarioResult result) {
        report.append("--- ").append(result.label()).append(" (").append(result.origins().size()).append(" origins) ---\n");
        report.append("  ").append(result.deadEnds()).append("/").append(result.origins().size()).append(" dead ends\n");
        for (int i = 0; i < result.deadEndOrigins().size(); i++) {
            report.append("  DEAD END at ").append(result.deadEndOrigins().get(i)).append(": ").append(result.deadEndMessages().get(i)).append("\n");
        }
        report.append("\n");
    }
}
