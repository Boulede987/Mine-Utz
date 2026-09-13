package com.mineutze.worldgen.export.hex;

import com.mineutze.worldgen.regionsolve.topology.mesh.HexGrid;

import com.mineutze.worldgen.regionsolve.topology.mesh.HexMeshBuilder;

import com.mineutze.worldgen.regionsolve.topology.mesh.VertexKey;

import com.mineutze.worldgen.regionsolve.topology.mesh.FaceAdjacency;

import com.mineutze.worldgen.regionsolve.livepath.DecidedFaceStore;

import com.mineutze.worldgen.regionsolve.livepath.DecidedVertexStore;

import com.mineutze.worldgen.regionsolve.livepath.SeedMeshCache;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.ModBiomeGraph;
import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mineutze.worldgen.regionsolve.topology.Point2D;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Real, live-PATH size-fidelity measurement - honest counterpart to {@link
 * SizeFidelityMeasurement}, which measures a code path ({@code
 * TriangleFieldGeometryRealization.realize}, one whole-mesh CSP + calibration pass) that
 * is DEAD in production (found via direct exploration, not assumed). Nothing in shipped
 * mod calls it - every real {@code /locate biome} query goes through {@link
 * SeedMeshCache} -> {@code TriangleFieldGeometryRealization.realizeCluster}, an
 * organically-grown, incrementally-committed cluster solve using already-decided
 * (tentative or permanent) neighbors as FIXED context via {@link DecidedFaceStore}/
 * {@link DecidedVertexStore}.
 *
 * <p>This tool drives the REAL {@link SeedMeshCache} with a grid of queries (same public
 * entry point Minecraft calls), measures each queried face's realized-vs-target area
 * ratio directly from the real, committed {@link DecidedFaceStore}/{@link
 * DecidedVertexStore} state, not a fresh unconstrained solve. Same {@code ±10%} metric,
 * same per-biome/worst-10 report shape as {@link SizeFidelityMeasurement} for legible
 * before/after comparisons, but the NUMBER may legitimately differ since the two exercise
 * different code paths.
 */
public final class SizeFidelityLivePathMeasurement {

    // Matches TriangleFieldGeometryRealization.HEX_SIZE_BLOCKS - kept in sync manually,
    // same convention as SizeFidelityMeasurement/DeadEndFrequencySimulation's local copies
    // (constant is private, this class stays a pure outside caller).
    private static final double HEX_SIZE_BLOCKS = 325;
    private static final MacroGridConfig GRID_CONFIG = MacroGridConfig.DEFAULT;
    private static final int SEED_COUNT = 10;
    private static final long SEED_BASE = 2026L;
    // Bounded, modest area per seed - real cluster solves (up to 2500-block first-touch
    // growth cap each) cost more than the whole-mesh tool's one big batch, so starts smaller
    // than SizeFidelityMeasurement's 3000-block half-width - widen once real runtime known.
    private static final double AREA_HALF_WIDTH_BLOCKS = 1500;
    // Dense enough that most hexes across the swept area get queried directly, not just
    // touched as incidental cluster context.
    private static final double QUERY_SPACING_BLOCKS = HEX_SIZE_BLOCKS * 0.9;
    // Matches TriangleFieldGeometryRealization.FAST_PATH_LOOKUP_PAD_BLOCKS - generous enough
    // to contain one face's own 6-neighbor ring for measurement, no CSP here (pure read
    // against already-committed stores).
    private static final double MEASUREMENT_MESH_PAD_BLOCKS = 3 * HEX_SIZE_BLOCKS;

    private record FaceResult(BiomeId biome, long worldSeed, double ratio) {
    }

    public static void main(String[] args) {
        BiomeGraph graph = ModBiomeGraph.OVERWORLD;
        List<FaceResult> results = new ArrayList<>();

        for (int seedOffset = 0; seedOffset < SEED_COUNT; seedOffset++) {
            long worldSeed = SEED_BASE + seedOffset;
            DecidedFaceStore faceStore = new DecidedFaceStore();
            DecidedVertexStore vertexStore = new DecidedVertexStore();
            SeedMeshCache cache = new SeedMeshCache(graph, GRID_CONFIG, worldSeed, faceStore, vertexStore);

            Set<HexGrid.AxialPos> queriedAxialPositions = new LinkedHashSet<>();
            for (double x = -AREA_HALF_WIDTH_BLOCKS; x <= AREA_HALF_WIDTH_BLOCKS; x += QUERY_SPACING_BLOCKS) {
                for (double z = -AREA_HALF_WIDTH_BLOCKS; z <= AREA_HALF_WIDTH_BLOCKS; z += QUERY_SPACING_BLOCKS) {
                    cache.biomeAt(GRID_CONFIG.cellContaining(x, z));
                    queriedAxialPositions.add(HexGrid.nearestAxialPos(new Point2D(x, z), HEX_SIZE_BLOCKS));
                }
            }

            int measured = 0;
            for (HexGrid.AxialPos axialPos : queriedAxialPositions) {
                Optional<FaceResult> result = measureFace(axialPos, worldSeed, faceStore, vertexStore);
                if (result.isPresent()) {
                    results.add(result.get());
                    measured++;
                }
            }
            System.out.println("worldSeed=" + worldSeed + ": queried " + queriedAxialPositions.size() + " faces, measured " + measured);
        }

        long violations = results.stream().filter(r -> Math.abs(r.ratio() - 1.0) > 0.10).count();
        System.out.println();
        System.out.printf("LIVE PATH violations=%d/%d (%.1f%%)%n", violations, results.size(), 100.0 * violations / Math.max(1, results.size()));

        System.out.println();
        System.out.println("=== per-biome realized/target ratio (all seeds combined), sorted worst-undersized first ===");
        Map<BiomeId, List<Double>> ratiosByBiome = new HashMap<>();
        for (FaceResult result : results) {
            ratiosByBiome.computeIfAbsent(result.biome(), b -> new ArrayList<>()).add(result.ratio());
        }
        Map<BiomeId, Double> medianByBiome = new TreeMap<>();
        ratiosByBiome.forEach((biome, ratios) -> medianByBiome.put(biome, median(ratios)));
        medianByBiome.entrySet().stream()
            .sorted(Map.Entry.comparingByValue())
            .forEach(e -> System.out.printf("%-32s median=%.3f  n=%d%n", e.getKey(), e.getValue(), ratiosByBiome.get(e.getKey()).size()));

        System.out.println();
        System.out.println("=== worst 10 by |ratio-1| ===");
        results.stream()
            .sorted(Comparator.comparingDouble((FaceResult r) -> Math.abs(r.ratio() - 1.0)).reversed())
            .limit(10)
            .forEach(r -> System.out.printf("%-32s ratio=%.2f (worldSeed=%d)%n", r.biome(), r.ratio(), r.worldSeed()));
    }

