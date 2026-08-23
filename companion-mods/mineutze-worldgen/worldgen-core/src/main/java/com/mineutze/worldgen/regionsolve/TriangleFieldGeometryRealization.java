package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.regionsolve.topology.DelaunayTriangulation;
import com.mineutze.worldgen.regionsolve.topology.IndexedTriangle;
import com.mineutze.worldgen.regionsolve.topology.Point2D;
import com.mineutze.worldgen.regionsolve.topology.SeedGrid;
import com.mineutze.worldgen.regionsolve.topology.TriangleAdjacency;
import com.mineutze.worldgen.regionsolve.topology.TriangleIdentitySizeAssignment;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The actual region-solve path: {@link SeedGrid} (topology-only placement) → {@link
 * DelaunayTriangulation} → {@link TriangleAdjacency} (real ≤3-neighbor mesh topology,
 * used by BOTH the CSP's legality-solving AND {@link SeamGrowthRasterizer}'s own
 * shared-seam inheritance) → {@link TriangleIdentitySizeAssignment} (fused identity+size
 * CSP) → {@link SeamGrowthRasterizer} (grows each face's actual pixel shape) → {@code
 * Map<MacroCellPos,BiomeId>}.
 *
 * <p><b>The mesh's raw point positions matter only up to identity+size assignment.</b>
 * After that, a face's actual SHAPE is never decided by a geometric query against mesh
 * positions (the old distance-minus-radius Voronoi competition, plus its whole
 * domain-warp field, is gone) — it's grown outward from whatever its already-decided
 * neighbors leave behind, cross-region {@code fixedBoundaryCells} included from the very
 * first cell. See {@link SeamGrowthRasterizer}'s own doc for the full mechanism and why
 * this closes the "thin bridge biome" seam artifact at its root, not just tunes around
 * it: {@link RegionSolveInput#fixedBoundaryCells} is still consulted for IDENTITY (a face
 * whose centroid falls in an already-solved neighbor is fixed to that neighbor's own
 * decision, via {@link TriangleIdentitySizeAssignment}'s existing {@code
 * fixedFaceBiomes} mechanism — see {@link #buildFixedFaceBiomes}), and {@link
 * com.mineutze.worldgen.biomegraph.BiomeGraph#requireEveryLocalNeighborhoodSolvable}
 * still proves the zero-tolerance dead end structurally unreachable for a well-formed
 * graph, rather than just hoping it's rare.
 *
 * <p>Real, separate, and still-open gap: the exact rendered pixel SHAPE at a region
 * border can still show a visible "join" in principle (a face growing right up against
 * a long stretch of fixed boundary has no guarantee its own frontier matches that
 * boundary's exact curve everywhere along it) — legality is guaranteed, curve-smoothness
 * across the seam is a separate, smaller, still-deferred concern.
 */
public final class TriangleFieldGeometryRealization {

    // Coarse relative to macroCellSizeBlocks (4) - matches doc/DESIGN.md's own target widths
    // (400-1000 blocks), so a typical triangle's territory is comparable to one real biome's
    // size, not many tiny slivers or one giant blob.
    private static final int SEED_SPACING_BLOCKS = 500;
    // Generous multiple of spacing - this is what guarantees every triangle touching the
    // actual region is non-hull-touching in TriangleAdjacency's sense (real neighbor
    // context on every side for the CSP's own legality solving).
    //
    // Package-private, not private: RegionBoundaryCollector.cellsBorderingRegion reuses
    // this SAME constant as its own collection depth - it's the exact bound past which no
    // face centroid in a region's own mesh can ever exist, so the two need a single shared
    // source of truth, not two independently-tuned numbers that could quietly drift apart.
    static final int TRIANGULATION_MARGIN_BLOCKS = SEED_SPACING_BLOCKS * 3;

    private TriangleFieldGeometryRealization() {
    }

    public static RegionSolveResult realize(RegionSolveInput input) {
        MacroGridConfig gridConfig = input.gridConfig();
        List<MacroCellPos> cells = gridConfig.cellsInRegion(input.region(), input.worldSeed());
        int cellSize = gridConfig.macroCellSizeBlocks();

        int minBlockX = cells.stream().mapToInt(MacroCellPos::x).min().orElseThrow() * cellSize;
        int maxBlockX = (cells.stream().mapToInt(MacroCellPos::x).max().orElseThrow() + 1) * cellSize;
        int minBlockZ = cells.stream().mapToInt(MacroCellPos::z).min().orElseThrow() * cellSize;
        int maxBlockZ = (cells.stream().mapToInt(MacroCellPos::z).max().orElseThrow() + 1) * cellSize;

        List<Point2D> points = SeedGrid.seedsIn(
            input.worldSeed(), SEED_SPACING_BLOCKS,
            minBlockX - TRIANGULATION_MARGIN_BLOCKS, minBlockZ - TRIANGULATION_MARGIN_BLOCKS,
            maxBlockX + TRIANGULATION_MARGIN_BLOCKS, maxBlockZ + TRIANGULATION_MARGIN_BLOCKS
        );
        List<IndexedTriangle> faces = DelaunayTriangulation.triangulateFaces(points);
        TriangleAdjacency.Adjacency adjacency = TriangleAdjacency.of(faces);

        Map<Integer, Point2D> allCentroids = new LinkedHashMap<>();
        for (int faceIndex = 0; faceIndex < faces.size(); faceIndex++) {
            allCentroids.put(faceIndex, centroidOf(faces.get(faceIndex), points));
        }
        Map<Integer, BiomeId> fixedFaceBiomes = buildFixedFaceBiomes(allCentroids, gridConfig, input.fixedBoundaryCells());

        Set<Integer> freeFaceIndices = new LinkedHashSet<>();
        for (int faceIndex = 0; faceIndex < faces.size(); faceIndex++) {
            if (!adjacency.hullTouchingFaceIndices().contains(faceIndex) && !fixedFaceBiomes.containsKey(faceIndex)) {
                freeFaceIndices.add(faceIndex);
            }
        }

        double regionAreaBlocks2 = (double) (maxBlockX - minBlockX) * (maxBlockZ - minBlockZ);
        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments =
            TriangleIdentitySizeAssignment.solve(adjacency, freeFaceIndices, fixedFaceBiomes, input.graph(), input.worldSeed(), regionAreaBlocks2)
                .orElseThrow(() -> new IllegalStateException(
                    "TriangleFieldGeometryRealization: no legal identity assignment exists for region " + input.region()
                        + " - the BiomeGraph's own construction-time bridgeability check only rules out configs "
                        + "impossible in principle, not every real local triangle arrangement."
                ));

        Map<MacroCellPos, BiomeId> result = SeamGrowthRasterizer.rasterize(
            cells, assignments, allCentroids, input.graph(), input.fixedBoundaryCells(), gridConfig
        );
        return new RegionSolveResult(result, List.of(), 0);
    }

    /**
     * Any free face whose centroid falls inside an already-solved neighbor region is fixed
     * to that neighbor's own already-committed biome at the containing cell, sourced from
     * {@link RegionSolveInput#fixedBoundaryCells()} — see this class's own doc for why this
     * closes the cross-region disagreement gap using {@link TriangleIdentitySizeAssignment}'s
     * existing fixed-context mechanism with no changes needed inside that class. A face whose
     * centroid finds no exact entry (its neighbor genuinely isn't solved yet, or it's simply
     * still within this region's own legitimate territory) is correctly left unfixed - there
     * is no ground truth to pin it to.
     */
    private static Map<Integer, BiomeId> buildFixedFaceBiomes(
        Map<Integer, Point2D> centroids, MacroGridConfig gridConfig, Map<MacroCellPos, BiomeId> fixedBoundaryCells
    ) {
        if (fixedBoundaryCells.isEmpty()) {
            return Map.of();
        }
        Map<Integer, BiomeId> fixedFaceBiomes = new LinkedHashMap<>();
        centroids.forEach((faceIndex, centroid) -> {
            MacroCellPos containing = gridConfig.cellContaining(centroid.x(), centroid.z());
            BiomeId fixed = fixedBoundaryCells.get(containing);
            if (fixed != null) {
                fixedFaceBiomes.put(faceIndex, fixed);
            }
        });
        return fixedFaceBiomes;
    }

    private static Point2D centroidOf(IndexedTriangle face, List<Point2D> points) {
        Point2D a = points.get(face.a());
        Point2D b = points.get(face.b());
        Point2D c = points.get(face.c());
        return new Point2D((a.x() + b.x() + c.x()) / 3.0, (a.z() + b.z() + c.z()) / 3.0);
    }
}
