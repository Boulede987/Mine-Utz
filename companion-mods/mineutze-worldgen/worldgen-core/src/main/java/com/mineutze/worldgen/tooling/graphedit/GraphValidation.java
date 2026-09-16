package com.mineutze.worldgen.tooling.graphedit;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.EdgeData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.NodeData;
import com.mineutze.worldgen.biomegraph.BiomeGraphJson;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.export.BridgeMotifChecker;

import java.util.ArrayList;
import java.util.List;

/**
 * Live warnings for the graph editor - the whole reason this tool exists rather than
 * staying an Excel sheet. Reuses the REAL checking code this session already built and
 * measured, never a second copy of the rules: {@link BiomeGraph}'s own constructor
 * (bridgeability etc. - whatever it enforces IS the ground truth, this class doesn't need
 * to know every rule by name), {@link BiomeGraphJson#toBiomeGraph} for the node/rule
 * conversion (shared with {@code ModBiomeGraph.OVERWORLD}'s own production loader), and
 * {@link BridgeMotifChecker}'s two arithmetic motif screens (Rule 1/2 from this session's
 * own graph audit).
 */
final class GraphValidation {

    // Mirrors BiomeGraph.MIN_LEGAL_NEIGHBORS (private there) - the real threshold
    // BiomeGraph.forProduction's requireMinLegalNeighbors() enforces for any shippable
    // catalog. Reported here as a real ERROR (see underConnectedErrors) using the SAME
    // threshold forProduction itself would reject on - NOT by actually calling
    // forProduction as the hard SAVE-blocking gate, though: requireMinLegalNeighbors
    // throws on the FIRST violation, so a single call can't surface every under-connected
    // biome at once, AND blocking the save would break this tool's own incremental "add
    // one adjacency at a time" premise (a brand new biome's first adjacency would get
    // rejected for having only 1 neighbor, losing the edit). If BiomeGraph's own constant
    // ever changes, update this one too - it's a threshold, not rule logic, the one class
    // of number this codebase's own discipline accepts mirroring with a doc pointer.
    private static final int MIN_LEGAL_NEIGHBORS = 3;

    private GraphValidation() {
    }

    /**
     * Real user finding (2026-09-15): under-connection was originally folded into {@code
     * motifWarnings}, and the user correctly pushed back - "I believe they should [be
     * errors]." Unlike the two {@link BridgeMotifChecker} motifs (real but explicitly
     * NOT proofs - "verify with the real solver before trusting"), under-connection is a
     * direct mirror of a check {@link BiomeGraph#forProduction} genuinely, unconditionally
     * enforces - production WOULD reject this graph. That's a real error, not a soft
     * correlate, even though (see {@link #MIN_LEGAL_NEIGHBORS}'s own doc) it doesn't block
     * saving mid-edit the way {@code constructs=false} does.
     *
     * @param constructs        whether {@link BiomeGraph}'s own bare constructor accepted
     *                          this data as-is - {@code false} means a genuinely malformed
     *                          reference (e.g. a rule naming an unknown biome), blocks
     *                          saving outright.
     * @param constructionError {@link BiomeGraph}'s own real exception message when {@code
     *                          constructs} is false, {@code null} otherwise.
     * @param errors             real violations of a check production WOULD enforce
     *                          (today: under-connection) - never blocks saving (see this
     *                          record's own doc for why), but a real problem, not a
     *                          suggestion. Empty when {@code constructs} is false.
     * @param motifWarnings     soft warnings - real, data-validated correlates of geometric
     *                          infeasibility (see {@link BridgeMotifChecker}'s own doc:
     *                          "verify with the real solver before trusting," not a proof).
     *                          Does NOT include Motif 3 (Crowded-Ring) or anything from
     *                          {@code GeometricFeasibilityProof}/{@code
     *                          DeadEndFrequencySimulation} - those are real but too slow
     *                          for live editing, on-demand only (see {@code
     *                          GraphEditorServer}'s "Deep check" button). Empty when
     *                          {@code constructs} is false.
     */
    record ValidationResult(boolean constructs, String constructionError, List<String> errors, List<String> motifWarnings) {
    }

    static ValidationResult validate(BiomeGraphData data) {
        BiomeGraph graph;
        try {
            graph = BiomeGraphJson.toBiomeGraph(data);
        } catch (IllegalArgumentException e) {
            return new ValidationResult(false, e.getMessage(), List.of(), List.of());
        }
        return new ValidationResult(true, null, underConnectedErrors(graph, data), motifWarnings(graph, data));
    }

    /**
     * Real user finding (2026-09-15): "I have a few cells without any links, so it should
     * tell me there is a problem. But it passes." - a zero/under-connected biome is
     * structurally valid to {@link BiomeGraph}'s bare constructor, so nothing caught it.
     * This is the check that would: {@link BiomeGraph#requireMinLegalNeighbors}, run here
     * as a real error (see {@link ValidationResult}'s own doc for why it's an error but
     * still doesn't block saving).
     */
    private static List<String> underConnectedErrors(BiomeGraph graph, BiomeGraphData data) {
        List<String> errors = new ArrayList<>();
        List<BiomeId> allBiomes = data.nodes().stream().map(node -> new BiomeId(node.namespace(), node.path())).toList();
        for (BiomeId id : allBiomes) {
            long legalNeighbors = allBiomes.stream().filter(other -> !other.equals(id) && graph.isAllowed(id, other)).count();
            if (legalNeighbors < MIN_LEGAL_NEIGHBORS) {
                errors.add(id + ": only " + legalNeighbors + " legal neighbor(s), fewer than the " + MIN_LEGAL_NEIGHBORS
                    + " a real/production graph requires (BiomeGraph.forProduction would reject this) - add more adjacencies.");
            }
        }
        return errors;
    }

    private static List<String> motifWarnings(BiomeGraph graph, BiomeGraphData data) {
        List<String> warnings = new ArrayList<>();
        for (NodeData node : data.nodes()) {
            BiomeId id = new BiomeId(node.namespace(), node.path());
            List<BiomeId> neighbors = neighborsOf(id, data);
            if (neighbors.isEmpty()) {
                continue;
            }
            if (BridgeMotifChecker.triggersDominantNeighborMotif(graph, neighbors)) {
                warnings.add(id + ": Motif 1 (Dominant Neighbor) - a real neighbor's own declared minimum already exceeds the "
                    + "650-block baseline, which can squeeze this biome's achievable size regardless of every other neighbor. "
                    + "Verify with the real solver before trusting.");
            }
            if (BridgeMotifChecker.triggersFragileNarrowTierMotif(graph, id, neighbors)) {
                warnings.add(id + ": Motif 2 (Fragile Narrow Tier) - this biome's own range sits entirely below the 650-block "
                    + "baseline with 2+ real neighbors present, at compounding risk even though no single neighbor dominates. "
                    + "Verify with the real solver before trusting.");
            }
        }
        return warnings;
    }

    private static List<BiomeId> neighborsOf(BiomeId id, BiomeGraphData data) {
        String key = id.namespace() + ":" + id.path();
        List<BiomeId> neighbors = new ArrayList<>();
        for (EdgeData edge : data.edges()) {
            if (edge.aKey().equals(key)) {
                neighbors.add(new BiomeId(edge.bNamespace(), edge.bPath()));
            } else if (edge.bKey().equals(key)) {
                neighbors.add(new BiomeId(edge.aNamespace(), edge.aPath()));
            }
        }
        return neighbors;
    }
}
