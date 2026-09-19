package com.mineutze.worldgen.reachability;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphJson;
import com.mineutze.worldgen.coherence.RawRuleTable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Abstract reproduction of the real mechanism {@code DeadEndFrequencySimulation
 * .STRUCTURE_RING_SWEEP} just found live (11/12 dead ends, every one a 46-57-face joint
 * free-cluster failure) - see package doc. Runs the SAME qualitative shape (many query
 * origins sharing one {@link HexPromotionSimulator}) at two spacing regimes (sparse:
 * isolated, expected clean; dense: overlapping, expected failures) to check the abstract
 * model reproduces what the real live code showed, then a spacing sweep to characterize
 * where the transition actually sits - a real, actionable number, not just a yes/no.
 *
 * <p><b>Honest limitation:</b> {@code GROWTH_CAP_HEX_DISTANCE} here (6) is smaller than
 * real production's own ratio (~7.7, {@code CLUSTER_GROWTH_CAP_RADIUS_BLOCKS}/{@code
 * HEX_SIZE_BLOCKS}) - plain backtracking (no AC-3/forward-checking, see {@link
 * LocalClusterSolver}'s own doc) on a full ~7.7-ring fresh cluster (~217 faces) is
 * expensive to run exhaustively across many origins; 6 rings (~127 faces) keeps this
 * tractable while staying close. A finding here is real evidence about the mechanism, not
 * a byte-exact reproduction of production's own numbers.
 */
public final class JointClusterReachabilityProof {

    private static final Path REPORT_PATH = Path.of("doc", "joint-cluster-reachability-report.txt");
    private static final int GROWTH_CAP_HEX_DISTANCE = 6;
    private static final int RING_COUNT = 3;
    private static final int SPARSE_ORIGIN_COUNT = 15;

    private JointClusterReachabilityProof() {
    }

    public static void main(String[] args) throws IOException {
        BiomeGraphData data = BiomeGraphJson.loadResource(RawRuleTable.class, "/biomegraph/biome-graph.json");
        RawRuleTable table = RawRuleTable.from(data);
        runProof(table, REPORT_PATH, "production ModBiomeGraph.OVERWORLD (src/main/resources/biomegraph/biome-graph.json)");
    }

    public static void runProof(RawRuleTable table, Path reportPath, String label) throws IOException {
        StringBuilder report = new StringBuilder();
        report.append("JOINT-CLUSTER REACHABILITY - abstract reproduction of the real STRUCTURE_RING_SWEEP mechanism\n");
        report.append("========================================================================================\n\n");
        report.append("Against: ").append(label).append("\n");
        report.append("Growth cap: ").append(GROWTH_CAP_HEX_DISTANCE).append(" hex-rings (see this class's own doc on why smaller than production's ~7.7)\n\n");

        ScenarioResult sparse = runScenario(table, "SPARSE (isolated origins, spacing 4x growth cap)", lineOrigins(SPARSE_ORIGIN_COUNT, GROWTH_CAP_HEX_DISTANCE * 4));
        ScenarioResult dense = runScenario(table, "DENSE (concentric rings, spacing 2 << growth cap)", ringOrigins(2, RING_COUNT));

        appendScenario(report, sparse);
        appendScenario(report, dense);

        report.append("--- Spacing sweep (fixed growth cap ").append(GROWTH_CAP_HEX_DISTANCE).append(", ring count ").append(RING_COUNT).append(") ---\n");
        System.out.println("Running spacing sweep...");
        for (int spacing = 1; spacing <= GROWTH_CAP_HEX_DISTANCE + 2; spacing++) {
            ScenarioResult result = runScenario(table, "spacing=" + spacing, ringOrigins(spacing, RING_COUNT));
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

    static ScenarioResult runScenario(RawRuleTable table, String label, List<AxialPos> origins) {
        HexPromotionSimulator simulator = new HexPromotionSimulator(table, GROWTH_CAP_HEX_DISTANCE);
        List<AxialPos> deadEndOrigins = new ArrayList<>();
        List<String> deadEndMessages = new ArrayList<>();
        for (AxialPos origin : origins) {
            HexPromotionSimulator.QueryResult result = simulator.query(origin);
            if (result.deadEnd()) {
                deadEndOrigins.add(origin);
                deadEndMessages.add(result.message());
            }
        }
        return new ScenarioResult(label, origins, deadEndOrigins, deadEndMessages);
    }

    /** {@code count} origins spaced {@code spacing} hex-steps apart along a straight line - cheap, guaranteed-isolated baseline (no cluster can reach a neighboring origin's own cluster). */
    static List<AxialPos> lineOrigins(int count, int spacing) {
        List<AxialPos> origins = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            origins.add(new AxialPos(i * spacing, 0));
        }
        return origins;
    }

    /** Concentric hex rings at radii {@code spacing, 2*spacing, ..., ringCount*spacing} around the origin - real shape STRUCTURE_RING_SWEEP mirrors, in native axial coordinates (see {@link AxialPos#ring}). */
    static List<AxialPos> ringOrigins(int spacing, int ringCount) {
        List<AxialPos> origins = new ArrayList<>();
        AxialPos center = new AxialPos(1_000_000, 1_000_000); // arbitrary far-from-zero anchor, avoids any accidental collision with lineOrigins' own coordinates when both run against a shared simulator in tests
        for (int ring = 1; ring <= ringCount; ring++) {
            origins.addAll(AxialPos.ring(center, ring * spacing));
        }
        return origins;
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
