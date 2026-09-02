package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.regionsolve.topology.HexFace;
import com.mineutze.worldgen.regionsolve.topology.HexMeshBuilder;
import com.mineutze.worldgen.regionsolve.topology.Point2D;
import com.mineutze.worldgen.regionsolve.topology.TriangleAdjacency;
import com.mineutze.worldgen.regionsolve.topology.TriangleIdentitySizeAssignment;
import com.mineutze.worldgen.regionsolve.topology.WeightedVertexPlacement;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The live region-solve path: {@link HexMeshBuilder} (regular hex lattice, exactly 3
 * faces meeting at every vertex — see doc/decisions/2026-08-29-hex-mesh-edge-
 * displacement.md) → {@link TriangleAdjacency} (real ≤6-neighbor mesh topology) → {@link
 * TriangleIdentitySizeAssignment} (fused identity+size CSP, legality fully decided
 * here) → {@link WeightedVertexPlacement} (power-diagram vertex placement, size baked
 * directly into WHERE each corner sits) → {@link EdgeDisplacementRasterizer}
 * (independent per-face polygon rasterization, no competition, no live legality
 * re-check) → {@code Map<MacroCellPos,BiomeId>}.
 *
 * <p>Supersedes the earlier Delaunay-triangle-mesh + competitive-growth path (deleted
 * alongside {@link DelaunayTriangulation}, {@code SeamGrowthRasterizer}, {@code
 * GrowthGapFiller}): a triangle's vertex degree averages ~6, so two triangles can share
 * only a vertex (not an edge) and still end up pixel-adjacent, a case the CSP's edge-only
 * legality graph never checked — that gap was what forced the old rasterizer to re-check
 * legality live, per cell, during growth. A hex mesh's vertex-degree-3 property makes
 * edge-adjacency and vertex-adjacency the same relation, always, so CSP-time legality
 * checking is provably complete and the rasterizer needs no live check at all.
 *
 * <p><b>The mesh's raw point positions matter only up to identity+size assignment.</b>
 * After that, a face's actual SHAPE comes from its own corners' weighted positions
 * ({@link WeightedVertexPlacement}) plus organic wiggle ({@link
 * com.mineutze.worldgen.regionsolve.topology.EdgeDisplacement}), never from growth
 * competing for territory. {@link RegionSolveInput#fixedBoundaryCells} is still
 * consulted for IDENTITY (a face whose centroid falls in an already-solved neighbor is
 * fixed to that neighbor's own decision, via {@link TriangleIdentitySizeAssignment}'s
 * existing {@code fixedFaceBiomes} mechanism — see {@link #buildFixedFaceBiomes}); a
 * cross-region vertex falls back to a baseline (unweighted) position in {@link
 * WeightedVertexPlacement} (only real, in-region assignments carry a real weight — see
 * its own doc), so a region boundary displaces with wiggle only, not size-driven
 * precision — matches the same "legality guaranteed, seam smoothness a separate
 * concern" split the old path already had.
 */
public final class TriangleFieldGeometryRealization {

    // 650-block undisplaced width (circumradius 325), not the naive 800 (circumradius
    // 400) doc/DESIGN.md's raw 300-1000 range midpoint would suggest - chosen to minimize
    // the REAL 48-biome catalog's total starting size deficit instead: ModBiomeGraph.
    // OVERWORLD's own size ranges aren't evenly spread across that 300-1000 range (14
    // biomes in a 300-600 tier, 24 in 500-800, only 10 in 700-1000), and 800 sits at
    // tier-2's own ceiling but far above the ENTIRE tier-1 range (max 600). 650 was picked
    // by minimizing total |targetArea(biome's own midpoint width) - targetArea(650)|
    // summed across all 48 real biomes, weighted by how many biomes actually fall in each
    // tier - see WeightedVertexPlacement's own doc for the area/weight formula this
    // reuses. Still not a full fix, even under the current power-diagram size mechanism
    // (this constant was originally justified against the OLDER, deleted per-edge
    // additive-bias mechanism, whose "same-tier neighbors trade zero area" degeneracy no
    // longer exists as a hard rule - but a real, measured, softer version of the same
    // problem remains: a face wanting to be much smaller than its actual surrounding
    // weights still lands systematically oversized, since weight-equals-target-area is
    // only a one-shot proxy for realized area, not an exact one - see
    // WeightedVertexPlacement's own doc). This constant reduces every face's OWN starting
    // gap regardless of which size mechanism sits downstream of it.
    //
    // This EXACT value was tried once already and reverted: it originally exposed two real
    // dead ends in TriangleIdentitySizeAssignment's own backtracking search that plain
    // forward checking (and later, binary AC-3 alone) couldn't see - worldSeed=2034's
    // 13s-at-400-but-non-terminating-at-325 case, then worldSeed=2060's genuine
    // zero-valid-triple mesh-vertex dead end. Both are fixed now (real AC-3 + mesh-vertex
    // triple consistency + a hard node-budget safety net - see that class's own doc for
    // the full story). Swept all 50 real starvation-test seeds at this exact value
    // post-fix: 100% solve clean. Re-applying it is not just re-trying the same broken
    // thing again - the actual blocker was fixed at its own root, in the CSP, not here.
    private static final double HEX_SIZE_BLOCKS = 325;
    // Generous margin (matches the old triangle mesh's own empirically-trusted value) -
    // guarantees every face touching the actual region is non-hull-touching (real
    // neighbor context on every side for the CSP's own legality solving).
    //
    // Package-private, not private: RegionBoundaryCollector.cellsBorderingRegion reuses
    // this SAME constant as its own collection depth - it's the exact bound past which no
    // face centroid in a region's own mesh can ever exist, so the two need a single shared
    // source of truth, not two independently-tuned numbers that could quietly drift apart.
    static final int TRIANGULATION_MARGIN_BLOCKS = 1500;

    private TriangleFieldGeometryRealization() {
    }

    /** This region's own mesh cells' bounding box, in block coordinates — the input to both mesh seeding (padded by {@link #TRIANGULATION_MARGIN_BLOCKS}) and the CSP's own area-fairness math. */
    private record BlockBounds(int minX, int maxX, int minZ, int maxZ) {
        double areaBlocks2() {
            return (double) (maxX - minX) * (maxZ - minZ);
        }
    }

    public static RegionSolveResult realize(RegionSolveInput input) {
        MacroGridConfig gridConfig = input.gridConfig();
        List<MacroCellPos> cells = gridConfig.cellsInRegion(input.region(), input.worldSeed());
        BlockBounds bounds = blockBoundsOf(cells, gridConfig.macroCellSizeBlocks());

        HexMeshBuilder.HexMesh mesh = buildMesh(bounds);
        TriangleAdjacency.Adjacency adjacency = TriangleAdjacency.of(mesh.faces());
        Map<Integer, Point2D> centroids = centroidsOf(mesh);

        Map<Integer, BiomeId> fixedFaceBiomes = buildFixedFaceBiomes(centroids, gridConfig, input.fixedBoundaryCells());
        Set<Integer> freeFaceIndices = freeFaceIndices(mesh.faces().size(), adjacency, fixedFaceBiomes);

        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments = solveIdentities(
            adjacency, freeFaceIndices, fixedFaceBiomes, input.graph(), input.worldSeed(), bounds.areaBlocks2(), input.region()
        );

        Map<Integer, Point2D> displacedVertices = WeightedVertexPlacement.displacedVertexPositions(mesh, adjacency, assignments, HEX_SIZE_BLOCKS);
        Map<MacroCellPos, BiomeId> result = EdgeDisplacementRasterizer.rasterize(
            cells, assignments, mesh, displacedVertices, fixedFaceBiomes, input.fixedBoundaryCells(), gridConfig, input.worldSeed()
        );
        return new RegionSolveResult(result, List.of(), 0);
    }

    private static BlockBounds blockBoundsOf(List<MacroCellPos> cells, int cellSize) {
        int minBlockX = cells.stream().mapToInt(MacroCellPos::x).min().orElseThrow() * cellSize;
        int maxBlockX = (cells.stream().mapToInt(MacroCellPos::x).max().orElseThrow() + 1) * cellSize;
        int minBlockZ = cells.stream().mapToInt(MacroCellPos::z).min().orElseThrow() * cellSize;
        int maxBlockZ = (cells.stream().mapToInt(MacroCellPos::z).max().orElseThrow() + 1) * cellSize;
        return new BlockBounds(minBlockX, maxBlockX, minBlockZ, maxBlockZ);
    }

    /** {@code bounds} padded by {@link #TRIANGULATION_MARGIN_BLOCKS} on every side - the margin is what guarantees every face touching the actual region is non-hull-touching (real neighbor context on every side for the CSP's own legality solving). */
    private static HexMeshBuilder.HexMesh buildMesh(BlockBounds bounds) {
        return HexMeshBuilder.build(
            HEX_SIZE_BLOCKS,
            bounds.minX() - TRIANGULATION_MARGIN_BLOCKS, bounds.minZ() - TRIANGULATION_MARGIN_BLOCKS,
            bounds.maxX() + TRIANGULATION_MARGIN_BLOCKS, bounds.maxZ() + TRIANGULATION_MARGIN_BLOCKS
        );
    }

    private static Map<Integer, Point2D> centroidsOf(HexMeshBuilder.HexMesh mesh) {
        Map<Integer, Point2D> centroids = new LinkedHashMap<>();
        for (int faceIndex = 0; faceIndex < mesh.faces().size(); faceIndex++) {
            centroids.put(faceIndex, centroidOf(mesh.faces().get(faceIndex), mesh.vertices()));
        }
        return centroids;
    }

    private static Point2D centroidOf(HexFace face, List<Point2D> vertices) {
        double sumX = 0;
        double sumZ = 0;
        for (int vertexIndex : face.vertexIndices()) {
            Point2D vertex = vertices.get(vertexIndex);
            sumX += vertex.x();
            sumZ += vertex.z();
        }
        int cornerCount = face.vertexIndices().size();
        return new Point2D(sumX / cornerCount, sumZ / cornerCount);
    }

    /** Every face that's neither hull-touching (missing real neighbor context, see {@link TriangleAdjacency}'s own doc) nor already fixed by a neighbor region's own solve - the CSP only ever assigns these. */
    private static Set<Integer> freeFaceIndices(int faceCount, TriangleAdjacency.Adjacency adjacency, Map<Integer, BiomeId> fixedFaceBiomes) {
        Set<Integer> freeFaceIndices = new LinkedHashSet<>();
        for (int faceIndex = 0; faceIndex < faceCount; faceIndex++) {
            if (!adjacency.hullTouchingFaceIndices().contains(faceIndex) && !fixedFaceBiomes.containsKey(faceIndex)) {
                freeFaceIndices.add(faceIndex);
            }
        }
        return freeFaceIndices;
    }

    private static Map<Integer, TriangleIdentitySizeAssignment.Assignment> solveIdentities(
        TriangleAdjacency.Adjacency adjacency, Set<Integer> freeFaceIndices, Map<Integer, BiomeId> fixedFaceBiomes,
        BiomeGraph graph, long worldSeed, double regionAreaBlocks2, RegionPos region
    ) {
        return TriangleIdentitySizeAssignment.solve(adjacency, freeFaceIndices, fixedFaceBiomes, graph, worldSeed, regionAreaBlocks2)
            .orElseThrow(() -> new IllegalStateException(
                "TriangleFieldGeometryRealization: no legal identity assignment exists for region " + region
                    + " - the BiomeGraph's own construction-time checks only rule out configs "
                    + "impossible in principle, not every real local mesh arrangement."
            ));
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
}
