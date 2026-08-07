package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.regionsolve.topology.CspIdentityAssignment;
import com.mineutze.worldgen.regionsolve.topology.SeedGraph;
import com.mineutze.worldgen.regionsolve.topology.SeedGraphBuilder;
import com.mineutze.worldgen.regionsolve.topology.SeedGraphNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Orchestrates the topology-first region solve: build an abstract seed
 * graph, color it via CSP, only then realize geometry. See
 * doc/Modelisation/ActivityDiagram/topology-first-region-solve.puml. Wired in
 * as {@link SeedRegionCache}'s solve path, replacing {@link RegionSolver}
 * (kept in the codebase, unused by the live path, as a rollback option until
 * this pipeline has real in-game verification behind it).
 *
 * <p>The failure ladder is a genuine termination PROOF, not a tuned cap: a
 * graph that fails to color gets a bounded number of reseed attempts (cheap —
 * pure abstract-graph regeneration, no geometry exists yet to undo), then the
 * single most-constrained free node gets dropped from consideration entirely
 * and the reseed budget resets. Dropping a node only ever REMOVES a
 * constraint, so free-node count strictly decreases with every drop; at zero
 * free nodes, CSP succeeds vacuously (nothing left to color), unconditionally
 * — so this loop cannot fail to terminate in success. An earlier draft of
 * this ladder stopped dropping at 1 remaining node and had a "fail loud, the
 * boundary must be corrupt" branch past that — wrong: a lone free node can
 * still be pinned between two already-fixed neighbors whose legal sets don't
 * overlap, and dropping OTHER nodes never fixes that. Correct fix was to drop
 * that node too, not to crash — its territory doesn't vanish, {@link
 * GeometryRealization} plus {@link GapFiller}'s own already-proven,
 * always-terminating pixel-level guarantee covers whatever's left, exactly
 * like the pre-redesign pipeline already does. No fail-loud branch belongs
 * here; there was never a real case this ladder couldn't resolve.
 */
public final class TopologyFirstRegionSolver {

    // Placeholder, tune like every other cap in this codebase (BudgetPlanner's
    // TARGET_PACKING_FRACTION, GapFiller's MAX_PATCH_DROP_RETRIES, ...) once real
    // solve output exists to tune it from. Purely a "how much does a nicer reseed
    // get tried before falling to the tier below it" knob — node-drop alone
    // already guarantees termination on its own, see class doc.
    private static final int MAX_RESEED_ATTEMPTS_PER_DROP_LEVEL = 10;
    private static final int DEFAULT_SEED_SPACING_BLOCKS = 400;

    private TopologyFirstRegionSolver() {
    }

    public static RegionSolveResult solve(RegionSolveInput input) {
        return solve(input, DEFAULT_SEED_SPACING_BLOCKS);
    }

    public static RegionSolveResult solve(RegionSolveInput input, int seedSpacingBlocks) {
        Set<MacroCellPos> excludedSeedPositions = new HashSet<>();
        List<String> warnings = new ArrayList<>();
        int reseedAttempt = 0;

        while (true) {
            SeedGraph seedGraph = SeedGraphBuilder.build(input, seedSpacingBlocks, reseedAttempt, excludedSeedPositions);

            if (!seedGraph.reseedRecommended()) {
                Optional<Map<Integer, BiomeId>> assignment = CspIdentityAssignment.solve(seedGraph, input.graph(), input.worldSeed());
                if (assignment.isPresent()) {
                    Map<MacroCellPos, BiomeId> realized = GeometryRealization.realize(seedGraph, assignment.get(), input);
                    return new RegionSolveResult(realized, List.copyOf(warnings), excludedSeedPositions.size());
                }
            }

            if (reseedAttempt < MAX_RESEED_ATTEMPTS_PER_DROP_LEVEL) {
                reseedAttempt++;
                continue;
            }

            MacroCellPos dropped = mostConstrainedFreeNodePosition(seedGraph).orElseThrow(() -> new IllegalStateException(
                "TopologyFirstRegionSolver: reached node-drop with zero free nodes in the graph, but a graph with "
                    + "zero free nodes must already have succeeded via vacuous CSP success (nothing left to color) - "
                    + "reaching this point means that proof is wrong. That's a real bug in this class, not a "
                    + "legitimately unsolvable region."
            ));
            excludedSeedPositions.add(dropped);
            warnings.add(
                "Dropped seed node at " + dropped + " after " + (MAX_RESEED_ATTEMPTS_PER_DROP_LEVEL + 1)
                    + " reseed attempts couldn't color it — its territory falls to GeometryRealization/GapFiller instead."
            );
            reseedAttempt = 0;
        }
    }

    /** "Most constrained" = smallest remaining domain — includes an already-empty domain, the exact node that would have triggered reseedRecommended. Ties break on lowest node id for determinism. */
    private static Optional<MacroCellPos> mostConstrainedFreeNodePosition(SeedGraph seedGraph) {
        return seedGraph.nodes().stream()
            .filter(node -> !node.fixed())
            .min(Comparator.<SeedGraphNode>comparingInt(node -> node.domain().size()).thenComparingInt(SeedGraphNode::id))
            .map(SeedGraphNode::position);
    }
}
