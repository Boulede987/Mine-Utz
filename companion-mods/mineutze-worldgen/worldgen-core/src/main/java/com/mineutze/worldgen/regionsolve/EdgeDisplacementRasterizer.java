package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.regionsolve.topology.EdgeDisplacement;
import com.mineutze.worldgen.regionsolve.topology.HexFace;
import com.mineutze.worldgen.regionsolve.topology.HexMeshBuilder;
import com.mineutze.worldgen.regionsolve.topology.Point2D;
import com.mineutze.worldgen.regionsolve.topology.TriangleIdentitySizeAssignment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Replaces {@link SeamGrowthRasterizer}'s competitive, per-cell-legality-checked growth
 * with independent per-face polygon rasterization: each free face's own displaced
 * boundary ({@link EdgeDisplacement} wiggle, positioned by {@link
 * com.mineutze.worldgen.regionsolve.topology.WeightedVertexPlacement}'s own power-diagram
 * vertex placement) is rasterized on its own, no competition, and — the whole point of
 * the hex-mesh pivot (see doc/decisions/2026-08-29-hex-mesh-edge-displacement.md) — NO
 * live per-cell legality re-check, because a hex mesh's vertex-degree-3 property already
 * makes the CSP's edge-only legality check complete: no vertex-only touch can ever slip
 * through the way it could for a triangle mesh.
 *
 * <p><b>Scope limitation, inherited from {@code WeightedVertexPlacement}:</b> a
 * cross-region boundary vertex (a face there isn't in this call's own {@code
 * assignments}, only its already-decided BIOME is known via {@code fixedBoundaryCells})
 * gets a baseline (unweighted) position there — still deterministic and watertight (both
 * regions independently compute the identical point for a shared physical vertex), just
 * without size-driven precision right at a region seam. A real open item this codebase's
 * own doc/decisions/2026-08-29-remove-region-stitching.md proposes eliminating entirely
 * by removing region-shaped stitching, not something silently solved here.
 *
 * <p><b>Completeness is an assertion, not a repair pass</b> — unlike growth (where an
 * unclaimed cell was an expected, frequent occurrence {@link GrowthGapFiller} existed to
 * mop up), every region cell should be covered by EXACTLY ONE face's own polygon by
 * construction here, given a generous enough mesh margin. A gap or overlap now means a
 * real bug (insufficient margin, or a rasterizer defect), not something to silently
 * paper over.
 *
 * <p><b>KNOWN, OPEN BUG (found 2026-09-04, not yet fixed):</b> {@link #requireNoOverlaps}
 * fires on 13 of the standard 50 starvation-test seeds — a face's own polygon can grow
 * large enough to overlap a DIFFERENT, non-adjacent face's territory in space, which
 * {@code WeightedVertexPlacement}'s per-face-local checks (simplicity, minimum edge
 * length) can never catch, since each only ever validates one face's own 6 corners in
 * isolation against itself. See {@code
 * ModBiomeGraphTest.overworldHasAKnownFacePolygonOverlapBug} (worldSeed=2031, the
 * smallest concrete reproducer) for the tracked regression case — do not silently soften
 * or remove {@link #requireNoOverlaps} to make callers stop seeing this; it is real.
 */
public final class EdgeDisplacementRasterizer {

    /** Kept proportional to each edge's own length (not an absolute block count) so texture stays comparable regardless of hex size tuning — same self-scaling-dial spirit {@link MacroGridConfig}'s own warp amplitude/wavelength coupling already established. */
    private static final double WIGGLE_FRACTION_OF_EDGE_LENGTH = 0.08;

    private EdgeDisplacementRasterizer() {
    }

    public static Map<MacroCellPos, BiomeId> rasterize(
        List<MacroCellPos> cells, Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments,
        HexMeshBuilder.HexMesh mesh, Map<Integer, Point2D> displacedVertexPositions, Map<Integer, BiomeId> fixedFaceBiomes,
        Map<MacroCellPos, BiomeId> fixedBoundaryCells, MacroGridConfig gridConfig, long worldSeed
    ) {
        Set<MacroCellPos> regionCellSet = new HashSet<>(cells);
        Map<MacroCellPos, BiomeId> decided = new HashMap<>(fixedBoundaryCells);

        Set<MacroCellPos> claimedThisCall = new HashSet<>();
        Set<MacroCellPos> overlaps = new LinkedHashSet<>();
        for (Map.Entry<Integer, TriangleIdentitySizeAssignment.Assignment> entry : assignments.entrySet()) {
            HexFace face = mesh.faces().get(entry.getKey());
            List<Point2D> boundary = displacedBoundaryOf(face, mesh, displacedVertexPositions, worldSeed);
            claimCellsInside(boundary, entry.getValue().biome(), regionCellSet, decided, gridConfig, claimedThisCall, overlaps);
        }

        // A fixed face (centroid inside an already-solved neighbor region, see
        // TriangleFieldGeometryRealization.buildFixedFaceBiomes) is never in `assignments`
        // (that map only ever holds free/CSP-solved faces) - without rasterizing its own
        // displaced polygon too, only the ONE cell that made it "fixed" in the first place
        // (already present via fixedBoundaryCells) would be covered, silently orphaning the
        // rest of that same face's own territory whenever it straddles into THIS region's
        // own cells. Its own corners still get whatever position WeightedVertexPlacement
        // computed for them (baseline-weighted, since a fixed face has no real assignment
        // here - see that class's own scope doc), so this is wiggle-only displacement right
        // at those corners, matching a cross-region edge's own documented "no size-driven
        // precision at a seam" limitation.
        for (Map.Entry<Integer, BiomeId> entry : fixedFaceBiomes.entrySet()) {
            HexFace face = mesh.faces().get(entry.getKey());
            List<Point2D> boundary = displacedBoundaryOf(face, mesh, displacedVertexPositions, worldSeed);
            claimCellsInside(boundary, entry.getValue(), regionCellSet, decided, gridConfig, claimedThisCall, overlaps);
        }

        requireEveryRegionCellCovered(cells, decided);
        requireNoOverlaps(overlaps);
        return collectResult(cells, decided);
    }

    /** Walks a face's own vertex loop, displacing each edge in turn - each edge's polyline already starts where the previous one ended, so only its FIRST point (except for the very first edge) is dropped to avoid a duplicate. Corner positions come from {@code displacedVertexPositions} where available (real, weighted placement), falling back to the raw undisplaced position for a true outer-hull vertex with no real mesh-vertex triple - see WeightedVertexPlacement's own doc. */
    private static List<Point2D> displacedBoundaryOf(
        HexFace face, HexMeshBuilder.HexMesh mesh, Map<Integer, Point2D> displacedVertexPositions, long worldSeed
    ) {
        List<Integer> vertexIndices = face.vertexIndices();
        int cornerCount = vertexIndices.size();
        List<Point2D> boundary = new ArrayList<>();
        for (int i = 0; i < cornerCount; i++) {
            int aIndex = vertexIndices.get(i);
            int bIndex = vertexIndices.get((i + 1) % cornerCount);
            Point2D a = displacedVertexPositions.getOrDefault(aIndex, mesh.vertices().get(aIndex));
            Point2D b = displacedVertexPositions.getOrDefault(bIndex, mesh.vertices().get(bIndex));
            double edgeLength = Math.hypot(b.x() - a.x(), b.z() - a.z());
            List<Point2D> edgePolyline = EdgeDisplacement.displacedPolyline(a, b, worldSeed, WIGGLE_FRACTION_OF_EDGE_LENGTH * edgeLength);
            boundary.addAll(boundary.isEmpty() ? edgePolyline : edgePolyline.subList(1, edgePolyline.size()));
        }
        return boundary;
    }

    /**
     * Only tests macro-cells within the boundary's own bounding box, not the whole region -
     * a real face touches at most a small, bounded slice of a large region.
     *
     * <p>{@code overlaps} records a cell whose center falls inside TWO different free
     * faces' own polygons - a real bug this class's own doc already claimed to treat as
     * seriously as a gap, but which nothing actually checked for until now. A cell already
     * present in {@code decided} from {@code fixedBoundaryCells} (an already-solved
     * neighbor region's own prior decision, not a face processed in THIS call) is a
     * legitimate, expected pre-decision, not an overlap - only a collision against ANOTHER
     * face from THIS SAME call's own {@code assignments}/{@code fixedFaceBiomes} counts.
     */
    private static void claimCellsInside(
        List<Point2D> boundary, BiomeId biome, Set<MacroCellPos> regionCellSet, Map<MacroCellPos, BiomeId> decided,
        MacroGridConfig gridConfig, Set<MacroCellPos> claimedThisCall, Set<MacroCellPos> overlaps
    ) {
        int cellSizeBlocks = gridConfig.macroCellSizeBlocks();
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

        int minCellX = Math.floorDiv((int) Math.floor(minX), cellSizeBlocks);
        int maxCellX = Math.floorDiv((int) Math.floor(maxX), cellSizeBlocks);
        int minCellZ = Math.floorDiv((int) Math.floor(minZ), cellSizeBlocks);
        int maxCellZ = Math.floorDiv((int) Math.floor(maxZ), cellSizeBlocks);

        for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
            for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                MacroCellPos candidate = new MacroCellPos(cellX, cellZ);
                if (!regionCellSet.contains(candidate)) {
                    continue;
                }
                double centerX = cellX * cellSizeBlocks + cellSizeBlocks / 2.0;
                double centerZ = cellZ * cellSizeBlocks + cellSizeBlocks / 2.0;
                if (!isInsidePolygon(boundary, centerX, centerZ)) {
                    continue;
                }
                if (claimedThisCall.contains(candidate)) {
                    overlaps.add(candidate); // a different face from THIS call already claimed it - keep its (first) claim, just report the collision
                    continue;
                }
                if (decided.containsKey(candidate)) {
                    continue; // already decided by fixedBoundaryCells (a neighbor region's own prior call) - expected, not an overlap
                }
                decided.put(candidate, biome);
                claimedThisCall.add(candidate);
            }
        }
    }

    /**
     * Standard even-odd ray-casting (PNPOLY): {@code boundary} is already closed (its
     * last point equals its first), so consecutive pairs alone walk the whole loop with
     * no wraparound needed. The strict {@code >} comparison on one side of each edge test
     * is what gives a consistent, deterministic owner to a point that lands exactly on a
     * shared edge or vertex — the half-open-boundary discipline {@link
     * com.mineutze.worldgen.regionsolve.topology.SeedGrid}/{@link
     * com.mineutze.worldgen.regionsolve.topology.HexGrid} already use elsewhere, applied
     * here to polygon membership instead of a box.
     */
    private static boolean isInsidePolygon(List<Point2D> boundary, double x, double z) {
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
     * A cell whose center falls inside TWO different faces' own polygons is a real bug
     * (a valid planar tiling never lets two cells overlap) - this class's own doc always
     * claimed to treat overlap as seriously as a gap, but nothing actually checked for it
     * until this was added alongside the minimum-edge-length fix in {@code
     * WeightedVertexPlacement}, whose own per-face-local checks can't see a face growing
     * large enough to reach into a DISTANT, non-adjacent face's territory - a genuinely
     * different failure mode from the local self-intersection/thin-edge ones that class
     * does guard against.
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
