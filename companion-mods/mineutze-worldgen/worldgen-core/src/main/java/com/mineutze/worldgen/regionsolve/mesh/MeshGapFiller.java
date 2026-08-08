package com.mineutze.worldgen.regionsolve.mesh;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;
import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mineutze.worldgen.regionsolve.WorldgenTrace;
import com.mineutze.worldgen.regionsolve.topology.Point2D;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Direct mesh-native port of {@code GapFiller}'s exact 3-tier structure —
 * see that class's doc for the full rationale, unchanged here: multi-source
 * BFS first (cheap, no side effects when it works), then bounded neighbor
 * reassignment for a dead-end face, then dropping the smallest directly-
 * blocking patch, then an unconditional force-commit as the last resort
 * that can never fail to terminate. The only structural difference from
 * the pixel version is what counts as "blocking" a face: {@link
 * MeshLegality} checks both edge-adjacent AND diagonal-only (point-touch)
 * neighbors, so every tier here walks {@link #blockingCandidateFaces} —
 * the union of both — instead of just orthogonal neighbors.
 *
 * <p>Never reassigns or drops a face in {@code fixedFaceIds} — those are
 * already-solved neighbor-region context, not this solve's to revise, the
 * mesh analog of the pixel version's "never touches a cell outside
 * regionCellSet."
 *
 * <p><b>Unlike the pixel {@code GapFiller}, this class does NOT apply
 * {@link MeshCohesion}'s neck-prevention during fill — only {@link
 * MeshLegality}.</b> Tried it first (biome's own profile, no bootstrap
 * exemption, matching the pixel version's own "gap-fill extends
 * already-established patches" reasoning) and it measurably starved
 * sparse-germ biomes on seed 42: a biome with only 3 germs got literally
 * ZERO additional territory from a full fill pass once its growth-phase
 * patches stalled, while denser biomes (13-14 germs) kept growing
 * substantially through the same fill. Root cause: gap-fill's frontier
 * seeds from EVERY already-assigned face of a biome simultaneously
 * (possibly several separate, disconnected clusters from growth), so a
 * fresh gap-fill candidate typically has only 1 same-identity edge-neighbor
 * — whichever face it's extending from — even when it's a completely
 * ordinary, non-pathological extension, not a real neck. That's
 * structurally different from {@link PatchMerger}'s serial single-patch
 * growth, where the same 2-of-3 threshold is a correct, proven
 * neck-blocker (see {@link MeshCohesion}'s own doc, including a reverted
 * attempt to fix this by counting diagonal partners too — that nearly
 * disabled neck-prevention mesh-wide instead). {@link MeshLegality} (fully
 * enforced here, unchanged) plus gap-fill's inherently fair multi-source
 * nearest-wins competition are enough protection against pathological
 * flooding on their own — the same real-world lesson the pixel
 * {@code GapFiller} already demonstrates from the other direction: its own
 * cohesion check is lenient enough (2 of 8 Moore-neighbors) that it barely
 * constrains ordinary fills either, and the one case it actually needs to
 * catch (a solid patch flooding through a literal 1-cell gap in a wall) is
 * still caught by legality and the natural shape of a multi-source BFS,
 * not by strictness.
 *
 * <p><b>The fill pass itself is target-aware, not a single shared
 * nearest-wins queue.</b> An earlier version pooled every biome's frontier
 * into one {@code PriorityQueue} ordered purely by distance — found (via
 * direct per-biome-area measurement at every pipeline stage, seed 42) to
 * systematically favor whichever biome already had more/bigger claimed
 * territory: a restrictively-legal biome (few compatible neighbors, so a
 * narrow frontier) lost the competition for contested cells to a
 * broadly-legal, numerous-germ biome even when the restricted biome was
 * still far below its own target and the dominant one was already near or
 * past its own. Each biome now gets its OWN frontier queue; every claim
 * step picks whichever biome (with a non-empty frontier) has the largest
 * remaining gap to its own aggregate target area, then claims its single
 * best (distance+jitter) candidate — the exact same "furthest below target
 * goes first" discipline {@link PatchMerger#merge} already uses one
 * layer up, just extended into gap-fill instead of stopping at growth.
 */
public final class MeshGapFiller {

    // Same technique and same value as the pixel GapFiller's own frontier jitter —
    // deterministic per (worldSeed, face), so competing candidates within the same
    // biome's own frontier at equal distance resolve reproducibly instead of by
    // insertion order. Now that distance is real geometric distance (see
    // FillCandidate's own doc) rather than a hop count, this is in the same
    // macro-cell-unit scale as a real single hop through this mesh's
    // ~4-macro-cell-wide triangles (FineMeshPointScatter.FINE_MESH_SPACING_BLOCKS)
    // — re-tune if that spacing constant changes significantly.
    private static final double JITTER_MAGNITUDE = 12.0;
    private static final long JITTER_SALT = 0x6A9F_11E4L;

    // Same role/values as GapFiller.MAX_PATCH_DROP_RETRIES / MAX_REASSIGN_DEPTH -
    // bounds the search, not a correctness mechanism (resolveRemaining's force mode
    // behind it is the unconditional guarantee).
    private static final int MAX_PATCH_DROP_RETRIES = 3;
    private static final int MAX_REASSIGN_DEPTH = 2;

    private MeshGapFiller() {
    }

    /**
     * {@code distance} is real accumulated centroid-to-centroid geometric
     * distance (macro-cell units), not a flat hop count — {@code PatchMerger}'s
     * own frontier already orders by real distance from the germ, and a
     * hop count would systematically favor whichever biome has more/smaller
     * already-claimed faces nearby on this mesh's non-uniform triangle
     * sizes (unlike the pixel grid, where a hop IS a fixed-size macro-cell
     * step and the two metrics coincide). No {@code biome} field — each
     * instance only ever lives in that one biome's own frontier queue, so
     * it's implied by which queue it's in, not carried per-candidate.
     */
    private record FillCandidate(int faceId, double distance) {
    }

    /** Convenience overload: nominal "1 unit of demand per biome present in the graph" target, for callers with no real per-germ target info (most tests). The real production call site ({@code MeshGeometryRealization}) uses the 6-arg overload with each biome's actual aggregate germ-target area. */
    public static void fillGaps(FineMesh mesh, MeshAssignment assignment, BiomeGraph graph, MacroGridConfig grid, long worldSeed) {
        fillGaps(mesh, assignment, graph, grid, worldSeed, nominalTargetAreaByBiome(graph, grid));
    }

    private static Map<BiomeId, Double> nominalTargetAreaByBiome(BiomeGraph graph, MacroGridConfig grid) {
        Map<BiomeId, Double> targets = new LinkedHashMap<>();
        for (BiomeNode node : graph.nodes()) {
            double sideInCells = (double) node.sizeRange().maxWidthBlocks() / grid.macroCellSizeBlocks();
            targets.put(node.id(), sideInCells * sideInCells);
        }
        return targets;
    }

    /** @param targetAreaByBiome each biome's aggregate target area (macro-cell² units, sum across all its germs) — a biome absent from this map is treated as having no further demand (target 0), so it's never preferred over one that's actually short of its own target. */
    public static void fillGaps(FineMesh mesh, MeshAssignment assignment, BiomeGraph graph, MacroGridConfig grid, long worldSeed, Map<BiomeId, Double> targetAreaByBiome) {
        int patchDropAttempt = 0;

        while (true) {
            runFillPass(mesh, assignment.faceIdentity(), graph, worldSeed, targetAreaByBiome);

            try {
                resolveRemaining(mesh, assignment, grid, graph, false);
                return;
            } catch (DeadEndException deadEnd) {
                if (patchDropAttempt < MAX_PATCH_DROP_RETRIES && dropSmallestBlockingPatch(mesh, deadEnd.faceId, assignment)) {
                    patchDropAttempt++;
                    continue;
                }
                resolveRemaining(mesh, assignment, grid, graph, true);
                return;
            }
        }
    }

    private static void runFillPass(FineMesh mesh, Map<Integer, BiomeId> faceIdentity, BiomeGraph graph, long worldSeed, Map<BiomeId, Double> targetAreaByBiome) {
        Map<BiomeId, PriorityQueue<FillCandidate>> frontierByBiome = new LinkedHashMap<>();
        Map<BiomeId, Double> claimedAreaByBiome = new LinkedHashMap<>();

        for (Map.Entry<Integer, BiomeId> entry : faceIdentity.entrySet()) {
            BiomeId biome = entry.getValue();
            claimedAreaByBiome.merge(biome, MeshAreas.area(mesh, mesh.face(entry.getKey())), Double::sum);
            addUnclaimedNeighbors(mesh, entry.getKey(), 0.0, faceIdentity, frontierFor(frontierByBiome, biome, worldSeed));
        }

        while (true) {
            Optional<BiomeId> mostBehind = mostBehindTarget(frontierByBiome, claimedAreaByBiome, targetAreaByBiome);
            if (mostBehind.isEmpty()) {
                return;
            }
            BiomeId biome = mostBehind.get();
            claimNextValidFrontierFace(mesh, biome, frontierByBiome.get(biome), faceIdentity, graph, claimedAreaByBiome);
        }
    }

    private static PriorityQueue<FillCandidate> frontierFor(Map<BiomeId, PriorityQueue<FillCandidate>> frontierByBiome, BiomeId biome, long worldSeed) {
        return frontierByBiome.computeIfAbsent(biome, key -> new PriorityQueue<>(candidateOrder(worldSeed)));
    }

    /** Whichever biome (with at least one still-claimable frontier candidate) has the largest gap to its own target — same "furthest below target" tie-break idea PatchMerger uses, natural BiomeId order as the deterministic fallback for an exact tie. */
    private static Optional<BiomeId> mostBehindTarget(Map<BiomeId, PriorityQueue<FillCandidate>> frontierByBiome, Map<BiomeId, Double> claimedAreaByBiome, Map<BiomeId, Double> targetAreaByBiome) {
        return frontierByBiome.entrySet().stream()
            .filter(entry -> !entry.getValue().isEmpty())
            .max(Comparator.<Map.Entry<BiomeId, PriorityQueue<FillCandidate>>>comparingDouble(
                    entry -> remainingGap(entry.getKey(), claimedAreaByBiome, targetAreaByBiome))
                .thenComparing(entry -> entry.getKey().toString()))
            .map(Map.Entry::getKey);
    }

    private static double remainingGap(BiomeId biome, Map<BiomeId, Double> claimedAreaByBiome, Map<BiomeId, Double> targetAreaByBiome) {
        return targetAreaByBiome.getOrDefault(biome, 0.0) - claimedAreaByBiome.getOrDefault(biome, 0.0);
    }

    /**
     * Drains {@code frontier} (that one biome's own queue) until a still-
     * legal, still-unclaimed candidate is found and claimed, or the queue
     * runs dry — mirrors {@code PatchMerger}'s own "recheck fresh, a stale
     * entry just gets skipped, not treated as a stall" discipline: an entry
     * valid when pushed can go stale by the time it's popped, since a
     * competing biome may have claimed it (or a neighbor that made it
     * illegal) in the meantime.
     */
    private static void claimNextValidFrontierFace(
        FineMesh mesh, BiomeId biome, PriorityQueue<FillCandidate> frontier, Map<Integer, BiomeId> faceIdentity, BiomeGraph graph, Map<BiomeId, Double> claimedAreaByBiome
    ) {
        while (!frontier.isEmpty()) {
            FillCandidate candidate = frontier.poll();
            int faceId = candidate.faceId();
            if (faceIdentity.containsKey(faceId) || !MeshLegality.isLegal(mesh, faceId, biome, faceIdentity, graph)) {
                continue;
            }
            faceIdentity.put(faceId, biome);
            claimedAreaByBiome.merge(biome, MeshAreas.area(mesh, mesh.face(faceId)), Double::sum);
            WorldgenTrace.log("MESH GAPFILL claim face " + faceId + " = " + biome + " (distance=" + candidate.distance() + ")");
            addUnclaimedNeighbors(mesh, faceId, candidate.distance(), faceIdentity, frontier);
            return;
        }
    }

    private static void addUnclaimedNeighbors(FineMesh mesh, int faceId, double distance, Map<Integer, BiomeId> faceIdentity, PriorityQueue<FillCandidate> frontier) {
        Point2D fromCentroid = MeshAreas.centroid(mesh, mesh.face(faceId));
        for (int neighborFaceId : mesh.edgeAdjacentFaces(faceId)) {
            if (!faceIdentity.containsKey(neighborFaceId)) {
                Point2D toCentroid = MeshAreas.centroid(mesh, mesh.face(neighborFaceId));
                double dx = toCentroid.x() - fromCentroid.x();
                double dz = toCentroid.z() - fromCentroid.z();
                double hopDistance = Math.sqrt(dx * dx + dz * dz);
                frontier.add(new FillCandidate(neighborFaceId, distance + hopDistance));
            }
        }
    }

    private static Comparator<FillCandidate> candidateOrder(long worldSeed) {
        return Comparator.<FillCandidate>comparingDouble(candidate -> candidate.distance() + jitter(worldSeed, candidate.faceId()))
            .thenComparingInt(FillCandidate::faceId);
    }

    private static double jitter(long worldSeed, int faceId) {
        long hash = worldSeed ^ JITTER_SALT;
        hash = hash * 31 + faceId;
        return new Random(hash).nextDouble() * JITTER_MAGNITUDE;
    }

    /** Every face that could possibly make {@code faceId} illegal per {@link MeshLegality} — edge-adjacent AND diagonal-only partners alike. */
    private static Set<Integer> blockingCandidateFaces(FineMesh mesh, int faceId) {
        Set<Integer> candidates = new LinkedHashSet<>(mesh.edgeAdjacentFaces(faceId));
        candidates.addAll(MeshDiagonalPairs.diagonalPartnersOf(mesh, faceId));
        return candidates;
    }

    private static boolean dropSmallestBlockingPatch(FineMesh mesh, int deadEndFaceId, MeshAssignment assignment) {
        Set<Integer> smallestDroppable = null;
        for (int neighborFaceId : blockingCandidateFaces(mesh, deadEndFaceId)) {
            if (assignment.fixedFaceIds().contains(neighborFaceId) || assignment.faceIdentity().get(neighborFaceId) == null) {
                continue;
            }
            Set<Integer> component = componentContaining(mesh, neighborFaceId, assignment.faceIdentity(), assignment.fixedFaceIds());
            if (smallestDroppable == null || component.size() < smallestDroppable.size()) {
                smallestDroppable = component;
            }
        }

        if (smallestDroppable == null) {
            return false;
        }
        smallestDroppable.forEach(assignment.faceIdentity()::remove);
        return true;
    }

    /** Same-identity flood fill via edge-adjacency only, stopping at (and never including) any fixed face. */
    private static Set<Integer> componentContaining(FineMesh mesh, int start, Map<Integer, BiomeId> faceIdentity, Set<Integer> fixedFaceIds) {
        return componentContaining(mesh, start, faceIdentity, fixedFaceIds, -1);
    }

    private static Set<Integer> componentContaining(FineMesh mesh, int start, Map<Integer, BiomeId> faceIdentity, Set<Integer> fixedFaceIds, int excludedFaceId) {
        BiomeId biome = faceIdentity.get(start);
        Set<Integer> visited = new LinkedHashSet<>();
        Deque<Integer> stack = new ArrayDeque<>(List.of(start));
        visited.add(start);
        while (!stack.isEmpty()) {
            int current = stack.pop();
            for (int neighbor : mesh.edgeAdjacentFaces(current)) {
                if (neighbor != excludedFaceId && !fixedFaceIds.contains(neighbor) && biome.equals(faceIdentity.get(neighbor)) && visited.add(neighbor)) {
                    stack.push(neighbor);
                }
            }
        }
        return visited;
    }

    private static boolean stealingPreservesDonorConnectivity(FineMesh mesh, int donorFaceId, Map<Integer, BiomeId> faceIdentity, Set<Integer> fixedFaceIds) {
        Set<Integer> wholeComponent = componentContaining(mesh, donorFaceId, faceIdentity, fixedFaceIds);
        Set<Integer> remaining = new LinkedHashSet<>(wholeComponent);
        remaining.remove(donorFaceId);
        if (remaining.isEmpty()) {
            return true; // donor was the entire component - nothing left to disconnect
        }
        int anyRemaining = remaining.iterator().next();
        Set<Integer> reachableWithoutDonor = componentContaining(mesh, anyRemaining, faceIdentity, fixedFaceIds, donorFaceId);
        return reachableWithoutDonor.containsAll(remaining);
    }

    private static double donorAreaWithout(FineMesh mesh, int donorFaceId, Map<Integer, BiomeId> faceIdentity, Set<Integer> fixedFaceIds) {
        Set<Integer> wholeComponent = componentContaining(mesh, donorFaceId, faceIdentity, fixedFaceIds);
        double totalArea = wholeComponent.stream().mapToDouble(faceId -> MeshAreas.area(mesh, mesh.face(faceId))).sum();
        return totalArea - MeshAreas.area(mesh, mesh.face(donorFaceId));
    }

    private static double minAreaFor(BiomeId biome, MacroGridConfig grid, BiomeGraph graph) {
        double sideInCells = (double) graph.node(biome).sizeRange().minWidthBlocks() / grid.macroCellSizeBlocks();
        return sideInCells * sideInCells;
    }

    private static final class DeadEndException extends RuntimeException {
        final int faceId;

        DeadEndException(int faceId, String message) {
            super(message);
            this.faceId = faceId;
        }
    }

    private static void resolveRemaining(FineMesh mesh, MeshAssignment assignment, MacroGridConfig grid, BiomeGraph graph, boolean force) {
        List<Integer> remaining = new ArrayList<>();
        for (int faceId = 0; faceId < mesh.faces().size(); faceId++) {
            if (!assignment.faceIdentity().containsKey(faceId)) {
                remaining.add(faceId);
            }
        }

        for (int faceId : remaining) {
            BiomeId chosen = bestLegalChoice(mesh, faceId, assignment, grid, graph, force);
            assignment.faceIdentity().put(faceId, chosen);
            WorldgenTrace.log("MESH RESOLVEREMAINING claim face " + faceId + " = " + chosen + " (force=" + force + ")");
        }
    }

    private static BiomeId bestLegalChoice(FineMesh mesh, int faceId, MeshAssignment assignment, MacroGridConfig grid, BiomeGraph graph, boolean force) {
        List<BiomeId> legalCandidates = legalCandidatesAt(mesh, faceId, assignment.faceIdentity(), graph);

        if (legalCandidates.isEmpty()) {
            List<BiomeId> allBiomeIds = graph.nodes().stream().map(BiomeNode::id).sorted().toList();
            for (BiomeId candidate : allBiomeIds) {
                if (makeLegalHere(mesh, faceId, candidate, assignment, new HashSet<>(), 0, grid, graph)) {
                    legalCandidates = List.of(candidate);
                    break;
                }
            }
        }

        Map<BiomeId, Long> neighborCounts = mesh.edgeAdjacentFaces(faceId).stream()
            .map(assignment.faceIdentity()::get)
            .filter(Objects::nonNull)
            .collect(Collectors.groupingBy(biome -> biome, Collectors.counting()));

        if (legalCandidates.isEmpty()) {
            if (force) {
                return graph.nodes().stream()
                    .map(BiomeNode::id)
                    .max(Comparator.<BiomeId>comparingLong(id -> neighborCounts.getOrDefault(id, 0L)).thenComparing(Comparator.naturalOrder()))
                    .orElseThrow();
            }
            throw new DeadEndException(faceId,
                "MeshGapFiller: no biome in the graph is legal at face " + faceId + " given its neighbors " + neighborCounts
                    + " - the adjacency graph is over-constrained (e.g. mutually-incompatible biomes meeting at a "
                    + "point with nothing compatible with all of them), and no neighbor could be legally reassigned "
                    + "to fix that either.");
        }

        return legalCandidates.stream()
            .max(Comparator.<BiomeId>comparingLong(id -> neighborCounts.getOrDefault(id, 0L)).thenComparing(Comparator.naturalOrder()))
            .orElseThrow();
    }

    private static List<BiomeId> legalCandidatesAt(FineMesh mesh, int faceId, Map<Integer, BiomeId> faceIdentity, BiomeGraph graph) {
        return graph.nodes().stream()
            .map(BiomeNode::id)
            .filter(candidate -> MeshLegality.isLegal(mesh, faceId, candidate, faceIdentity, graph))
            .toList();
    }

    private static boolean makeLegalHere(
        FineMesh mesh, int position, BiomeId desiredBiome, MeshAssignment assignment, Set<Integer> beingReassigned, int depth, MacroGridConfig grid, BiomeGraph graph
    ) {
        Map<Integer, BiomeId> faceIdentity = assignment.faceIdentity();
        Set<Integer> fixedFaceIds = assignment.fixedFaceIds();

        if (MeshLegality.isLegal(mesh, position, desiredBiome, faceIdentity, graph)) {
            return true;
        }
        if (depth >= MAX_REASSIGN_DEPTH) {
            return false;
        }

        Map<Integer, BiomeId> fixedThisCall = new LinkedHashMap<>();
        for (int neighbor : blockingCandidateFaces(mesh, position)) {
            if (MeshLegality.isLegal(mesh, position, desiredBiome, faceIdentity, graph)) {
                break; // an earlier neighbor fixed within this same call already unblocked position
            }
            if (fixedFaceIds.contains(neighbor) || beingReassigned.contains(neighbor)) {
                continue;
            }
            BiomeId blocking = faceIdentity.get(neighbor);
            if (blocking == null || graph.isAllowed(desiredBiome, blocking)) {
                continue; // not assigned, or not actually blocking desiredBiome
            }
            if (!stealingPreservesDonorConnectivity(mesh, neighbor, faceIdentity, fixedFaceIds)) {
                continue;
            }
            double minArea = minAreaFor(blocking, grid, graph);
            if (donorAreaWithout(mesh, neighbor, faceIdentity, fixedFaceIds) < minArea) {
                continue;
            }

            for (BiomeId alternative : graph.nodes().stream().map(BiomeNode::id).sorted().toList()) {
                if (alternative.equals(blocking) || !graph.isAllowed(desiredBiome, alternative)) {
                    continue; // must actually unblock desiredBiome
                }

                faceIdentity.put(neighbor, alternative);
                beingReassigned.add(neighbor);
                if (makeLegalHere(mesh, neighbor, alternative, assignment, beingReassigned, depth + 1, grid, graph)) {
                    fixedThisCall.put(neighbor, blocking);
                    break;
                }
                faceIdentity.put(neighbor, blocking);
                beingReassigned.remove(neighbor);
            }
        }

        if (MeshLegality.isLegal(mesh, position, desiredBiome, faceIdentity, graph)) {
            return true;
        }

        // Partial fix isn't a fix - undo every reassignment made across ALL neighbors touched in this call.
        for (Map.Entry<Integer, BiomeId> fixed : fixedThisCall.entrySet()) {
            faceIdentity.put(fixed.getKey(), fixed.getValue());
            beingReassigned.remove(fixed.getKey());
        }
        return false;
    }
}
