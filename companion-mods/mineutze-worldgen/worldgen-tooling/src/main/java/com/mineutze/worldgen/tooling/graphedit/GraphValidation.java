package com.mineutze.worldgen.tooling.graphedit;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphJson;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.plan.RawRuleTable;
import com.mineutze.worldgen.plan.WorldBiomePlan;

import java.util.ArrayList;
import java.util.List;

/**
 * Live warnings for the graph editor - the whole reason this tool exists rather than
 * staying an Excel sheet. Reuses the REAL checking code this session already built and
 * measured, never a second copy of the rules: {@link BiomeGraph}'s own constructor
 * (bridgeability etc. - whatever it enforces IS the ground truth, this class doesn't need
 * to know every rule by name) and {@link BiomeGraphJson#toBiomeGraph} for the node/rule
 * conversion (shared with {@code ModBiomeGraph.OVERWORLD}'s own production loader).
 * Whether the graph can actually be SOLVED is not checked live - that takes minutes and is
 * the editor's "Solve check" button ({@link GraphEditorSolveCheck}).
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
     * Real user finding (2026-09-15): under-connection is an error, not a soft warning - it is a
     * direct mirror of a check {@link BiomeGraph#forProduction} genuinely, unconditionally enforces,
     * so production WOULD reject this graph. It still doesn't block saving mid-edit the way
     * {@code constructs=false} does (see {@link #MIN_LEGAL_NEIGHBORS}'s own doc).
     *
     * @param constructs        whether {@link BiomeGraph}'s own bare constructor accepted
     *                          this data as-is and every group and requirement in it can be
     *                          read and points at something that exists - {@code false} means a
     *                          genuinely malformed reference (e.g. a rule naming an unknown
     *                          biome, a requirement naming a missing group), blocks saving outright.
     * @param constructionError {@link BiomeGraph}'s own real exception message when {@code
     *                          constructs} is false, {@code null} otherwise.
     * @param errors            real violations of a check production WOULD enforce
     *                          (today: under-connection) - never blocks saving, but a real
     *                          problem, not a suggestion. Empty when {@code constructs} is false.
     */
    record ValidationResult(boolean constructs, String constructionError, List<String> errors) {
    }

    static ValidationResult validate(BiomeGraphData data) {
        BiomeGraph graph;
        try {
            graph = BiomeGraphJson.toBiomeGraph(data);
        } catch (IllegalArgumentException e) {
            return new ValidationResult(false, e.getMessage(), List.of());
        }
        try {
            RawRuleTable.from(data);
        } catch (IllegalArgumentException unreadable) {
            return new ValidationResult(false, unreadable.getMessage(), List.of());
        }
        List<String> errors = underConnectedErrors(graph, data);
        errors.addAll(generatorRejections(data));
        return new ValidationResult(true, null, errors);
    }

    /** What the world generator itself says about the rules (a size range with no whole cell, a border requirement nothing could meet): what it would throw at world creation, shown live instead. */
    private static List<String> generatorRejections(BiomeGraphData data) {
        try {
            WorldBiomePlan.requireCatalogAcceptable(data);
            return List.of();
        } catch (IllegalArgumentException rejection) {
            return List.of("The world generator would reject this graph: " + rejection.getMessage());
        }
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
            checkConnectivity(graph, allBiomes, errors, id);
        }
        return errors;
    }

    private static void checkConnectivity(BiomeGraph graph, List<BiomeId> allBiomes, List<String> errors, BiomeId id) {
        long legalNeighbors = allBiomes.stream().filter(other -> !other.equals(id) && graph.isAllowed(id, other)).count();
        if (legalNeighbors < MIN_LEGAL_NEIGHBORS) {
            errors.add(id + ": only " + legalNeighbors + " legal neighbor(s), fewer than the " + MIN_LEGAL_NEIGHBORS
                + " a real/production graph requires (BiomeGraph.forProduction would reject this) - add more adjacencies.");
        }
    }
}
