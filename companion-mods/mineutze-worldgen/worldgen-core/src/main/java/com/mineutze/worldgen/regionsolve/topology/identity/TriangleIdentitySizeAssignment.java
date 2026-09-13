package com.mineutze.worldgen.regionsolve.topology.identity;

import com.mineutze.worldgen.regionsolve.topology.Point2D;

import com.mineutze.worldgen.regionsolve.topology.mesh.HexMeshBuilder;

import com.mineutze.worldgen.regionsolve.topology.placement.WeightedVertexPlacement;

import com.mineutze.worldgen.regionsolve.topology.mesh.FaceAdjacency;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;
import com.mineutze.worldgen.biomegraph.BiomeSizeRange;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Assigns each free triangle face (index into a {@link FaceAdjacency}) both a {@link
 * BiomeId} AND a target width in one pass, composing 4 narrower collaborators, each blind
 * to the others:
 * <ul>
 *     <li>{@link FaceIdentityCsp} - decides WHO (CSP identity search: MRV + backtracking +
 *     AC-3/ternary propagation), zero width concern.</li>
 *     <li>{@link AchievableWidthRange} - converts a committed neighbor's real geometry
 *     into a width bound a candidate must fit, zero identity/legality concern.</li>
 *     <li>{@link SelfAdjacentPairing} - decides which identity-decided faces share one
 *     felt region instead of drawing independently.</li>
 *     <li>{@link FaceWidthPicker} - draws the actual width once identity (and pairing)
 *     settled, zero identity/legality concern.</li>
 * </ul>
 * Deliberately no cross-neighbor size CONSTRAINT in {@link FaceIdentityCsp}: {@code
 * AdjacencyRule} has no size-coupling field anywhere - a biome pair is allowed or not,
 * independent of size - so once identity is legal, width is a free, deterministic pick
 * from that biome's {@link BiomeSizeRange} (intersected with {@link AchievableWidthRange}
 * if any). Real geometric size consistency (does this triangle get enough room given
 * neighbors' competing claims) is {@link WeightedVertexPlacement}'s job, not this class's.
 *
 * <p>No retry/reseed/node-drop ladder here on purpose — orchestration concern, belongs to
 * whatever calls this. This class just reports success/failure for the exact mesh given.
 */
public final class TriangleIdentitySizeAssignment {

    /**
     * @param legalMinWidthBlocks this face's absolute legal floor - {@code biome}'s
     *                            declared {@link BiomeSizeRange} directly for a singleton
     *                            face, or {@link FaceWidthPicker#pairedMemberLegalMinWidthBlocks}
     *                            for a self-adjacent pair member: the two members' widths
     *                            merely need to SUM into the biome's declared range (real
     *                            user decision, 2026-09-13 - an asymmetric (10, 1000) split
     *                            is legal for a (900,1500) range as long as 1010 fits),
     *                            not each independently occupy an equal share - checking a
     *                            member against the FULL combined range would still be
     *                            wrong, a real bug an earlier live-path legal-size test had,
     *                            caught by the user.
     * @param legalMaxWidthBlocks symmetric ceiling, same reasoning
     * @param pairedWithFaceIndex the OTHER face this one jointly forms one felt region with
     *                            (see {@link SelfAdjacentPairing}), or {@link #NOT_PAIRED}
     *                            for a singleton. Geometry (weight resolution) needs this to
     *                            treat a pair as ONE competitor against external neighbors
     *                            instead of two independent half-weighted ones - see
     *                            {@code CalibratedVertexPlacement}/{@code
     *                            SequentialVertexPlacement}'s own joint-weight-resolution
     *                            doc. Travels here (not re-derived from {@code
     *                            SelfAdjacentPairing} at each geometry call site) so the
     *                            pairing decision is made exactly once, by identity, and
     *                            geometry never needs a {@code BiomeGraph} at all.
     */
    public record Assignment(BiomeId biome, int widthBlocks, int legalMinWidthBlocks, int legalMaxWidthBlocks, int pairedWithFaceIndex) {

        /** Sentinel for {@link #pairedWithFaceIndex} meaning "no partner, a singleton face" - real face indices are always {@code >= 0}. */
        public static final int NOT_PAIRED = -1;

        /**
         * Convenience for callers that only care about geometry math against a synthetic
         * assignment (most of {@code WeightedVertexPlacementTest}), no real CSP-level
         * legal-range concern - defaults fully unconstrained ({@code Integer.MIN_VALUE}/
         * {@code MAX_VALUE}, "no additional bound" for {@code
         * WeightedVertexPlacement.legalAreaRangesOf}), matching this codebase's "narrow
         * convenience constructor for common case" convention (e.g. {@code BiomeNode}'s
         * 2-arg constructor defaulting {@code selfAdjacencyLimit}).
         */
        public Assignment(BiomeId biome, int widthBlocks) {
            this(biome, widthBlocks, Integer.MIN_VALUE, Integer.MAX_VALUE, NOT_PAIRED);
        }

        /** Convenience for a caller with a real legal range but no pairing concern (e.g. a hand-built singleton test case). */
        public Assignment(BiomeId biome, int widthBlocks, int legalMinWidthBlocks, int legalMaxWidthBlocks) {
            this(biome, widthBlocks, legalMinWidthBlocks, legalMaxWidthBlocks, NOT_PAIRED);
        }
    }

    private TriangleIdentitySizeAssignment() {
    }

    /**
     * @param adjacency        face-to-face neighbor structure ({@link FaceAdjacency}) —
     *                         actual triangle geometry never needed, only which faces
     *                         border which.
     * @param freeFaceIndices  faces this call assigns — never includes hull-touching faces
     *                         (see {@link FaceAdjacency}: those are missing real neighbor
     *                         context, not this class's problem to guess around).
     * @param fixedFaceBiomes  already-committed neighbor context (e.g. adjacent, already-
     *                         solved area) — read-only ground truth, never reassigned.
     *                         Widths for fixed faces are the caller's concern; this class
     *                         only needs their identity.
     * @return empty if no legal assignment exists for the given mesh/fixed context —
     *         {@code BiomeGraph.requireEveryLocalNeighborhoodSolvable} only rules out
     *         "impossible in principle" configs, not every real local arrangement.
     */
    public static Optional<Map<Integer, Assignment>> solve(
        FaceAdjacency.Adjacency adjacency, Set<Integer> freeFaceIndices,
        Map<Integer, BiomeId> fixedFaceBiomes, BiomeGraph graph, long worldSeed
    ) {
        return solve(adjacency, freeFaceIndices, fixedFaceBiomes, graph, worldSeed, Double.POSITIVE_INFINITY);
    }

    /**
     * Same as {@link #solve(FaceAdjacency.Adjacency, Set, Map, BiomeGraph, long)}, but also
     * caps how many triangles any ONE biome gets in this region — see {@link
     * FaceFairnessQuota} for why and how the cap is derived. Callers without region-area
     * awareness can keep using the simpler overload, which passes {@link
     * Double#POSITIVE_INFINITY} here (no cap, today's exact behavior).
     *
     * @param regionAreaBlocks2 region's area in blocks², used only to size the cap.
     */
    public static Optional<Map<Integer, Assignment>> solve(
        FaceAdjacency.Adjacency adjacency, Set<Integer> freeFaceIndices,
        Map<Integer, BiomeId> fixedFaceBiomes, BiomeGraph graph, long worldSeed, double regionAreaBlocks2
    ) {
        return solve(adjacency, freeFaceIndices, fixedFaceBiomes, graph, worldSeed, regionAreaBlocks2, Map.of());
    }

    /**
     * Same as {@link #solve(FaceAdjacency.Adjacency, Set, Map, BiomeGraph, long, double)},
     * but also lets a caller supply each committed neighbor's real WIDTH (not just
     * identity) — direct fix for a gap found exploring the live in-game path (2026-09-11):
     * {@code TriangleFieldGeometryRealization.realizeCluster} already reads a committed
     * neighbor's width from {@code DecidedFaceStore}, threads it into {@code
     * WeightedVertexPlacement}, but never passed it HERE - a fresh face's width was always
     * drawn blind from its biome's full catalog range, zero visibility into how much room
     * real locked-in neighbors could spare. See {@link FaceWidthPicker} for how this
     * changes the draw.
     *
     * @param committedWidthBlocksByFace a NEIGHBOR's committed width, keyed by neighbor's
     *                                   face index — read-only, same discipline as {@code
     *                                   fixedFaceBiomes}. Never has an entry for a face this
     *                                   call is assigning.
     */
    public static Optional<Map<Integer, Assignment>> solve(
        FaceAdjacency.Adjacency adjacency, Set<Integer> freeFaceIndices, Map<Integer, BiomeId> fixedFaceBiomes,
        BiomeGraph graph, long worldSeed, double regionAreaBlocks2, Map<Integer, Integer> committedWidthBlocksByFace
    ) {
        return solve(adjacency, freeFaceIndices, fixedFaceBiomes, graph, worldSeed, regionAreaBlocks2, committedWidthBlocksByFace, Map.of());
    }

    /**
     * Same as {@link #solve(FaceAdjacency.Adjacency, Set, Map, BiomeGraph, long, double, Map)},
     * but also computes each free face's REAL achievable size range from committed
     * neighbors BEFORE deciding what goes there ("compute achievable range first, decide
     * second"). {@code TriangleIdentitySizeAssignment} otherwise has zero geometry input -
     * this is the one, narrow crossing into {@link WeightedVertexPlacement}'s real geometry
     * math, via {@link WeightedVertexPlacement#achievableAreaRangeFor} rather than merging
     * the two classes.
     *
     * <p>A candidate biome whose ENTIRE {@link BiomeSizeRange} falls outside a face's
     * achievable range is HARD-EXCLUDED from its domain, same standing as a graph-illegal
     * candidate (deliberate, not soft preference - "no legal assignment" can now be caused
     * by SIZE alone, not just identity). Composes for free with existing AC-3/ternary
     * propagation and the two-attempt tentative-recovery strategy already in {@code
     * TriangleFieldGeometryRealization.realizeCluster} - attempt 2 has strictly fewer
     * truly-fixed neighbors than attempt 1, so a size dead end on attempt 1 gets a real
     * second chance with a WIDER achievable range on attempt 2, no new recovery mechanism.
     * Once identity is chosen, width is drawn from the INTERSECTION of the biome's catalog
     * range and achievable range, not raw catalog range alone.
     *
     * @param mesh                       the real mesh this solve's faces belong to - only
     *                                   used to compute achievable ranges, nothing else.
     */
    public static Optional<Map<Integer, Assignment>> solve(
        FaceAdjacency.Adjacency adjacency, Set<Integer> freeFaceIndices, Map<Integer, BiomeId> fixedFaceBiomes,
        BiomeGraph graph, long worldSeed, double regionAreaBlocks2, Map<Integer, Integer> committedWidthBlocksByFace,
        HexMeshBuilder.HexMesh mesh, double baselineCircumradiusBlocks, double minEdgeLengthBlocks
    ) {
        return solve(
            adjacency, freeFaceIndices, fixedFaceBiomes, graph, worldSeed, regionAreaBlocks2, committedWidthBlocksByFace,
            mesh, baselineCircumradiusBlocks, minEdgeLengthBlocks, Map.of()
        );
    }

    /**
     * Same as {@link #solve(FaceAdjacency.Adjacency, Set, Map, BiomeGraph, long, double, Map, HexMeshBuilder.HexMesh, double, double)},
     * but also lets a caller supply each committed vertex's real final position - a face
     * fully encased by already-decided neighbors has EVERY corner pre-locked by the time
     * its geometry gets computed, making the downstream realized-area clamp a no-op
     * (nothing left to shrink toward), so a biome that can't fit gets hard-excluded HERE,
     * at CSP time, not silently committing an illegal size. See DECISIONS.md 2026-09-11
     * "achievableAreaRangeFor now consults committed VERTEX positions."
     *
     * @param committedVertexPositions a vertex's currently-decided position, keyed by index
     *                                  into {@code mesh.vertices()} - tentative-or-permanent
     *                                  (mirrors {@code DecidedVertexStore#get}, same
     *                                  discipline {@code
     *                                  WeightedVertexPlacement#sequentiallyRealizedVertexPositions}
     *                                  applies to its own {@code committedVertexPositions}:
     *                                  whichever value is CURRENT is fixed context for faces
     *                                  this call decides, regardless of whether a later
     *                                  solve could still revise it).
     */
    public static Optional<Map<Integer, Assignment>> solve(
        FaceAdjacency.Adjacency adjacency, Set<Integer> freeFaceIndices, Map<Integer, BiomeId> fixedFaceBiomes,
        BiomeGraph graph, long worldSeed, double regionAreaBlocks2, Map<Integer, Integer> committedWidthBlocksByFace,
        HexMeshBuilder.HexMesh mesh, double baselineCircumradiusBlocks, double minEdgeLengthBlocks,
        Map<Integer, Point2D> committedVertexPositions
    ) {
        // Real catalog-wide bounds, not arbitrary extreme - see achievableAreaRangeFor for
        // why an astronomically large probe weight collapses to the same constant
        // regardless of real neighbor context (real bug found/fixed before this was
        // tested). Every biome here is a real candidate somewhere, so catalog-wide min/max
        // width is the meaningful probe range - "smallest to largest real biome size, what
        // could it get given real neighbors."
        int probeMinWidthBlocks = graph.nodes().stream().mapToInt(node -> node.sizeRange().minWidthBlocks()).min().orElseThrow();
        int probeMaxWidthBlocks = graph.nodes().stream().mapToInt(node -> node.sizeRange().maxWidthBlocks()).max().orElseThrow();

        Map<Integer, AchievableWidthRange> achievableRangeByFace = new HashMap<>();
        for (int faceIndex : freeFaceIndices) {
            boolean hasCommittedNeighbor = adjacency.neighborFaceIndices().getOrDefault(faceIndex, Set.of()).stream()
                .anyMatch(committedWidthBlocksByFace::containsKey);
            if (!hasCommittedNeighbor) {
                continue;
            }
            WeightedVertexPlacement.AreaRange areaRange = WeightedVertexPlacement.achievableAreaRangeFor(
                mesh, adjacency, faceIndex, committedWidthBlocksByFace, committedVertexPositions, baselineCircumradiusBlocks, minEdgeLengthBlocks,
                probeMinWidthBlocks, probeMaxWidthBlocks
            );
            achievableRangeByFace.put(faceIndex, AchievableWidthRange.from(areaRange));
        }
        return solve(adjacency, freeFaceIndices, fixedFaceBiomes, graph, worldSeed, regionAreaBlocks2, committedWidthBlocksByFace, achievableRangeByFace);
    }

    private static Optional<Map<Integer, Assignment>> solve(
        FaceAdjacency.Adjacency adjacency, Set<Integer> freeFaceIndices, Map<Integer, BiomeId> fixedFaceBiomes,
        BiomeGraph graph, long worldSeed, double regionAreaBlocks2, Map<Integer, Integer> committedWidthBlocksByFace,
        Map<Integer, AchievableWidthRange> achievableRangeByFace
    ) {
        // .sorted(), not just .toList() - BiomeGraph.nodes() comes from a Map.copyOf-wrapped
        // map, and JDK deliberately randomizes Map.copyOf's iteration order PER JVM RUN (to
        // catch code assuming it's stable). Shuffling an already-JVM-random start with an
        // otherwise-correctly-seeded Random still gives a JVM-run-dependent result, since
        // Fisher-Yates output depends on input arrangement, not just seed.
        List<BiomeId> allBiomes = graph.nodes().stream().map(BiomeNode::id).sorted().toList();
        Map<Integer, List<BiomeId>> domains = initialDomains(adjacency, freeFaceIndices, fixedFaceBiomes, graph, allBiomes, achievableRangeByFace);
        FaceFairnessQuota fairness = FaceFairnessQuota.forRegion(allBiomes, freeFaceIndices.size(), regionAreaBlocks2);

        Optional<Map<Integer, BiomeId>> assigned = FaceIdentityCsp.solve(domains, adjacency, freeFaceIndices, graph, worldSeed, fairness);
        if (assigned.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(buildResult(assigned.get(), adjacency, graph, worldSeed, committedWidthBlocksByFace, achievableRangeByFace));
    }

    /** Each free face's own candidate biome domain, hard-excluding anything illegal against a fixed neighbor OR outside the face's own real achievable range (see the mesh-aware {@code solve} overload's own doc for why size can exclude a candidate here too, not just adjacency). */
    private static Map<Integer, List<BiomeId>> initialDomains(
        FaceAdjacency.Adjacency adjacency, Set<Integer> freeFaceIndices, Map<Integer, BiomeId> fixedFaceBiomes,
        BiomeGraph graph, List<BiomeId> allBiomes, Map<Integer, AchievableWidthRange> achievableRangeByFace
    ) {
        Map<Integer, List<BiomeId>> domains = new HashMap<>();
        for (int faceIndex : freeFaceIndices) {
            AchievableWidthRange achievable = achievableRangeByFace.get(faceIndex);
            List<BiomeId> domain = new ArrayList<>(allBiomes);
            domain.removeIf(candidate -> !FaceIdentityCsp.legalAgainstFixedNeighbors(faceIndex, candidate, adjacency, fixedFaceBiomes, graph)
                || (achievable != null && !achievable.fits(graph.node(candidate).sizeRange())));
            domains.put(faceIndex, domain);
        }
        return domains;
    }

    /** Once identity is fully assigned, draws each face's own width (pair-split where applicable) and its resulting absolute legal range - see {@link Assignment}'s own doc for what {@code legalMinWidthBlocks}/{@code legalMaxWidthBlocks} mean for a paired vs. singleton face. */
    private static Map<Integer, Assignment> buildResult(
        Map<Integer, BiomeId> assigned, FaceAdjacency.Adjacency adjacency, BiomeGraph graph, long worldSeed,
        Map<Integer, Integer> committedWidthBlocksByFace, Map<Integer, AchievableWidthRange> achievableRangeByFace
    ) {
        Map<Integer, Integer> pairPartnerByFace = SelfAdjacentPairing.pairedFaces(assigned, adjacency, graph);
        Map<Integer, Assignment> result = new LinkedHashMap<>();
        assigned.forEach((faceIndex, biome) -> {
            Integer partner = pairPartnerByFace.get(faceIndex);
            BiomeSizeRange fullRange = graph.node(biome).sizeRange();
            AchievableWidthRange achievable = achievableRangeByFace.get(faceIndex);
            BiomeSizeRange effectiveRange = achievable == null ? fullRange : achievable.intersect(fullRange);
            int widthBlocks = partner != null
                ? FaceWidthPicker.splitWidthForPair(
                    fullRange, worldSeed, faceIndex, partner, biome, adjacency, committedWidthBlocksByFace,
                    achievable, achievableRangeByFace.get(partner))
                : FaceWidthPicker.pickWidth(effectiveRange, worldSeed, faceIndex, biome, adjacency, committedWidthBlocksByFace);
            int legalMinWidthBlocks = partner != null
                ? FaceWidthPicker.pairedMemberLegalMinWidthBlocks(fullRange.minWidthBlocks(), fullRange.maxWidthBlocks())
                : fullRange.minWidthBlocks();
            int legalMaxWidthBlocks = partner != null
                ? FaceWidthPicker.pairedMemberLegalMaxWidthBlocks(fullRange.maxWidthBlocks())
                : fullRange.maxWidthBlocks();
            int pairedWithFaceIndex = partner != null ? partner : Assignment.NOT_PAIRED;
            result.put(faceIndex, new Assignment(biome, widthBlocks, legalMinWidthBlocks, legalMaxWidthBlocks, pairedWithFaceIndex));
        });
        return result;
    }
}
