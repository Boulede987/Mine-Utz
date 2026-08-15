package com.mineutze.worldgen.regionsolve.mesh;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;
import com.mineutze.worldgen.biomegraph.GrowthCohesionProfile;
import com.mineutze.worldgen.regionsolve.MacroCellPos;
import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mineutze.worldgen.regionsolve.RegionSolveInput;
import com.mineutze.worldgen.regionsolve.topology.Point2D;
import com.mineutze.worldgen.regionsolve.topology.SeedGraph;
import com.mineutze.worldgen.regionsolve.topology.SeedGraphNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

/**
 * Priority-interleaved flood-fill over the mesh: atomic unit = triangle
 * face instead of macro-cell, sizing target = cumulative area instead of
 * cell count (see {@link MeshAreas}'s doc for why no unit conversion is
 * needed between the two).
 *
 * <p>Every {@link SeedGraphNode} — fixed boundary context and CSP-assigned
 * free seeds alike — has a matching pre-colored germ vertex in the mesh
 * (see {@code FineMeshPointScatter}). A fixed germ's biome is committed
 * directly to one of its incident faces with no legality check (it's
 * already-solved ground truth from a neighbor region, not something this
 * solve can veto) — that single face is enough for every OTHER face
 * touching the same vertex to see it as context too, since any two faces
 * sharing a vertex are, by construction, either edge-adjacent to each
 * other or {@link MeshDiagonalPairs} partners of each other, and
 * {@link MeshLegality} checks both. A free germ instead nominates one
 * incident face as its patch's starting point (first legal, unclaimed
 * candidate touching it — {@link MeshGapFiller} reclaims it if none is
 * found) and grows from there via an interleaved-by-furthest-below-target
 * discipline, reusing {@link MeshLegality} and {@link MeshCohesion} at
 * every claim instead of inventing new checks.
 */
public final class PatchMerger {

    private static final double FRONTIER_JITTER_MAGNITUDE = 12.0; // same scale idea as GrowthEngine's own jitter, macro-cell² area units
    private static final long FRONTIER_JITTER_SALT = 0x9A1C_4E77L;

    private PatchMerger() {
    }

    public static MeshAssignment merge(FineMesh mesh, RegionSolveInput input, SeedGraph seedGraph, Map<Integer, BiomeId> assignedIdentities) {
        Map<Integer, BiomeId> faceIdentity = new LinkedHashMap<>();
        Map<Point2D, Integer> vertexIdByPosition = vertexIdByPosition(mesh);

        Set<Integer> fixedFaceIds = seedFixedGerms(mesh, seedGraph, vertexIdByPosition, faceIdentity);
        List<PatchGrowthState> patches = seedFreePatches(mesh, seedGraph, assignedIdentities, input, faceIdentity, vertexIdByPosition);
        growPatchesToTarget(mesh, patches, faceIdentity, input.graph());

        return new MeshAssignment(faceIdentity, fixedFaceIds);
    }

    private static Map<Point2D, Integer> vertexIdByPosition(FineMesh mesh) {
        Map<Point2D, Integer> byPosition = new LinkedHashMap<>();
        List<ScatteredPoint> points = mesh.points();
        for (int vertexId = 0; vertexId < points.size(); vertexId++) {
            byPosition.putIfAbsent(points.get(vertexId).position(), vertexId);
        }
        return byPosition;
    }

    private static Set<Integer> seedFixedGerms(FineMesh mesh, SeedGraph seedGraph, Map<Point2D, Integer> vertexIdByPosition, Map<Integer, BiomeId> faceIdentity) {
        Set<Integer> fixedFaceIds = new LinkedHashSet<>();
        for (SeedGraphNode node : seedGraph.nodes()) {
            if (!node.fixed()) {
                continue;
            }
            int vertexId = vertexIdByPosition.get(toPoint(node.position()));
            for (int faceId : mesh.incidentFaces(vertexId)) {
                if (!faceIdentity.containsKey(faceId)) {
                    faceIdentity.put(faceId, node.fixedBiome());
                    fixedFaceIds.add(faceId);
                    break;
                }
            }
        }
        return fixedFaceIds;
    }

    private static List<PatchGrowthState> seedFreePatches(
        FineMesh mesh, SeedGraph seedGraph, Map<Integer, BiomeId> assignedIdentities, RegionSolveInput input,
        Map<Integer, BiomeId> faceIdentity, Map<Point2D, Integer> vertexIdByPosition
    ) {
        List<PatchGrowthState> patches = new ArrayList<>();
        for (SeedGraphNode node : seedGraph.nodes()) {
            if (node.fixed()) {
                continue;
            }
            BiomeId biome = requireAssignment(assignedIdentities, node);
            int germVertexId = vertexIdByPosition.get(toPoint(node.position()));

            Optional<Integer> seedFace = firstLegalUnclaimedIncidentFace(mesh, germVertexId, biome, faceIdentity, input.graph());
            if (seedFace.isEmpty()) {
                continue; // MeshGapFiller's sweep reclaims this territory from a neighbor instead
            }

            BiomeNode biomeNode = input.graph().node(biome);
            PatchGrowthState patch = new PatchGrowthState(germVertexId, biome, targetArea(biomeNode, input.gridConfig()), biomeNode.cohesionProfile(), input.worldSeed(), mesh, mesh.position(germVertexId));

            faceIdentity.put(seedFace.get(), biome);
            patch.claimedFaceCount = 1;
            patch.claimedArea = MeshAreas.area(mesh, mesh.face(seedFace.get()));
            pushLegalFrontierNeighbors(mesh, patch, seedFace.get(), faceIdentity, input.graph());
            patches.add(patch);
        }
        return patches;
    }

