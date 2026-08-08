package com.mineutze.worldgen.regionsolve.mesh;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;
import com.mineutze.worldgen.biomegraph.GrowthCohesionProfile;
import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mineutze.worldgen.regionsolve.WorldgenTrace;

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
 */
public final class MeshGapFiller {

    // Same technique as GrowthEngine/GapFiller's own frontier jitter — deterministic
    // per (worldSeed, face, biome), so competing patches at equal distance resolve
    // reproducibly instead of by insertion order.
    private static final double JITTER_MAGNITUDE = 12.0;
    private static final long JITTER_SALT = 0x6A9F_11E4L;

    // Same role/values as GapFiller.MAX_PATCH_DROP_RETRIES / MAX_REASSIGN_DEPTH -
    // bounds the search, not a correctness mechanism (resolveRemaining's force mode
    // behind it is the unconditional guarantee).
    private static final int MAX_PATCH_DROP_RETRIES = 3;
    private static final int MAX_REASSIGN_DEPTH = 2;

    private MeshGapFiller() {
    }

    private record FillCandidate(int faceId, BiomeId biome, int distance) {
    }

    public static void fillGaps(FineMesh mesh, MeshAssignment assignment, BiomeGraph graph, MacroGridConfig grid, long worldSeed) {
        int patchDropAttempt = 0;

        while (true) {
            runFillPass(mesh, assignment.faceIdentity(), graph, worldSeed);

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

    private static void runFillPass(FineMesh mesh, Map<Integer, BiomeId> faceIdentity, BiomeGraph graph, long worldSeed) {
        PriorityQueue<FillCandidate> frontier = new PriorityQueue<>(candidateOrder(worldSeed));

        for (Map.Entry<Integer, BiomeId> entry : faceIdentity.entrySet()) {
            addUnclaimedNeighbors(mesh, entry.getKey(), entry.getValue(), 1, faceIdentity, frontier);
        }

        while (!frontier.isEmpty()) {
            FillCandidate candidate = frontier.poll();
            int faceId = candidate.faceId();
            if (faceIdentity.containsKey(faceId)) {
                continue; // already claimed by a competing biome that reached it first
            }

            GrowthCohesionProfile cohesion = graph.node(candidate.biome()).cohesionProfile();
            boolean stillLegal = MeshLegality.isLegal(mesh, faceId, candidate.biome(), faceIdentity, graph);
            boolean stillCohesive = satisfiesCohesionForGapFill(mesh, faceId, candidate.biome(), faceIdentity, cohesion);
            if (!stillLegal || !stillCohesive) {
                continue; // stale by the time it was popped - some other source may still legally reach it later
            }

            faceIdentity.put(faceId, candidate.biome());
            WorldgenTrace.log("MESH GAPFILL claim face " + faceId + " = " + candidate.biome() + " (distance=" + candidate.distance() + ")");
            addUnclaimedNeighbors(mesh, faceId, candidate.biome(), candidate.distance() + 1, faceIdentity, frontier);
        }
    }

    /** Gap-fill extends already-established patches, never has GrowthEngine's own seed-bootstrap problem, so the real already-claimed count (not a hardcoded exemption) is what MeshCohesion should see. */
    private static boolean satisfiesCohesionForGapFill(FineMesh mesh, int faceId, BiomeId biome, Map<Integer, BiomeId> faceIdentity, GrowthCohesionProfile cohesion) {
        long alreadyClaimedFaces = faceIdentity.values().stream().filter(biome::equals).count();
        return MeshCohesion.satisfies(mesh, faceId, biome, faceIdentity, cohesion, (int) alreadyClaimedFaces);
    }

    private static void addUnclaimedNeighbors(FineMesh mesh, int faceId, BiomeId biome, int distance, Map<Integer, BiomeId> faceIdentity, PriorityQueue<FillCandidate> frontier) {
        for (int neighborFaceId : mesh.edgeAdjacentFaces(faceId)) {
            if (!faceIdentity.containsKey(neighborFaceId)) {
                frontier.add(new FillCandidate(neighborFaceId, biome, distance));
            }
        }
    }

    private static Comparator<FillCandidate> candidateOrder(long worldSeed) {
        return Comparator.<FillCandidate>comparingDouble(candidate -> candidate.distance() + jitter(worldSeed, candidate.faceId(), candidate.biome()))
            .thenComparingInt(FillCandidate::faceId)
            .thenComparing(candidate -> candidate.biome().toString());
    }

    private static double jitter(long worldSeed, int faceId, BiomeId biome) {
        long hash = worldSeed ^ JITTER_SALT;
        hash = hash * 31 + faceId;
        hash = hash * 31 + biome.toString().hashCode();
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
