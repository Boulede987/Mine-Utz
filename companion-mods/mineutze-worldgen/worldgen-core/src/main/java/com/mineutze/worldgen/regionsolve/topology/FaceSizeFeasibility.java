package com.mineutze.worldgen.regionsolve.topology;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-region, per-edge computation over already-CSP-assigned sizes, run once
 * between {@link TriangleIdentitySizeAssignment}'s CSP and the (not yet
 * built) edge-displacement rasterizer. NOT a static biome-graph-only table —
 * {@code BiomeGraph.isAllowed} is already O(1) and legality never depends on
 * size, so there's no CSP-speed problem to solve here (see
 * doc/decisions/2026-08-29-hex-mesh-edge-displacement.md's corrected "Change
 * 3"). This is a real computation over THIS region's own already-assigned
 * widths, working out how much each shared edge should systematically
 * bulge one way or the other so each face's rasterized area lands close to
 * its CSP-assigned target — sizes stay approximate ("close to target"), not
 * exact, same soft-quota spirit {@link FaceFairnessQuota} already treats its
 * own quotas with.
 *
 * <p><b>Scope: within-region edges only.</b> An edge whose OTHER face isn't
 * in this call's own {@code assignments} (a hull-touching face, or one on a
 * cross-region boundary handled by {@code fixedBoundaryCells} elsewhere) is
 * skipped entirely — reconciling a bias needs BOTH faces' own desired area
 * delta, and a cross-region neighbor's target width isn't available here
 * (only its already-decided BIOME is, via {@code fixedBoundaryCells} — no
 * {@link TriangleIdentitySizeAssignment.Assignment} at all). The
 * rasterizer must supply its own default (e.g. zero bias, a straight-ish
 * seam) for any edge missing from this method's output — a real open item
 * for that phase, not silently solved here.
 */
public final class FaceSizeFeasibility {

    /** Keeps the resulting polygon simple (non-self-intersecting) regardless of how large a raw area mismatch requests — the actual feasibility bound this class produces; sizes stay approximate past this point, never exact. */
    private static final double MAX_BIAS_FRACTION_OF_EDGE_LENGTH = 0.4;

    private FaceSizeFeasibility() {
    }

    /**
     * One signed bias per within-region shared edge (see class doc for the
     * cross-region exclusion), in the same canonical direction convention
     * {@link EdgeDisplacement#displacedPolyline} expects for its own
     * {@code biasBlocks} parameter — this output can be handed straight to
     * that method, keyed by each edge's own two vertex indices.
     */
    public static Map<NodeEdge, Double> biasBlocksByEdge(
        HexMeshBuilder.HexMesh mesh, TriangleAdjacency.Adjacency adjacency,
        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments
    ) {
        Map<Integer, Double> perEdgeAreaDeltaByFace = perEdgeAreaDeltaByFace(mesh, assignments);

        Map<NodeEdge, Double> biasByEdge = new LinkedHashMap<>();
        for (Map.Entry<Integer, Set<Integer>> entry : adjacency.neighborFaceIndices().entrySet()) {
            int faceIndex = entry.getKey();
            if (!assignments.containsKey(faceIndex)) {
                continue; // hull-touching or otherwise not this call's concern
            }
            for (int neighborIndex : entry.getValue()) {
                if (neighborIndex < faceIndex || !assignments.containsKey(neighborIndex)) {
                    continue; // each unordered pair once; skip cross-region/hull edges, see class doc
                }
                biasByEdge.put(
                    sharedEdgeOf(mesh.faces().get(faceIndex), mesh.faces().get(neighborIndex)),
                    biasForSharedEdge(mesh, faceIndex, neighborIndex, perEdgeAreaDeltaByFace)
                );
            }
        }
        return Map.copyOf(biasByEdge);
    }

    private static Map<Integer, Double> perEdgeAreaDeltaByFace(
        HexMeshBuilder.HexMesh mesh, Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments
    ) {
        Map<Integer, Double> perEdgeAreaDelta = new HashMap<>();
        for (Map.Entry<Integer, TriangleIdentitySizeAssignment.Assignment> entry : assignments.entrySet()) {
            HexFace face = mesh.faces().get(entry.getKey());
            double areaError = targetAreaBlocks2(entry.getValue().widthBlocks()) - shoelaceArea(face, mesh.vertices());
            perEdgeAreaDelta.put(entry.getKey(), areaError / face.vertexIndices().size());
        }
        return perEdgeAreaDelta;
    }