    private static Optional<Integer> firstLegalUnclaimedIncidentFace(FineMesh mesh, int vertexId, BiomeId biome, Map<Integer, BiomeId> faceIdentity, BiomeGraph graph) {
        for (int faceId : mesh.incidentFaces(vertexId)) {
            if (!faceIdentity.containsKey(faceId) && MeshLegality.isLegal(mesh, faceId, biome, faceIdentity, graph)) {
                return Optional.of(faceId);
            }
        }
        return Optional.empty();
    }

    private static BiomeId requireAssignment(Map<Integer, BiomeId> assignedIdentities, SeedGraphNode node) {
        BiomeId biome = assignedIdentities.get(node.id());
        if (biome == null) {
            throw new IllegalArgumentException("No CSP assignment for free SeedGraphNode " + node.id());
        }
        return biome;
    }

    /** Direct area target from the size range, deliberately not MacroGridConfig.cellCountForWidthBlocks's rounded integer helper — a continuous area bound needs the unrounded value. */
    private static double targetArea(BiomeNode biomeNode, MacroGridConfig grid) {
        double sideInCells = (double) biomeNode.sizeRange().maxWidthBlocks() / grid.macroCellSizeBlocks();
        return sideInCells * sideInCells;
    }

    private static void growPatchesToTarget(FineMesh mesh, List<PatchGrowthState> patches, Map<Integer, BiomeId> faceIdentity, BiomeGraph graph) {
        while (true) {
            Optional<PatchGrowthState> next = patches.stream()
                .filter(patch -> patch.belowTarget() && patch.hasClaimableFrontier())
                .max(patchPriorityOrder());
            if (next.isEmpty()) {
                return;
            }
            claimNextValidFrontierFace(mesh, next.get(), faceIdentity, graph);
        }
    }

    /** Furthest below target goes first; ties break on germ vertex id for determinism. */
    private static Comparator<PatchGrowthState> patchPriorityOrder() {
        return Comparator.<PatchGrowthState>comparingDouble(PatchGrowthState::remainingGap)
            .thenComparingInt(patch -> patch.germVertexId);
    }

    private static void claimNextValidFrontierFace(FineMesh mesh, PatchGrowthState patch, Map<Integer, BiomeId> faceIdentity, BiomeGraph graph) {
        while (!patch.frontier.isEmpty()) {
            int faceId = patch.frontier.pollFirst();
            if (faceIdentity.containsKey(faceId)
                || !MeshLegality.isLegal(mesh, faceId, patch.biome, faceIdentity, graph)
                || !MeshCohesion.satisfies(mesh, faceId, patch.biome, faceIdentity, patch.cohesion, patch.claimedFaceCount)) {
                continue;
            }
            faceIdentity.put(faceId, patch.biome);
            patch.claimedFaceCount++;
            patch.claimedArea += MeshAreas.area(mesh, mesh.face(faceId));
            pushLegalFrontierNeighbors(mesh, patch, faceId, faceIdentity, graph);
            return;
        }
    }

    private static void pushLegalFrontierNeighbors(FineMesh mesh, PatchGrowthState patch, int faceId, Map<Integer, BiomeId> faceIdentity, BiomeGraph graph) {
        for (int neighborFaceId : mesh.edgeAdjacentFaces(faceId)) {
            if (!faceIdentity.containsKey(neighborFaceId)
                && MeshLegality.isLegal(mesh, neighborFaceId, patch.biome, faceIdentity, graph)
                && MeshCohesion.satisfies(mesh, neighborFaceId, patch.biome, faceIdentity, patch.cohesion, patch.claimedFaceCount)) {
                patch.frontier.add(neighborFaceId);
            }
        }
    }

    private static Point2D toPoint(MacroCellPos pos) {
        return new Point2D(pos.x(), pos.z());
    }

    private static final class PatchGrowthState {
        final int germVertexId;
        final BiomeId biome;
        final double targetArea;
        final GrowthCohesionProfile cohesion;
        final TreeSet<Integer> frontier;
        int claimedFaceCount = 0;
        double claimedArea = 0.0;

        PatchGrowthState(int germVertexId, BiomeId biome, double targetArea, GrowthCohesionProfile cohesion, long worldSeed, FineMesh mesh, Point2D germPosition) {
            this.germVertexId = germVertexId;
            this.biome = biome;
            this.targetArea = targetArea;
            this.cohesion = cohesion;
            this.frontier = new TreeSet<>(frontierOrder(mesh, germPosition, worldSeed, germVertexId));
        }

        boolean belowTarget() {
            return claimedArea < targetArea;
        }

        boolean hasClaimableFrontier() {
            return !frontier.isEmpty();
        }

        double remainingGap() {
            return targetArea - claimedArea;
        }
    }

    private static Comparator<Integer> frontierOrder(FineMesh mesh, Point2D germPosition, long worldSeed, int germVertexId) {
        return Comparator.<Integer>comparingDouble(faceId -> distanceSquared(MeshAreas.centroid(mesh, mesh.face(faceId)), germPosition) + jitter(worldSeed, germVertexId, faceId))
            .thenComparingInt(Integer::intValue);
    }

    private static double distanceSquared(Point2D a, Point2D b) {
        double dx = a.x() - b.x();
        double dz = a.z() - b.z();
        return dx * dx + dz * dz;
    }

    /** Deterministic: same (worldSeed, germVertexId, faceId) always jitters the same way, so growth stays reproducible. */
    private static double jitter(long worldSeed, int germVertexId, int faceId) {
        long hash = worldSeed ^ FRONTIER_JITTER_SALT;
        hash = hash * 31 + germVertexId;
        hash = hash * 31 + faceId;
        return new Random(hash).nextDouble() * FRONTIER_JITTER_MAGNITUDE;
    }
}