    /**
     * Reads this face's REAL committed state directly - empty if axial position has no
     * decided biome yet, or its own 6 corners aren't all committed yet (ordinary even for a
     * just-queried face: a corner only commits once ALL 3 owning faces have some value,
     * which can lag behind the covering face's own promotion - see {@code
     * TriangleFieldGeometryRealization.promoteFaceToPermanent}'s own doc).
     */
    private static Optional<FaceResult> measureFace(HexGrid.AxialPos axialPos, long worldSeed, DecidedFaceStore faceStore, DecidedVertexStore vertexStore) {
        Optional<DecidedFaceStore.DecidedFace> decided = faceStore.get(axialPos);
        if (decided.isEmpty()) {
            return Optional.empty();
        }

        Point2D center = HexGrid.centerOf(axialPos, HEX_SIZE_BLOCKS);
        double pad = MEASUREMENT_MESH_PAD_BLOCKS;
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(
            HEX_SIZE_BLOCKS, (int) Math.floor(center.x() - pad), (int) Math.floor(center.z() - pad),
            (int) Math.ceil(center.x() + pad), (int) Math.ceil(center.z() + pad)
        );
        FaceAdjacency.Adjacency adjacency = FaceAdjacency.of(mesh.faces());
        Optional<Integer> faceIndex = findFaceIndex(mesh, axialPos);
        if (faceIndex.isEmpty() || adjacency.hullTouchingFaceIndices().contains(faceIndex.get())) {
            return Optional.empty(); // measurement pad too tight - shouldn't happen given generous pad, skip rather than crash
        }

        List<Point2D> corners = committedCornersOf(mesh, adjacency, faceIndex.get(), vertexStore);
        if (corners == null) {
            return Optional.empty();
        }

        double realizedArea = shoelaceArea(corners);
        double targetArea = targetArea(decided.get().widthBlocks());
        return Optional.of(new FaceResult(decided.get().biomeId(), worldSeed, realizedArea / targetArea));
    }

    /** This face's own 6 corners, real cyclic order, read from {@code vertexStore} - {@code null} if any of the 6 isn't committed yet. */
    private static List<Point2D> committedCornersOf(HexMeshBuilder.HexMesh mesh, FaceAdjacency.Adjacency adjacency, int faceIndex, DecidedVertexStore vertexStore) {
        Set<Integer> ring = new LinkedHashSet<>();
        ring.add(faceIndex);
        ring.addAll(adjacency.neighborFaceIndices().getOrDefault(faceIndex, Set.of()));

        Map<Integer, Point2D> positionByVertexIndex = new HashMap<>();
        for (int[] triangle : adjacency.mutuallyAdjacentFaceTriples(ring)) {
            if (triangle[0] != faceIndex && triangle[1] != faceIndex && triangle[2] != faceIndex) {
                continue;
            }
            int vertexIndex = FaceAdjacency.sharedVertexOf(mesh, triangle);
            VertexKey key = VertexKey.of(mesh.faces().get(triangle[0]), mesh.faces().get(triangle[1]), mesh.faces().get(triangle[2]));
            Optional<Point2D> position = vertexStore.get(key);
            if (position.isEmpty()) {
                return null;
            }
            positionByVertexIndex.put(vertexIndex, position.get());
        }

        List<Point2D> corners = new ArrayList<>();
        for (int vertexIndex : mesh.faces().get(faceIndex).vertexIndices()) {
            Point2D position = positionByVertexIndex.get(vertexIndex);
            if (position == null) {
                return null;
            }
            corners.add(position);
        }
        return corners;
    }

    private static Optional<Integer> findFaceIndex(HexMeshBuilder.HexMesh mesh, HexGrid.AxialPos axialPos) {
        for (int faceIndex = 0; faceIndex < mesh.faces().size(); faceIndex++) {
            if (mesh.faces().get(faceIndex).axialPos().equals(axialPos)) {
                return Optional.of(faceIndex);
            }
        }
        return Optional.empty();
    }

    private static double shoelaceArea(List<Point2D> corners) {
        double sum = 0;
        int n = corners.size();
        for (int i = 0; i < n; i++) {
            Point2D a = corners.get(i);
            Point2D b = corners.get((i + 1) % n);
            sum += a.x() * b.z() - b.x() * a.z();
        }
        return Math.abs(sum) / 2.0;
    }

    private static double targetArea(int widthBlocks) {
        double circumradius = widthBlocks / 2.0;
        return (3.0 * Math.sqrt(3.0) / 2.0) * circumradius * circumradius;
    }

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        if (n == 0) {
            return Double.NaN;
        }
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }
}
