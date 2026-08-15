package com.mineutze.worldgen.regionsolve.mesh;

import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.regionsolve.MacroCellPos;
import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mineutze.worldgen.regionsolve.RegionPos;
import com.mineutze.worldgen.regionsolve.RegionSolveInput;
import com.mineutze.worldgen.regionsolve.topology.Point2D;
import com.mineutze.worldgen.regionsolve.topology.SeedGraph;
import com.mineutze.worldgen.regionsolve.topology.SeedGraphNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * Builds the fine-mesh point scatter {@code MeshGeometryRealization} needs:
 * every already-colored point from the coarse {@link SeedGraph} (fixed
 * boundary cells and CSP-assigned interior seeds alike, both carried as
 * pre-colored "germ" points, mirroring how {@code SeedGraphBuilder} unifies
 * fixed and free nodes into one list), plus two purely structural additions
 * neither the coarse graph nor a plain merge step provides on their own:
 *
 * <p>A boundary ring one macro-cell outside the region square on every side
 * — without it, {@link com.mineutze.worldgen.regionsolve.topology.DelaunayTriangulation}'s
 * convex hull can fall short of covering the region on any side that has no
 * already-solved neighbor yet, leaving macro-cells near that edge with no
 * containing face at all.
 *
 * <p>Uncolored interior filler points at a finer spacing than the coarse
 * seed scatter, so most of the mesh has real atomic units smaller than a
 * whole patch for {@code PatchMerger} to grow with — germ points alone
 * would leave huge raw triangles spanning the gap between two coarse seeds.
 *
 * <p>Deduplicates by exact position (a {@code LinkedHashMap} keyed on
 * {@link Point2D}), preferring whichever point claimed that position first
 * — germ points are always added before ring/filler points, so a rare
 * coincidental collision never silently drops an identity.
 */
public final class FineMeshPointScatter {

    // Tuned from real exported output (seed 42), not guessed: 128 (2x macro-cell
    // size, when a macro-cell was 64 blocks) let MeshRasterizer's point-sampling
    // lose thin mesh-level connecting bridges between two parts of the same patch
    // entirely — a patch measurably CONNECTED at the mesh level (e.g. 5 components
    // after MeshGapFiller) came out MORE fragmented after rasterization (10
    // components), because no macro-cell center ever landed closer to a thin
    // bridge's tiny triangles than to the bigger flanking patches next to it — a
    // genuine sub-macro-cell mesh feature, not a rasterizer bug. Doubling to 4x
    // macro-cell size measurably fixed most of that (the same test case: forest
    // 5->3, plains 10->3, desert 3->3 unchanged) by keeping individual triangles
    // closer to macro-cell scale, so point-sampling can actually resolve them.
    // Still coarser increases risk losing thin REAL separator geometry
    // EdgeDisplacement needs to work with — re-tune if separator width/organic-read
    // regresses.
    //
    // Scaled from 256 to 16 alongside MacroGridConfig.DEFAULT's macroCellSizeBlocks
    // (64 -> 4, 2026-08-15) to hold that same 4x-macro-cell-size ratio — this
    // constant is a multiple of cell size, not an independent block measurement, so
    // it has to move with it or every triangle would suddenly span 64 macro-cells
    // instead of 4, undoing the tuning above.
    public static final int FINE_MESH_SPACING_BLOCKS = 16;

    private static final long RING_JITTER_SALT = 0x8113_2F17L;
    private static final long FILLER_JITTER_SALT = 0xF177_3E29L;

    private FineMeshPointScatter() {
    }

    public static List<ScatteredPoint> scatter(RegionSolveInput input, SeedGraph seedGraph, Map<Integer, BiomeId> assignedIdentities) {
        return scatter(input, seedGraph, assignedIdentities, FINE_MESH_SPACING_BLOCKS);
    }

    public static List<ScatteredPoint> scatter(RegionSolveInput input, SeedGraph seedGraph, Map<Integer, BiomeId> assignedIdentities, int fineSpacingBlocks) {
        Map<Point2D, ScatteredPoint> byPosition = new LinkedHashMap<>();

        addGermPoints(seedGraph, assignedIdentities, byPosition);
        addBoundaryRing(input, fineSpacingBlocks, byPosition);
        addFillerPoints(input, fineSpacingBlocks, byPosition);

        return List.copyOf(byPosition.values());
    }

    private static void addGermPoints(SeedGraph seedGraph, Map<Integer, BiomeId> assignedIdentities, Map<Point2D, ScatteredPoint> byPosition) {
        for (SeedGraphNode node : seedGraph.nodes()) {
            BiomeId germ = node.fixed() ? node.fixedBiome() : requireAssignment(assignedIdentities, node);
            Point2D point = toPoint(node.position());
            byPosition.putIfAbsent(point, new ScatteredPoint(point, Optional.of(germ)));
        }
    }

    private static BiomeId requireAssignment(Map<Integer, BiomeId> assignedIdentities, SeedGraphNode node) {
        BiomeId biome = assignedIdentities.get(node.id());
        if (biome == null) {
            throw new IllegalArgumentException("No CSP assignment for free SeedGraphNode " + node.id());
        }
        return biome;
    }

