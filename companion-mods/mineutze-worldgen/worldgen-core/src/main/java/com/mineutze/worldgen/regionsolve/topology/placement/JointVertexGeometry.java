package com.mineutze.worldgen.regionsolve.topology.placement;

import com.mineutze.worldgen.regionsolve.topology.Point2D;

import com.mineutze.worldgen.regionsolve.topology.mesh.HexMeshBuilder;

import com.mineutze.worldgen.regionsolve.topology.mesh.FaceAdjacency;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Whole-mesh raw radical-center positions, then clamped for geometric safety - the joint
 * (not sequential/per-face) reconciliation shared by {@code CalibratedVertexPlacement}'s
 * calibration loop and {@code AchievableAreaProbe}'s single-shot probes. See {@link
 * WeightedVertexPlacement} class doc for why raw-position computation and validity
 * clamping are kept as two separate concerns.
 */
final class JointVertexGeometry {

    /**
     * Cap on {@link #geometricallyValidPositions}'s fixed-point rounds. Each face
     * validates ITSELF assuming uniform scaling, but per-vertex minimum-reconciliation
     * across faces can leave different corners of the SAME face at different final scales
     * - a shape no single face's check ever validated, so it re-validates and re-shrinks
     * to a real fixed point instead of trusting one pass. Each round only shrinks
     * positions further toward the always-valid original, so this converges
     * monotonically; 20 rounds is generous headroom for a hex face's small (≤6)
     * neighborhood to settle. See DECISIONS.md 2026-09-04 "MAX_VALIDATION_ROUNDS."
     */
    private static final int MAX_VALIDATION_ROUNDS = 20;

    private JointVertexGeometry() {
    }

    /**
     * Every mesh vertex's RAW radical-center position (see {@link
     * PowerDiagramMath#radicalCenter}), then clamped for geometric safety by {@link
     * #geometricallyValidPositions} — two separate concerns: this method computes what the
     * SIZE math wants, the validity pass decides how much the GEOMETRY can tolerate.
     */
    static Map<Integer, Point2D> vertexPositionsFor(
        HexMeshBuilder.HexMesh mesh, Map<Integer, Point2D> centers, Map<Integer, Double> weightByFace,
        List<int[]> triangles, double minEdgeLengthBlocks, Map<Integer, Double> targetAreaByFace,
        Map<Integer, WeightedVertexPlacement.AreaRange> legalAreaRangeByFace, double baselineCircumradiusBlocks,
        Map<Integer, Point2D> committedVertexPositions
    ) {
        Map<Integer, Point2D> raw = new HashMap<>();
        for (int[] triangle : triangles) {
            int vertexIndex = FaceAdjacency.sharedVertexOf(mesh, triangle);
            Point2D radicalCenter = PowerDiagramMath.radicalCenter(
                centers.get(triangle[0]), weightByFace.get(triangle[0]),
                centers.get(triangle[1]), weightByFace.get(triangle[1]),
                centers.get(triangle[2]), weightByFace.get(triangle[2])
            );
            raw.put(vertexIndex, radicalCenter);
        }
        return geometricallyValidPositions(mesh, Map.copyOf(raw), minEdgeLengthBlocks, targetAreaByFace, legalAreaRangeByFace, baselineCircumradiusBlocks, committedVertexPositions);
    }

