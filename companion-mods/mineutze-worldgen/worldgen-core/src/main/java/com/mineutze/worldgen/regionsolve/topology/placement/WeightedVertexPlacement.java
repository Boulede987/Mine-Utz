package com.mineutze.worldgen.regionsolve.topology.placement;

import com.mineutze.worldgen.regionsolve.topology.Point2D;

import com.mineutze.worldgen.regionsolve.topology.mesh.HexMeshBuilder;

import com.mineutze.worldgen.regionsolve.topology.identity.TriangleIdentitySizeAssignment;

import com.mineutze.worldgen.regionsolve.topology.mesh.FaceAdjacency;

import com.mineutze.worldgen.regionsolve.livepath.TriangleFieldGeometryRealization;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-region computation over already-CSP-assigned sizes, run once between {@link
 * TriangleIdentitySizeAssignment}'s CSP and the edge-displacement rasterizer. Computes a
 * NEW position for every mesh vertex directly from each face's target size, via a power
 * diagram (weighted Voronoi diagram) over the mesh's own face centers. See DECISIONS.md
 * 2026-09-02 "Power-diagram vertex placement" for why this replaced the older per-edge
 * bias mechanism.
 *
 * <p><b>Core idea.</b> Give every face's hex center a WEIGHT derived from its target
 * width ({@link PowerDiagramMath#powerWeight}). The boundary between two faces in a power
 * diagram is a straight line determined ENTIRELY by comparing the two weights'
 * MAGNITUDES — no "which direction does my neighbor want" concept anywhere in the math.
 *
 * <p><b>Vertex consistency is a theorem, not an engineering trick.</b> For any 3
 * non-collinear weighted points (never collinear for 3 mutually-adjacent hex face
 * centers — see {@link PowerDiagramMath#radicalCenter}), their three pairwise boundary
 * lines always meet at one common point — the RADICAL CENTER, a classical circle-geometry
 * result. That's exactly what this class computes per mesh vertex, replacing the raw,
 * undisplaced hex vertex {@link HexMeshBuilder} produced. When every face has an
 * IDENTICAL weight (uniform sizes, nothing to correct), the radical center reduces
 * exactly to the ordinary circumcenter of the three face centers — which, in a regular
 * hex lattice, is exactly the original undisplaced vertex position, so this provably
 * subsumes the "nothing to correct" baseline as a special case.
 *
 * <p><b>Scope: real weights only where a real assignment exists.</b> A face with no
 * {@link TriangleIdentitySizeAssignment.Assignment} (a fixed cross-region face — its real
 * chosen width was never tracked across regions, only its biome; or a hull-touching face
 * with no assignment at all) falls back to {@code baselineCircumradiusBlocks} as its
 * weight, held FIXED throughout calibration — any vertex touching one gets LESS
 * size-driven displacement right at that seam.
 *
 * <p><b>Weight is only a one-shot proxy for realized area — calibrated by iteration, not
 * assumed.</b> A cell's realized area in a power diagram depends on ALL its neighbors'
 * weights too, not just its own, so naively setting {@code weight = powerWeight(target)}
 * once measurably undershoots for a face whose target diverges far from its actual
 * neighbors. Expected, not a bug — for any positive target areas summing to the domain's
 * total area, weights realizing them EXACTLY are known to exist (semi-discrete optimal
 * transport existence result for power diagrams), just not via closed form. {@code
 * CalibratedVertexPlacement} finds them approximately via several rounds of ADDITIVE
 * correction — justified by a real, provable monotonicity property of power diagrams
 * (increasing one site's weight, holding others fixed, strictly increases its own area
 * and strictly decreases every neighbor's).
 *
 * <p><b>This class is a thin facade.</b> The real work lives in its collaborators, each
 * with one job: {@link PowerDiagramMath} (weight/radical-center math), {@link
 * HexPolygonSafety} (per-face validity and safe-scaling), {@link MeshFaceGeometry}
 * (shared per-face data prep), {@link JointVertexGeometry} (whole-mesh raw-position +
 * validity reconciliation), {@code CalibratedVertexPlacement} (export/measurement-only
 * JOINT calibration path), {@code SequentialVertexPlacement} (shipped mod's real live
 * path), and {@code AchievableAreaProbe} (CSP-time achievable-range probe).
 */
public final class WeightedVertexPlacement {

    private WeightedVertexPlacement() {
    }

    /**
     * A face's real, achievable realized-area range - see {@link #achievableAreaRangeFor}'s
     * own doc. Public (not package-private): {@link TriangleIdentitySizeAssignment} - now in
     * the sibling {@code topology.identity} package after this session's topology reorg -
     * consumes this as the one, explicit, narrow crossing into this class's own real
     * geometry math (see that class's own doc).
     */
    public record AreaRange(double minAreaBlocks2, double maxAreaBlocks2) {
    }

    /**
     * <b>Export/measurement-only today, not the shipped mod's live path</b> — real callers
     * are {@code HexBiomeRasterVisual}, {@code SizeFidelityMeasurement}, and {@link
     * com.mineutze.worldgen.regionsolve.livepath.TriangleFieldGeometryRealization#realize}
     * (itself export-only, see that method's doc). Live path uses {@link
     * #sequentiallyRealizedVertexPositions} instead, via {@code
     * TriangleFieldGeometryRealization#realizeCluster}.
     *
     * <p>One displaced position per mesh vertex reachable from a real mesh-vertex triple,
     * keyed by vertex index into {@code mesh.vertices()} — usable in place of a raw {@code
     * mesh.vertices()} lookup wherever a face's corner position is needed downstream. A
     * vertex whose 3 mutually-adjacent faces can't be found (a true outer-hull vertex,
     * structurally expected at the mesh's boundary — see {@link FaceAdjacency}) has no
     * entry here; callers fall back to raw position, matching how hull-touching faces are
     * already excluded from CSP assignment without error elsewhere in this pipeline.
     *
     * <p>Equivalent to calling the 7-argument overload with no already-decided context —
     * every face falls back to {@code baselineCircumradiusBlocks} exactly as before this
     * class became store-aware. Kept so every existing caller/test compiles unchanged.
     */
    public static Map<Integer, Point2D> displacedVertexPositions(
        HexMeshBuilder.HexMesh mesh, FaceAdjacency.Adjacency adjacency,
        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments, double baselineCircumradiusBlocks,
        double minEdgeLengthBlocks
    ) {
        return displacedVertexPositions(mesh, adjacency, assignments, baselineCircumradiusBlocks, minEdgeLengthBlocks, Map.of(), Map.of());
    }

    /**
     * Store-aware overload: {@code committedWidthBlocksByFace} supplies a REAL width for a
     * face with no {@code assignments} entry this batch since it was already decided by an
     * earlier batch (via {@code DecidedFaceStore}) — used in place of the generic {@code
     * baselineCircumradiusBlocks} fallback, and (like every unassigned face) held FIXED
     * throughout calibration, never recalibrated. Direct fix for the cross-region seam
     * bug: two batches independently computing the same shared vertex used to feed a
     * DIFFERENT weight for whichever neighbor face wasn't their own free assignment
     * (baseline placeholder vs. the neighbor's real chosen width), producing two different
     * radical centers for what should be one physical point.
     *
     * <p>{@code committedVertexPositions} supplies a vertex's FINAL position, already
     * committed by an earlier batch (via {@code DecidedVertexStore}) — treated as an
     * immovable anchor: used as-is for every face that owns it (so neighboring, still-fresh
     * corners validate against its TRUE final shape), never recomputed or reshrunk.
     * Combined with the fixed-weight behavior above, this guarantees a vertex committed
     * once is reproduced identically by every later batch that touches it, closing the
     * watertightness gap at its actual root instead of only removing the region-shaped
     * partition that used to make it visible.
     */
    public static Map<Integer, Point2D> displacedVertexPositions(
        HexMeshBuilder.HexMesh mesh, FaceAdjacency.Adjacency adjacency,
        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments, double baselineCircumradiusBlocks,
        double minEdgeLengthBlocks, Map<Integer, Integer> committedWidthBlocksByFace, Map<Integer, Point2D> committedVertexPositions
    ) {
        return CalibratedVertexPlacement.displace(
            mesh, adjacency, assignments, baselineCircumradiusBlocks, minEdgeLengthBlocks, committedWidthBlocksByFace, committedVertexPositions
        );
    }

    /** See {@code SequentialVertexPlacement}'s own class doc for the full "no tug-of-war" design this delegates to - the shipped mod's real live path, via {@code TriangleFieldGeometryRealization#realizeCluster}. */
    public static Map<Integer, Point2D> sequentiallyRealizedVertexPositions(
        HexMeshBuilder.HexMesh mesh, FaceAdjacency.Adjacency adjacency,
        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments, double baselineCircumradiusBlocks,
        double minEdgeLengthBlocks, Map<Integer, Integer> committedWidthBlocksByFace, Map<Integer, Point2D> committedVertexPositions,
        long worldSeed
    ) {
        return SequentialVertexPlacement.realize(
            mesh, adjacency, assignments, baselineCircumradiusBlocks, minEdgeLengthBlocks, committedWidthBlocksByFace, committedVertexPositions, worldSeed
        );
    }

    /** See {@code SequentialVertexPlacement.processingOrderFor}'s own doc. */
    static List<Integer> processingOrderFor(Set<Integer> freeFaces, FaceAdjacency.Adjacency adjacency, Set<Integer> initiallySolved, long worldSeed) {
        return SequentialVertexPlacement.processingOrderFor(freeFaces, adjacency, initiallySolved, worldSeed);
    }

    /**
     * See {@code AchievableAreaProbe.rangeFor}'s own doc. Public (not package-private): the
     * real, load-bearing caller is {@link TriangleIdentitySizeAssignment}, now in the
     * sibling {@code topology.identity} package after this session's topology reorg.
     */
    public static AreaRange achievableAreaRangeFor(
        HexMeshBuilder.HexMesh mesh, FaceAdjacency.Adjacency adjacency, int faceIndex,
        Map<Integer, Integer> committedWidthBlocksByFace, Map<Integer, Point2D> committedVertexPositions,
        double baselineCircumradiusBlocks, double minEdgeLengthBlocks, int probeMinWidthBlocks, int probeMaxWidthBlocks
    ) {
        return AchievableAreaProbe.rangeFor(
            mesh, adjacency, faceIndex, committedWidthBlocksByFace, committedVertexPositions,
            baselineCircumradiusBlocks, minEdgeLengthBlocks, probeMinWidthBlocks, probeMaxWidthBlocks
        );
    }
}