    /**
     * A ring strictly outside the region square, jittered only along its
     * outward axis (never inward, so the coverage guarantee always holds
     * regardless of jitter) — jittering only that axis is still enough to
     * break the exact collinearity a plain unjittered ring side would
     * otherwise have, since {@code DelaunayTriangulation} can't handle 3+
     * exactly collinear points.
     *
     * <p>Offset by {@link MacroGridConfig#warpMarginCells()}, not a fixed 1
     * cell — the region grid's own warp (that class's doc) can push a
     * cell's TRUE membership (per {@code cellsInRegion}) further from the
     * nominal square than a single cell, and the mesh has to actually
     * contain every real cell of the region or {@code MeshRasterizer} falls
     * back to its degraded nearest-centroid path for whatever's left
     * uncovered.
     */
    private static void addBoundaryRing(RegionSolveInput input, int fineSpacingBlocks, Map<Point2D, ScatteredPoint> byPosition) {
        MacroGridConfig grid = input.gridConfig();
        RegionPos region = input.region();
        int spacingCells = Math.max(1, fineSpacingBlocks / grid.macroCellSizeBlocks());
        int margin = grid.warpMarginCells();
        int minX = region.x() * grid.regionSizeCellsPerSide();
        int minZ = region.z() * grid.regionSizeCellsPerSide();
        int maxX = minX + grid.regionSizeCellsPerSide() - 1;
        int maxZ = minZ + grid.regionSizeCellsPerSide() - 1;
        long worldSeed = input.worldSeed();

        for (int along : ringSteps(minX - margin, maxX + margin, spacingCells)) {
            addRingPoint(along, minZ - margin, true, -1, spacingCells, worldSeed, byPosition);
            addRingPoint(along, maxZ + margin, true, 1, spacingCells, worldSeed, byPosition);
        }
        for (int along : ringSteps(minZ - margin, maxZ + margin, spacingCells)) {
            addRingPoint(minX - margin, along, false, -1, spacingCells, worldSeed, byPosition);
            addRingPoint(maxX + margin, along, false, 1, spacingCells, worldSeed, byPosition);
        }
    }

    /** Evenly spaced steps from start to end inclusive, always including the exact end point even if the spacing doesn't divide evenly. */
    private static List<Integer> ringSteps(int startInclusive, int endInclusive, int spacingCells) {
        List<Integer> steps = new ArrayList<>();
        for (int value = startInclusive; value < endInclusive; value += spacingCells) {
            steps.add(value);
        }
        steps.add(endInclusive);
        return steps;
    }

    private static void addRingPoint(int alongCoord, int outwardBaseline, boolean outwardIsZ, int outwardSign, int spacingCells, long worldSeed, Map<Point2D, ScatteredPoint> byPosition) {
        long hash = worldSeed ^ RING_JITTER_SALT;
        hash = hash * 31 + alongCoord;
        hash = hash * 31 + outwardBaseline;
        hash = hash * 31 + (outwardIsZ ? 1 : 0);
        double jitterMagnitude = new Random(hash).nextDouble() * spacingCells * 0.5;
        double outward = outwardBaseline + outwardSign * jitterMagnitude;

        Point2D point = outwardIsZ ? new Point2D(alongCoord, outward) : new Point2D(outward, alongCoord);
        byPosition.putIfAbsent(point, new ScatteredPoint(point, Optional.empty()));
    }

    private static void addFillerPoints(RegionSolveInput input, int fineSpacingBlocks, Map<Point2D, ScatteredPoint> byPosition) {
        MacroGridConfig grid = input.gridConfig();
        RegionPos region = input.region();
        int spacingCells = Math.max(1, fineSpacingBlocks / grid.macroCellSizeBlocks());
        int minX = region.x() * grid.regionSizeCellsPerSide();
        int minZ = region.z() * grid.regionSizeCellsPerSide();
        int sideLength = grid.regionSizeCellsPerSide();
        long worldSeed = input.worldSeed();

        for (int gridX = minX; gridX < minX + sideLength; gridX += spacingCells) {
            for (int gridZ = minZ; gridZ < minZ + sideLength; gridZ += spacingCells) {
                Random jitterRandom = new Random(fillerJitterSeed(worldSeed, gridX, gridZ));
                int jitteredX = Math.min(gridX + jitterRandom.nextInt(spacingCells), minX + sideLength - 1);
                int jitteredZ = Math.min(gridZ + jitterRandom.nextInt(spacingCells), minZ + sideLength - 1);
                Point2D point = new Point2D(jitteredX, jitteredZ);
                byPosition.putIfAbsent(point, new ScatteredPoint(point, Optional.empty()));
            }
        }
    }

    private static long fillerJitterSeed(long worldSeed, int gridX, int gridZ) {
        long seed = worldSeed ^ FILLER_JITTER_SALT;
        seed = seed * 31 + gridX;
        seed = seed * 31 + gridZ;
        return seed;
    }

    private static Point2D toPoint(MacroCellPos pos) {
        return new Point2D(pos.x(), pos.z());
    }
}