    /**
     * Reconciles both faces' own per-edge desired area delta into one signed bias, in
     * {@link EdgeDisplacement}'s own canonical direction: positive bias bulges the
     * boundary toward whichever face sits on the canonical-positive-perpendicular side,
     * which SHRINKS that face and GROWS the other one (the curve eats into whichever side
     * it bulges toward) — determined here via a cross-product sign test against each
     * face's own centroid, not assumed.
     */
    private static double biasForSharedEdge(
        HexMeshBuilder.HexMesh mesh, int faceIndex, int neighborIndex, Map<Integer, Double> perEdgeAreaDeltaByFace
    ) {
        HexFace faceA = mesh.faces().get(faceIndex);
        HexFace faceB = mesh.faces().get(neighborIndex);
        NodeEdge sharedEdge = sharedEdgeOf(faceA, faceB);

        Point2D p1 = mesh.vertices().get(sharedEdge.a());
        Point2D p2 = mesh.vertices().get(sharedEdge.b());
        boolean firstIsLower = p1.x() != p2.x() ? p1.x() < p2.x() : p1.z() < p2.z();
        Point2D lower = firstIsLower ? p1 : p2;
        Point2D upper = firstIsLower ? p2 : p1;
        double length = Math.hypot(upper.x() - lower.x(), upper.z() - lower.z());

        boolean faceAIsPositiveSide = sideCross(lower, upper, centroidOf(faceA, mesh.vertices())) > 0;
        int positiveSideFace = faceAIsPositiveSide ? faceIndex : neighborIndex;
        int negativeSideFace = faceAIsPositiveSide ? neighborIndex : faceIndex;

        double reconciledAreaBias = (perEdgeAreaDeltaByFace.get(negativeSideFace) - perEdgeAreaDeltaByFace.get(positiveSideFace)) / 2.0;
        // Chord-bulge area relation for a parabolic displacement of amplitude d over a chord of length L: ΔArea ≈ (2/3)Ld, solved for d.
        double rawBias = reconciledAreaBias * 3.0 / (2.0 * length);
        double maxBias = MAX_BIAS_FRACTION_OF_EDGE_LENGTH * length;
        return Math.max(-maxBias, Math.min(maxBias, rawBias));
    }

    private static double sideCross(Point2D lower, Point2D upper, Point2D point) {
        double dx = upper.x() - lower.x();
        double dz = upper.z() - lower.z();
        return dx * (point.z() - lower.z()) - dz * (point.x() - lower.x());
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

    private static NodeEdge sharedEdgeOf(HexFace faceA, HexFace faceB) {
        Set<Integer> aVertices = new HashSet<>(faceA.vertexIndices());
        List<Integer> shared = new ArrayList<>(2);
        for (int vertexIndex : faceB.vertexIndices()) {
            if (aVertices.contains(vertexIndex)) {
                shared.add(vertexIndex);
            }
        }
        if (shared.size() != 2) {
            throw new IllegalStateException("adjacent faces must share exactly 2 vertices, found " + shared.size());
        }
        return NodeEdge.of(shared.get(0), shared.get(1));
    }

    private static double shoelaceArea(HexFace face, List<Point2D> vertices) {
        List<Integer> vertexIndices = face.vertexIndices();
        int cornerCount = vertexIndices.size();
        double sum = 0;
        for (int i = 0; i < cornerCount; i++) {
            Point2D current = vertices.get(vertexIndices.get(i));
            Point2D next = vertices.get(vertexIndices.get((i + 1) % cornerCount));
            sum += current.x() * next.z() - next.x() * current.z();
        }
        return Math.abs(sum) / 2.0;
    }

    /**
     * Treats a biome's assigned width as a regular hexagon's own corner-to-corner span
     * (matching DESIGN.md's "desert -> between 700 and 1000 blocks wide" framing against
     * the actual shape a face is realized as, one hex per biome patch - NOT a
     * cluster-of-many-small-hexes growth model) - a regular hexagon's corner-to-corner
     * span is exactly {@code 2 x circumradius}, and its area is
     * {@code (3*sqrt(3)/2) x circumradius^2}. Deliberately not routed through {@link
     * com.mineutze.worldgen.regionsolve.MacroGridConfig#cellCountForWidthBlocks}, which
     * quantizes to a macro-cell count for growth-rasterizer bookkeeping this continuous
     * area computation doesn't need.
     */
    private static double targetAreaBlocks2(int widthBlocks) {
        double circumradius = widthBlocks / 2.0;
        return (3.0 * Math.sqrt(3.0) / 2.0) * circumradius * circumradius;
    }
}
