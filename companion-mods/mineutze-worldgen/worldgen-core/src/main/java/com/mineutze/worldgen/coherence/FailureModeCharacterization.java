package com.mineutze.worldgen.coherence;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.EdgeData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.NodeData;
import com.mineutze.worldgen.biomegraph.BiomeGraphJson;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.coherence.PeriodicRowLayout.Cell;
import com.mineutze.worldgen.coherence.PeriodicRowLayout.Row;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * "Option 2" from the reachability menu (2026-09) - a different kind of tool than {@link
 * ConstructivePathfindingProof} (option 1). That one produces individual certificates for
 * specific states; this one mines real failure data for a cheap, reusable, generalizable
 * WARNING SCREEN - "this row looks risky" - mirroring exactly what {@code
 * BridgeMotifChecker} already is for the live hex-mesh path: a correlate, verified against
 * real search results, never itself a proof.
 *
 * <p><b>Real problem this arc had until now: almost no failure data to mine.</b> The real
 * 66-biome catalog basically never fails (0/10 isolated rows in {@link
 * ReachabilityScoutingChecks}, 10/10 paths found in {@link ConstructivePathfindingProof}).
 * Per explicit user direction, this class also tests against a DELIBERATELY, KNOWINGLY
 * broken synthetic graph (see {@link #dominantHubGraph}) to generate real, reliable failure
 * data to mine and to validate the mined screen actually catches a graph known to be bad -
 * not just stays silent on a graph that happens to be fine.
 *
 * <p><b>The mined pattern:</b> a "dominant biome" - one whose own cells collectively occupy
 * a large fraction of a row's total width. Intuition, confirmed by the dominant-hub graph's
 * own construction: if biome X's total width in a row approaches the row's own full
 * circumference, there is very little non-X "room" left for a second row to phase-shift its
 * own X-instances away from the first row's - and since X-X is always illegal, that leaves
 * almost no way to avoid a collision. A LOW-degree dominant biome makes this worse still
 * (fewer legal partners to fill the non-dominant remainder with, compounding the squeeze).
 */
public final class FailureModeCharacterization {

    private static final Path REPORT_PATH = Path.of("doc", "graph-coherence-failure-mode-characterization-report.txt");
    private static final long RANDOM_SEED = 20260916L;
    private static final int MIN_CELLS_PER_ROW = 6;
    private static final int MAX_CELLS_PER_ROW = 16;
    private static final int ANCHOR_BUILD_RETRIES = 300;
    private static final int SUCCESSOR_SEARCH_RETRIES = 2000;
    private static final int SAMPLE_SIZE = 25;

    // Mined threshold (2026-09) - see this class's own generated report for the real
    // numbers behind it, not asserted blind. A row is flagged if its single most space-
    // hungry biome occupies at least this fraction of the row's own total width.
    public static final double RISKY_DOMINATION_FRACTION_THRESHOLD = 0.5;

    private FailureModeCharacterization() {
    }

    public static void main(String[] args) throws IOException {
        BiomeGraphData data = BiomeGraphJson.loadResource(RawRuleTable.class, "/biomegraph/biome-graph.json");
        RawRuleTable realTable = RawRuleTable.from(data);
        RawRuleTable brokenTable = dominantHubGraph();
        runCharacterization(
            realTable, brokenTable, REPORT_PATH, "production ModBiomeGraph.OVERWORLD (src/main/resources/biomegraph/biome-graph.json)",
            "synthetic dominant-hub graph (deliberately pathological, see this class's own doc)"
        );
    }

    /**
     * Deliberately pathological "sure to fail" graph - star topology, one hub legal to 4
     * leaves, leaves not legal to each other, hub's own declared range set VASTLY wider than
     * the leaves' (5000-5100 vs 100-450). Real, empirically confirmed (2026-09), not just
     * argued: {@code PeriodicCoherenceProof.findWitness} against this exact graph NEVER
     * finds a witness (0/600 attempts across all 3 tried row counts) - every single attempt
     * builds row 0 fine and then dies trying to find ANY legal row 1, every time.
     */
    static RawRuleTable dominantHubGraph() {
        BiomeId hub = new BiomeId("synthetic", "dominant_hub");
        List<BiomeId> leaves = List.of(
            new BiomeId("synthetic", "leaf_a"), new BiomeId("synthetic", "leaf_b"),
            new BiomeId("synthetic", "leaf_c"), new BiomeId("synthetic", "leaf_d")
        );
        List<NodeData> nodes = new ArrayList<>();
        nodes.add(new NodeData(hub.namespace(), hub.path(), 5000, 5100, Integer.MAX_VALUE, List.of()));
        int rangeStart = 100;
        for (BiomeId leaf : leaves) {
            nodes.add(new NodeData(leaf.namespace(), leaf.path(), rangeStart, rangeStart + 50, Integer.MAX_VALUE, List.of()));
            rangeStart += 100;
        }
        List<EdgeData> edges = new ArrayList<>();
        for (BiomeId leaf : leaves) {
            edges.add(new EdgeData(hub.namespace(), hub.path(), leaf.namespace(), leaf.path(), 10));
        }
        return RawRuleTable.from(new BiomeGraphData(nodes, edges));
    }

    /** How space-hungry a row's single most dominant biome is, and how many legal partners that biome has. */
    public record Domination(BiomeId dominantBiome, double fraction, int degree) {
    }

    /** The actual cheap screen - real O(cells) arithmetic, no search. */
    public static boolean flagsAsRisky(Row row) {
        return dominationOf(row).fraction() >= RISKY_DOMINATION_FRACTION_THRESHOLD;
    }

    public static Domination dominationOf(Row row) {
        Map<BiomeId, Double> widthByBiome = new HashMap<>();
        double total = row.totalWidthBlocks();
        for (Cell cell : row.cells()) {
            widthByBiome.merge(cell.biomeId(), cell.widthBlocks(), Double::sum);
        }
        BiomeId dominant = null;
        double maxFraction = 0;
        for (Map.Entry<BiomeId, Double> entry : widthByBiome.entrySet()) {
            double fraction = entry.getValue() / total;
            if (fraction > maxFraction) {
                maxFraction = fraction;
                dominant = entry.getKey();
            }
        }
        return new Domination(dominant, maxFraction, 0);
    }

    private static Domination dominationWithDegree(Row row, Map<BiomeId, Integer> degreeById) {
        Domination raw = dominationOf(row);
        int degree = raw.dominantBiome() == null ? 0 : degreeById.getOrDefault(raw.dominantBiome(), 0);
        return new Domination(raw.dominantBiome(), raw.fraction(), degree);
    }

    public record RowSample(Row row, boolean successorFound, Domination domination) {
    }

    static List<RowSample> collectSamples(RawRuleTable table, Random random) {
        double rowHeightBlocks = PeriodicCoherenceProof.defaultRowHeightBlocks(table);
        Map<BiomeId, Integer> degreeById = ReachabilityScoutingChecks.computeDegrees(table);
        List<RowSample> samples = new ArrayList<>();
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            Row anchor = PeriodicCoherenceProof.tryBuildAnchorRow(
                table, new HashSet<>(), MIN_CELLS_PER_ROW, MAX_CELLS_PER_ROW, rowHeightBlocks, random, ANCHOR_BUILD_RETRIES
            );
            if (anchor == null) {
                continue;
            }
            double circumferenceBlocks = anchor.totalWidthBlocks();
            Row successor = PeriodicCoherenceProof.tryBuildLegalRow(
                table, new HashSet<>(), anchor, null, MIN_CELLS_PER_ROW, MAX_CELLS_PER_ROW, rowHeightBlocks, circumferenceBlocks, random,
                SUCCESSOR_SEARCH_RETRIES
            );
            samples.add(new RowSample(anchor, successor != null, dominationWithDegree(anchor, degreeById)));
        }
        return samples;
    }

    public static void runCharacterization(
        RawRuleTable realTable, RawRuleTable brokenTable, Path reportPath, String realLabel, String brokenLabel
    ) throws IOException {
        Random random = new Random(RANDOM_SEED);
        List<RowSample> realSamples = collectSamples(realTable, random);
        List<RowSample> brokenSamples = collectSamples(brokenTable, random);

        writeReport(realSamples, brokenSamples, reportPath, realLabel, brokenLabel);

        long realFailures = realSamples.stream().filter(s -> !s.successorFound()).count();
        long brokenFailures = brokenSamples.stream().filter(s -> !s.successorFound()).count();
        System.out.println(realLabel + ": " + realFailures + "/" + realSamples.size() + " sampled rows found NO successor.");
        System.out.println(brokenLabel + ": " + brokenFailures + "/" + brokenSamples.size() + " sampled rows found NO successor.");
        System.out.println("Report link: " + reportPath.toAbsolutePath().toUri());
    }

    private static void writeReport(
        List<RowSample> realSamples, List<RowSample> brokenSamples, Path reportPath, String realLabel, String brokenLabel
    ) throws IOException {
        StringBuilder report = new StringBuilder();
        report.append("FAILURE-MODE CHARACTERIZATION - mined screen, validated against a known-bad graph\n");
        report.append("====================================================================================\n\n");
        report.append("Mined pattern: a row's single most space-hungry biome (\"domination fraction\" = that\n");
        report.append("biome's own total width / the row's total width). Threshold: ").append(RISKY_DOMINATION_FRACTION_THRESHOLD).append("\n\n");

        appendTableSection(report, realLabel, realSamples);
        appendTableSection(report, brokenLabel, brokenSamples);

        report.append("--- Screen precision/recall on this real data (not asserted blind) ---\n");
        appendPrecisionRecall(report, "real catalog", realSamples);
        appendPrecisionRecall(report, "broken graph", brokenSamples);
        report.append("\nHonest limit: this is a correlate mined from these two tables' own samples, not a\n");
        report.append("proof - same discipline as BridgeMotifChecker. A row it flags should still be verified\n");
        report.append("against a real search before being treated as definitely doomed.\n");

        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, report.toString(), StandardCharsets.UTF_8);
    }

    private static void appendTableSection(StringBuilder report, String label, List<RowSample> samples) {
        report.append("--- ").append(label).append(" (").append(samples.size()).append(" rows sampled) ---\n");
        for (RowSample sample : samples) {
            report.append("  successor=").append(sample.successorFound() ? "FOUND   " : "NOT FOUND").append(" dominationFraction=")
                .append(String.format("%.3f", sample.domination().fraction())).append(" dominantBiome=").append(sample.domination().dominantBiome())
                .append(" (degree ").append(sample.domination().degree()).append(")\n");
        }
        report.append("\n");
    }

    private static void appendPrecisionRecall(StringBuilder report, String label, List<RowSample> samples) {
        long truePositives = samples.stream().filter(s -> !s.successorFound() && s.domination().fraction() >= RISKY_DOMINATION_FRACTION_THRESHOLD).count();
        long falsePositives = samples.stream().filter(s -> s.successorFound() && s.domination().fraction() >= RISKY_DOMINATION_FRACTION_THRESHOLD).count();
        long falseNegatives = samples.stream().filter(s -> !s.successorFound() && s.domination().fraction() < RISKY_DOMINATION_FRACTION_THRESHOLD).count();
        long actualFailures = samples.stream().filter(s -> !s.successorFound()).count();
        long flagged = samples.stream().filter(s -> s.domination().fraction() >= RISKY_DOMINATION_FRACTION_THRESHOLD).count();
        report.append(label).append(": ").append(actualFailures).append(" real failures, ").append(flagged).append(" flagged as risky - ")
            .append("truePositives=").append(truePositives).append(" falsePositives=").append(falsePositives)
            .append(" falseNegatives=").append(falseNegatives).append("\n");
    }
}