    /**
     * Per-face, per-round geometric check: no face's hexagon may become a self-intersecting
     * polygon, no edge may collapse below {@code minEdgeLengthBlocks}, AND its realized
     * area may never drop below {@code HexPolygonSafety.MIN_REALIZED_AREA_FRACTION_OF_TARGET}
     * of its target — no matter how extreme the weight mismatch. See DECISIONS.md 2026-09-03
     * for why this replaced a flat displacement-distance cap.
     *
     * <p>A vertex belongs to up to 3 faces, and its final position must be the SAME value
     * read by all of them (what makes a shared edge watertight between two faces — see
     * {@link EdgeDisplacement}). So each face independently computes the largest UNIFORM
     * scale ({@link HexPolygonSafety#maxSafeScale}) keeping its 6 corners valid, then every
     * vertex takes the SMALLEST scale demanded by any owning face — most conservative
     * requirement always wins, never averaged.
     *
     * <p>One pass isn't enough: a face validates ITSELF assuming all 6 corners scale
     * UNIFORMLY, but after per-vertex min-reconciliation, different corners of that SAME
     * face can end up at DIFFERENT final scales (driven by whichever other face shares
     * that corner) — a shape neither owning face's uniform-scale check ever tested. Fixed
     * by repeating validation-and-reshrink to a real fixed point: each round, re-check
     * every face's CURRENT shape and shrink further toward the always-valid original, only
     * as much as still needed. Since every round only moves positions closer to the
     * original undisplaced vertex, this is a monotonically tightening sequence bounded
     * below by an always-valid shape, so it converges; a round where no face needs further
     * shrinking is the fixed point. See DECISIONS.md 2026-09-04 "MAX_VALIDATION_ROUNDS" for
     * the bug that motivated this.
     *
     * <p>{@code committedVertexPositions} entries treated as immovable anchors, not
     * candidates to validate/shrink: both the "original" and "candidate" reference for
     * such a vertex is its committed position (never {@code mesh.vertices()}'s raw lattice
     * point, never {@code current}'s fresh one), so {@link HexPolygonSafety#lerp} between
     * two equal points returns that exact point regardless of scale — a committed vertex
     * can never drift, any round, any amount, while every OTHER face sharing it still
     * validates against its TRUE final shape, not a stale stand-in. This is what makes a
     * vertex committed once, by one batch, reproduced identically by every later batch
     * that touches it — see {@code DecidedVertexStore}.
     */
    static Map<Integer, Point2D> geometricallyValidPositions(
        HexMeshBuilder.HexMesh mesh, Map<Integer, Point2D> rawPositions, double minEdgeLengthBlocks,
        Map<Integer, Double> targetAreaByFace, Map<Integer, WeightedVertexPlacement.AreaRange> legalAreaRangeByFace, double baselineCircumradiusBlocks,
        Map<Integer, Point2D> committedVertexPositions
    ) {
        Map<Integer, Point2D> current = new HashMap<>(rawPositions);
        current.putAll(committedVertexPositions);
        for (int round = 0; round < MAX_VALIDATION_ROUNDS; round++) {
            Map<Integer, Double> minScaleByVertex = new HashMap<>();
            boolean anyFaceStillNeedsShrinking = false;
            for (int faceIndex = 0; faceIndex < mesh.faces().size(); faceIndex++) {
                List<Integer> vertexIndices = mesh.faces().get(faceIndex).vertexIndices();
                List<Point2D> original = new ArrayList<>();
                List<Point2D> candidate = new ArrayList<>();
                for (int vertexIndex : vertexIndices) {
                    Point2D committed = committedVertexPositions.get(vertexIndex);
                    Point2D basePosition = committed != null ? committed : mesh.vertices().get(vertexIndex);
                    original.add(basePosition);
                    candidate.add(committed != null ? committed : current.getOrDefault(vertexIndex, basePosition));
                }
                // Missing entry (no real assignment for this face) means fully
                // unconstrained target/legal-range - NaN, not a numeric default, so
                // safetyBoundsFor disables BOTH the floor and ceiling (see its own doc for
                // why a single numeric default here previously broke the ceiling side).
                double targetArea = targetAreaByFace.getOrDefault(faceIndex, Double.NaN);
                WeightedVertexPlacement.AreaRange legalAreaRange = legalAreaRangeByFace.getOrDefault(faceIndex, new WeightedVertexPlacement.AreaRange(0.0, Double.MAX_VALUE));
                HexPolygonSafety.SafetyBounds bounds = HexPolygonSafety.safetyBoundsFor(targetArea, legalAreaRange, baselineCircumradiusBlocks);
                double furtherScale = HexPolygonSafety.maxSafeScale(original, candidate, minEdgeLengthBlocks, bounds.minAreaBlocks2(), bounds.maxAreaBlocks2(), bounds.maxDisplacementBlocks());
                if (furtherScale < 1.0) {
                    anyFaceStillNeedsShrinking = true;
                }
                for (int vertexIndex : vertexIndices) {
                    if (current.containsKey(vertexIndex) && !committedVertexPositions.containsKey(vertexIndex)) {
                        minScaleByVertex.merge(vertexIndex, furtherScale, Math::min);
                    }
                }
            }

            Map<Integer, Point2D> next = new HashMap<>();
            for (Map.Entry<Integer, Point2D> entry : current.entrySet()) {
                int vertexIndex = entry.getKey();
                if (committedVertexPositions.containsKey(vertexIndex)) {
                    next.put(vertexIndex, entry.getValue());
                    continue;
                }
                double scale = minScaleByVertex.getOrDefault(vertexIndex, 1.0);
                next.put(vertexIndex, HexPolygonSafety.lerp(mesh.vertices().get(vertexIndex), entry.getValue(), scale));
            }
            current = Map.copyOf(next);
            if (!anyFaceStillNeedsShrinking) {
                break;
            }
        }
        return current;
    }
}
