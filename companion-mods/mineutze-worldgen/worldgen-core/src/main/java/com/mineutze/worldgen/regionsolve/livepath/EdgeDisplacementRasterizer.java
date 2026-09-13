package com.mineutze.worldgen.regionsolve.livepath;

import com.mineutze.worldgen.regionsolve.topology.mesh.HexMeshBuilder;

import com.mineutze.worldgen.regionsolve.topology.mesh.NodeEdge;

import com.mineutze.worldgen.regionsolve.topology.identity.TriangleIdentitySizeAssignment;

import com.mineutze.worldgen.regionsolve.topology.mesh.FaceAdjacency;

import com.mineutze.worldgen.regionsolve.topology.mesh.HexFace;

import com.mineutze.worldgen.regionsolve.MacroGridConfig;

import com.mineutze.worldgen.regionsolve.MacroCellPos;

import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.regionsolve.topology.EdgeDisplacement;
import com.mineutze.worldgen.regionsolve.topology.Point2D;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Replaces {@code SeamGrowthRasterizer}'s (deleted) competitive, per-cell-legality-checked
 * growth with independent per-face polygon rasterization: each free face's own displaced
 * boundary ({@link EdgeDisplacement} wiggle, positioned by {@link
 * com.mineutze.worldgen.regionsolve.topology.WeightedVertexPlacement}'s power-diagram
 * vertex placement) rasterizes on its own, no competition, and - the whole point of the
 * hex-mesh pivot (see doc/decisions/2026-08-29-hex-mesh-edge-displacement.md) - NO live
 * per-cell legality re-check: a hex mesh's vertex-degree-3 property already makes the
 * CSP's edge-only legality check complete, no vertex-only touch can slip through like it
 * could for a triangle mesh.
 *
 * <p><b>Scope limitation, inherited from {@code WeightedVertexPlacement}:</b> a
 * cross-region boundary vertex (face isn't in this call's {@code assignments}, only its
 * already-decided BIOME known via {@code fixedBoundaryCells}) gets a baseline
 * (unweighted) position - still deterministic and watertight (both regions independently
 * compute the identical point for a shared vertex), just without size-driven precision at
 * a region seam. Real open item: doc/decisions/2026-08-29-remove-region-stitching.md
 * proposes eliminating this entirely by removing region-shaped stitching, not solved
 * here.
 *
 * <p><b>Completeness is an assertion, not a repair pass</b> — unlike growth (unclaimed
 * cell was expected, frequent - {@code GrowthGapFiller}, deleted, mopped up), every
 * region cell should be covered by EXACTLY ONE face's polygon by construction, given a
 * generous enough mesh margin. A gap or overlap now means a real bug (insufficient
 * margin or rasterizer defect), not something to paper over.
 *
 * <p><b>Do not remove {@link #requireNoOverlaps} now that it's quiet</b> - it proved the
 * fix for a real overlap bug (see DECISIONS.md 2026-09-04 "MAX_VERTEX_DISPLACEMENT_FRACTION
 * (0.8) reinstated" entry), and catches future regressions. Regression-tested across the
 * full standard seed range in {@code
 * ModBiomeGraphTest.overworldHasZeroIllegalAdjacenciesAcrossEveryStarvationRangeSeed}.
 */
public final class EdgeDisplacementRasterizer {

    private EdgeDisplacementRasterizer() {
    }

    public static Map<MacroCellPos, BiomeId> rasterize(
        List<MacroCellPos> cells, Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments,
        HexMeshBuilder.HexMesh mesh, FaceAdjacency.Adjacency adjacency, Map<Integer, Point2D> displacedVertexPositions,
        Map<Integer, BiomeId> fixedFaceBiomes, Map<MacroCellPos, BiomeId> fixedBoundaryCells, MacroGridConfig gridConfig, long worldSeed
    ) {
        Set<MacroCellPos> regionCellSet = new HashSet<>(cells);
        Map<MacroCellPos, BiomeId> decided = new HashMap<>(fixedBoundaryCells);

        Set<MacroCellPos> claimedThisCall = new HashSet<>();
        Set<MacroCellPos> overlaps = new LinkedHashSet<>();
        for (Map.Entry<Integer, TriangleIdentitySizeAssignment.Assignment> entry : assignments.entrySet()) {
            List<Point2D> boundary = displacedBoundaryOf(entry.getKey(), mesh, adjacency, displacedVertexPositions, worldSeed);
            claimCellsInside(boundary, entry.getValue().biome(), regionCellSet, decided, gridConfig, claimedThisCall, overlaps);
        }

        // A fixed face (centroid inside an already-solved neighbor region, see
        // TriangleFieldGeometryRealization.buildFixedFaceBiomes) is never in `assignments`
        // (that map only holds free/CSP-solved faces) - without rasterizing its own
        // displaced polygon too, only the ONE cell that made it "fixed" (already in
        // fixedBoundaryCells) gets covered, silently orphaning the rest of that face's
        // territory when it straddles into THIS region's cells. Corners still get whatever
        // position WeightedVertexPlacement computed (baseline-weighted, no real assignment
        // here - see that class's scope doc), so this is wiggle-only displacement at those
        // corners, matching a cross-region edge's documented "no size-driven precision at a
        // seam" limitation.
        for (Map.Entry<Integer, BiomeId> entry : fixedFaceBiomes.entrySet()) {
            List<Point2D> boundary = displacedBoundaryOf(entry.getKey(), mesh, adjacency, displacedVertexPositions, worldSeed);
            claimCellsInside(boundary, entry.getValue(), regionCellSet, decided, gridConfig, claimedThisCall, overlaps);
        }

        requireEveryRegionCellCovered(cells, decided);
        requireNoOverlaps(overlaps);
        return collectResult(cells, decided);
    }

    /**
     * Walks a face's vertex loop, displacing each edge in turn - each polyline starts
     * where the previous one ended, so only its FIRST point (except the very first edge)
     * is dropped to avoid a duplicate. Corner positions come from {@code
     * displacedVertexPositions} where available (real, weighted placement), falling back
     * to the raw undisplaced position for a true outer-hull vertex with no real
     * mesh-vertex triple - see WeightedVertexPlacement's doc.
     *
     * <p>Looks up the OTHER face sharing each edge (if any) to give {@link
     * EdgeDisplacement} both owning faces' centers - its safety guarantee needs both, not
     * just this face's own. A hull edge (one owning face) passes {@code null} for the
     * other pole, matching a hull-touching face's already-lower precision elsewhere.
     *
     * <p>Package-visible (not private) so {@code FaceLocator}/cluster-growth code can
     * build a single face's real boundary directly, without a whole region/window solve.
     */
    static List<Point2D> displacedBoundaryOf(
        int faceIndex, HexMeshBuilder.HexMesh mesh, FaceAdjacency.Adjacency adjacency,
        Map<Integer, Point2D> displacedVertexPositions, long worldSeed
    ) {
        HexFace face = mesh.faces().get(faceIndex);
        List<Integer> vertexIndices = face.vertexIndices();
        int cornerCount = vertexIndices.size();
        Point2D thisFaceCenter = faceCenterOf(faceIndex, mesh, displacedVertexPositions);

        List<Point2D> boundary = new ArrayList<>();
        for (int cornerIndex = 0; cornerIndex < cornerCount; cornerIndex++) {
            List<Point2D> edgePolyline = displacedEdgePolyline(
                faceIndex, cornerIndex, vertexIndices, cornerCount, mesh, adjacency, displacedVertexPositions, thisFaceCenter, worldSeed
            );
            boundary.addAll(boundary.isEmpty() ? edgePolyline : edgePolyline.subList(1, edgePolyline.size()));
        }
        return boundary;
    }

    /** One edge's own displaced polyline - the loop body of {@link #displacedBoundaryOf}, pulled out so that method reads as "walk each edge, accumulate its polyline." */
    private static List<Point2D> displacedEdgePolyline(
        int faceIndex, int cornerIndex, List<Integer> vertexIndices, int cornerCount, HexMeshBuilder.HexMesh mesh,
        FaceAdjacency.Adjacency adjacency, Map<Integer, Point2D> displacedVertexPositions, Point2D thisFaceCenter, long worldSeed
    ) {
        int aIndex = vertexIndices.get(cornerIndex);
        int bIndex = vertexIndices.get((cornerIndex + 1) % cornerCount);
        Point2D a = displacedVertexPositions.getOrDefault(aIndex, mesh.vertices().get(aIndex));
        Point2D b = displacedVertexPositions.getOrDefault(bIndex, mesh.vertices().get(bIndex));

        Integer neighborIndex = otherFaceSharingEdge(faceIndex, aIndex, bIndex, adjacency, mesh);
        Point2D neighborCenter = neighborIndex == null ? null : faceCenterOf(neighborIndex, mesh, displacedVertexPositions);

        return EdgeDisplacement.displacedPolyline(a, b, worldSeed, thisFaceCenter, neighborCenter);
    }

    /** Centroid of a face's CURRENT (displaced, still-straight) 6 corners - the pole {@link EdgeDisplacement} measures angles against, not the original undisplaced lattice point, since angles must be meaningful relative to the shape actually being wiggled. */
    private static Point2D faceCenterOf(int faceIndex, HexMeshBuilder.HexMesh mesh, Map<Integer, Point2D> displacedVertexPositions) {
        List<Integer> vertexIndices = mesh.faces().get(faceIndex).vertexIndices();
        double sumX = 0;
        double sumZ = 0;
        for (int vertexIndex : vertexIndices) {
            Point2D corner = displacedVertexPositions.getOrDefault(vertexIndex, mesh.vertices().get(vertexIndex));
            sumX += corner.x();
            sumZ += corner.z();
        }
        int cornerCount = vertexIndices.size();
        return new Point2D(sumX / cornerCount, sumZ / cornerCount);
    }

    /** The one other face (if any) sharing physical edge (aIndex, bIndex) with faceIndex - null for a hull edge (one real owning face). Checked via each candidate neighbor's own edge list rather than a separate edge-to-faces map - a face has at most 6 neighbors, cheap either way. */
    private static Integer otherFaceSharingEdge(int faceIndex, int aIndex, int bIndex, FaceAdjacency.Adjacency adjacency, HexMeshBuilder.HexMesh mesh) {
        NodeEdge edge = NodeEdge.of(aIndex, bIndex);
        for (int neighborIndex : adjacency.neighborFaceIndices().getOrDefault(faceIndex, Set.of())) {
            if (mesh.faces().get(neighborIndex).edges().contains(edge)) {
                return neighborIndex;
            }
        }
        return null;
    }

    /**
     * Only tests macro-cells within the boundary's own bounding box, not the whole region -
     * a real face touches at most a small, bounded slice of a large region.
     *
     * <p>{@code overlaps} records a cell whose center falls inside TWO different free
     * faces' polygons - real bug this class's doc always claimed to treat as seriously as
     * a gap, but nothing checked for until now. A cell already in {@code decided} from
     * {@code fixedBoundaryCells} (an already-solved neighbor region's prior decision, not
     * a face from THIS call) is a legitimate pre-decision, not an overlap - only a
     * collision against ANOTHER face from THIS SAME call's {@code assignments}/{@code
     * fixedFaceBiomes} counts.
     */
    private static void claimCellsInside(
        List<Point2D> boundary, BiomeId biome, Set<MacroCellPos> regionCellSet, Map<MacroCellPos, BiomeId> decided,
        MacroGridConfig gridConfig, Set<MacroCellPos> claimedThisCall, Set<MacroCellPos> overlaps
    ) {
        int cellSizeBlocks = gridConfig.macroCellSizeBlocks();
        BoundingBox bounds = boundingBoxOf(boundary);
        int minCellX = Math.floorDiv((int) Math.floor(bounds.minX()), cellSizeBlocks);
        int maxCellX = Math.floorDiv((int) Math.floor(bounds.maxX()), cellSizeBlocks);
        int minCellZ = Math.floorDiv((int) Math.floor(bounds.minZ()), cellSizeBlocks);
        int maxCellZ = Math.floorDiv((int) Math.floor(bounds.maxZ()), cellSizeBlocks);

        for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
            for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                claimCellIfInside(new MacroCellPos(cellX, cellZ), boundary, biome, regionCellSet, decided, cellSizeBlocks, claimedThisCall, overlaps);
            }
        }
    }

    private record BoundingBox(double minX, double maxX, double minZ, double maxZ) {
    }

    private static BoundingBox boundingBoxOf(List<Point2D> boundary) {
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (Point2D point : boundary) {
            minX = Math.min(minX, point.x());
            maxX = Math.max(maxX, point.x());
            minZ = Math.min(minZ, point.z());
            maxZ = Math.max(maxZ, point.z());
        }
        return new BoundingBox(minX, maxX, minZ, maxZ);
    }

    /** One candidate cell's own claim attempt - the innermost body of {@link #claimCellsInside}'s double loop, pulled out so that loop reads as "for each candidate cell in the bounding box, try to claim it." */
    private static void claimCellIfInside(
        MacroCellPos candidate, List<Point2D> boundary, BiomeId biome, Set<MacroCellPos> regionCellSet, Map<MacroCellPos, BiomeId> decided,
        int cellSizeBlocks, Set<MacroCellPos> claimedThisCall, Set<MacroCellPos> overlaps
    ) {
        if (!regionCellSet.contains(candidate)) {
            return;
        }
        double centerX = candidate.x() * cellSizeBlocks + cellSizeBlocks / 2.0;
        double centerZ = candidate.z() * cellSizeBlocks + cellSizeBlocks / 2.0;
        if (!isInsidePolygon(boundary, centerX, centerZ)) {
            return;
        }
        if (claimedThisCall.contains(candidate)) {
            overlaps.add(candidate); // a different face from THIS call already claimed it - keep its (first) claim, just report the collision
            return;
        }
        if (decided.containsKey(candidate)) {
            return; // already decided by fixedBoundaryCells (a neighbor region's own prior call) - expected, not an overlap
        }
        decided.put(candidate, biome);
        claimedThisCall.add(candidate);
    }

    /**
     * Standard even-odd ray-casting (PNPOLY): {@code boundary} is already closed (last
     * point equals first), so consecutive pairs alone walk the whole loop, no wraparound
     * needed. The strict {@code >} comparison on one side of each edge test gives a
     * consistent, deterministic owner to a point landing exactly on a shared edge/vertex
     * - the half-open-boundary discipline {@link
     * com.mineutze.worldgen.regionsolve.topology.SeedGrid}/{@link
     * com.mineutze.worldgen.regionsolve.topology.HexGrid} already use elsewhere, applied
     * here to polygon membership instead of a box.
     *
     * <p>Package-visible (not private) - see {@link #displacedBoundaryOf}'s doc for why.
     */
    static boolean isInsidePolygon(List<Point2D> boundary, double x, double z) {
        boolean inside = false;
        for (int i = 0; i < boundary.size() - 1; i++) {
            Point2D p1 = boundary.get(i);
            Point2D p2 = boundary.get(i + 1);
            boolean p1AboveZ = p1.z() > z;
            boolean p2AboveZ = p2.z() > z;
            if (p1AboveZ != p2AboveZ) {
                double intersectX = p1.x() + (z - p1.z()) / (p2.z() - p1.z()) * (p2.x() - p1.x());
                if (x < intersectX) {
                    inside = !inside;
                }
            }
        }
        return inside;
    }

    private static void requireEveryRegionCellCovered(List<MacroCellPos> cells, Map<MacroCellPos, BiomeId> decided) {
        List<MacroCellPos> uncovered = new ArrayList<>();
        for (MacroCellPos cell : cells) {
            if (!decided.containsKey(cell)) {
                uncovered.add(cell);
                if (uncovered.size() >= 5) {
                    break; // enough to diagnose - the full list isn't more useful once a real gap exists at all
                }
            }
        }
        if (!uncovered.isEmpty()) {
            throw new IllegalStateException(
                "EdgeDisplacementRasterizer: " + uncovered.size() + "+ region cell(s) not covered by any face's own polygon, e.g. "
                    + uncovered + " - insufficient mesh margin or a rasterizer defect, not an expected occurrence under this design"
            );
        }
    }

    /**
     * A cell whose center falls inside TWO different faces' polygons is a real bug (valid
     * planar tiling never overlaps cells) - this class's doc always claimed to treat
     * overlap as seriously as a gap, but nothing checked until this was added alongside
     * the minimum-edge-length fix in {@code WeightedVertexPlacement}, whose per-face-local
     * checks can't see a face growing large enough to reach a DISTANT, non-adjacent
     * face's territory - different failure mode from the local self-intersection/thin-edge
     * ones that class guards against.
     */
    private static void requireNoOverlaps(Set<MacroCellPos> overlaps) {
        if (!overlaps.isEmpty()) {
            throw new IllegalStateException(
                "EdgeDisplacementRasterizer: " + overlaps.size() + " region cell(s) claimed by more than one face's own polygon, e.g. "
                    + overlaps.stream().limit(5).toList() + " - two faces' territory overlapped in space, not an expected occurrence under this design"
            );
        }
    }

    private static Map<MacroCellPos, BiomeId> collectResult(List<MacroCellPos> cells, Map<MacroCellPos, BiomeId> decided) {
        Map<MacroCellPos, BiomeId> result = new LinkedHashMap<>();
        for (MacroCellPos cell : cells) {
            result.put(cell, decided.get(cell));
        }
        return result;
    }
}
