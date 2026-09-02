package com.mineutze.worldgen.regionsolve.topology;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-region computation over already-CSP-assigned sizes, run once between {@link
 * TriangleIdentitySizeAssignment}'s CSP and the edge-displacement rasterizer. Computes a
 * NEW position for every mesh vertex directly from each face's own target size, via a
 * power diagram (weighted Voronoi diagram) over the mesh's own face centers — replaces
 * the old per-edge bias negotiated between two neighbors (formerly {@code
 * FaceSizeFeasibility}, renamed because its whole mechanism changed, not just its
 * tuning): that approach had a real, structural dead end whenever two adjacent faces
 * wanted the same shrink/grow direction, since a shared edge genuinely can't satisfy
 * both sides of a single wall at once — measured directly against the real 48-biome
 * catalog: same-tier neighbors are 51% of all real legal pairs, and small tier-1 biomes
 * commonly landed 40-80%+ oversized as a result.
 *
 * <p><b>The core idea, and why it has no same-direction dead end.</b> Give every face's
 * own hex center a WEIGHT derived from its target width ({@link #powerWeight}). The
 * boundary between two faces in a power diagram is a straight line determined ENTIRELY
 * by comparing the two weights' MAGNITUDES — there is no "which direction does my
 * neighbor want" concept anywhere in the math, so two same-tier neighbors with
 * different chosen widths still get a correctly proportioned boundary; the old
 * formula's "reconciled delta is exactly zero when both sides want the same direction"
 * degeneracy simply does not exist here.
 *
 * <p><b>Vertex consistency is a theorem, not an engineering trick.</b> For any 3
 * non-collinear weighted points (never collinear for 3 mutually-adjacent hex face
 * centers — see {@link #radicalCenter}), their three pairwise boundary lines always
 * meet at one common point — the RADICAL CENTER, a classical result in circle geometry.
 * That point is exactly what this class computes per mesh vertex, replacing the raw,
 * undisplaced hex vertex {@link HexMeshBuilder} produced. When every face has an
 * IDENTICAL weight (uniform sizes, nothing to correct), the radical center reduces
 * exactly to the ordinary circumcenter of the three face centers — which, in a regular
 * hex lattice, is exactly the original undisplaced vertex position (verified directly:
 * a regular hexagon's vertex is equidistant from the 3 hex centers meeting there). So
 * this construction provably subsumes the old "nothing to correct" baseline as a
 * special case, not just approximates it.
 *
 * <p><b>Scope: real weights only where a real assignment exists.</b> A face with no
 * {@link TriangleIdentitySizeAssignment.Assignment} (a fixed cross-region face — its own
 * real chosen width was never tracked across regions, only its biome; or a
 * hull-touching face with no assignment at all) falls back to {@code
 * baselineCircumradiusBlocks} as its own weight, meaning any vertex touching one gets
 * LESS size-driven displacement right at that seam — the same "wiggle-only, no
 * size-correction precision at a boundary" limitation the old bias approach already had
 * and documented, not something newly introduced here.
 */
public final class WeightedVertexPlacement {

    /**
     * Caps how far a computed vertex can move from its own undisplaced position, as a
     * fraction of the mesh's own baseline circumradius — keeps the resulting polygon
     * simple (non-self-intersecting) regardless of how extreme a weight mismatch
     * requests. Same 0.4 fraction the old per-edge bias clamp used — a regular hexagon's
     * own side length equals its circumradius exactly, so the two bounds are directly
     * comparable, not an unrelated new number.
     */
    private static final double MAX_VERTEX_DISPLACEMENT_FRACTION = 0.4;

    private WeightedVertexPlacement() {
    }

    /**
     * One displaced position per mesh vertex reachable from a real mesh-vertex triple,
     * keyed by vertex index into {@code mesh.vertices()} — directly usable in place of a
     * raw {@code mesh.vertices()} lookup wherever a face's own corner position is needed
     * downstream. A vertex whose 3 mutually-adjacent faces can't be found at all (a true
     * outer-hull vertex, structurally expected at the mesh's own boundary — see {@link
     * TriangleAdjacency}'s own doc) has no entry here; callers fall back to the raw
     * position for those, matching how hull-touching faces are already excluded from
     * CSP assignment without error elsewhere in this pipeline.
     */
    public static Map<Integer, Point2D> displacedVertexPositions(
        HexMeshBuilder.HexMesh mesh, TriangleAdjacency.Adjacency adjacency,
        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments, double baselineCircumradiusBlocks
    ) {
        Map<Integer, Point2D> centers = centersOf(mesh);
        Map<Integer, Double> weightByFace = weightsOf(mesh, assignments, baselineCircumradiusBlocks);

        List<int[]> triangles = adjacency.mutuallyAdjacentTriples(centers.keySet());
        Map<Integer, Point2D> displaced = new HashMap<>();
        for (int[] triangle : triangles) {
            int vertexIndex = sharedVertexOf(mesh, triangle);
            Point2D original = mesh.vertices().get(vertexIndex);
            Point2D radicalCenter = radicalCenter(
                centers.get(triangle[0]), weightByFace.get(triangle[0]),
                centers.get(triangle[1]), weightByFace.get(triangle[1]),
                centers.get(triangle[2]), weightByFace.get(triangle[2])
            );
            displaced.put(vertexIndex, clampDisplacement(original, radicalCenter, baselineCircumradiusBlocks));
        }
        return Map.copyOf(displaced);
    }

    private static Map<Integer, Point2D> centersOf(HexMeshBuilder.HexMesh mesh) {
        Map<Integer, Point2D> centers = new HashMap<>();
        for (int faceIndex = 0; faceIndex < mesh.faces().size(); faceIndex++) {
            centers.put(faceIndex, centroidOf(mesh.faces().get(faceIndex), mesh.vertices()));
        }
        return centers;
    }

    /** A regular hex face's own centroid IS its axial lattice center exactly (the mesh is still perfectly regular/undisplaced at this point - displacement is this class's own output, not an input). */
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

    private static Map<Integer, Double> weightsOf(
        HexMeshBuilder.HexMesh mesh, Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments, double baselineCircumradiusBlocks
    ) {
        double baselineWeight = baselineCircumradiusBlocks * baselineCircumradiusBlocks;
        Map<Integer, Double> weights = new HashMap<>();
        for (int faceIndex = 0; faceIndex < mesh.faces().size(); faceIndex++) {
            TriangleIdentitySizeAssignment.Assignment assignment = assignments.get(faceIndex);
            weights.put(faceIndex, assignment != null ? powerWeight(assignment.widthBlocks()) : baselineWeight);
        }
        return weights;
    }

    /**
     * Power-diagram weight for a face targeting {@code widthBlocks} — a hex's assigned
     * width treated as a regular hexagon's own corner-to-corner span (same convention
     * this codebase already uses throughout, e.g. the old {@code targetAreaBlocks2}):
     * circumradius = width/2, weight = circumradius² — the standard power-diagram weight
     * for a "circle" of that radius.
     */
    private static double powerWeight(int widthBlocks) {
        double circumradius = widthBlocks / 2.0;
        return circumradius * circumradius;
    }

    /** The one mesh-vertex index shared by all 3 faces in a real vertex-triangle — guaranteed to exist and be unique by the hex mesh's own vertex-degree-3 property. */
    private static int sharedVertexOf(HexMeshBuilder.HexMesh mesh, int[] triangle) {
        Set<Integer> shared = new HashSet<>(mesh.faces().get(triangle[0]).vertexIndices());
        shared.retainAll(mesh.faces().get(triangle[1]).vertexIndices());
        shared.retainAll(mesh.faces().get(triangle[2]).vertexIndices());
        if (shared.size() != 1) {
            throw new IllegalStateException(
                "WeightedVertexPlacement: 3 mutually-adjacent faces " + Arrays.toString(triangle) + " share "
                    + shared.size() + " vertice(s), expected exactly 1 - not a valid hex mesh vertex triple"
            );
        }
        return shared.iterator().next();
    }

    /**
     * The radical center of 3 weighted points — the unique point X with equal "power
     * distance" ({@code |X-P|² - weight}) to all three, found by solving the 2x2 linear
     * system that falls out of subtracting pairs of the power-distance equalities
     * (cancels the quadratic {@code |X|²} term, the same structure as an ordinary
     * circumcenter computation, generalized with weights). When all three weights are
     * equal this reduces exactly to the ordinary circumcenter — see class doc for why
     * that's the correct "nothing to correct" baseline, not a coincidence.
     */
    private static Point2D radicalCenter(Point2D a, double weightA, Point2D b, double weightB, Point2D c, double weightC) {
        double bx = b.x() - a.x();
        double bz = b.z() - a.z();
        double cx = c.x() - a.x();
        double cz = c.z() - a.z();
        double magA = a.x() * a.x() + a.z() * a.z();
        double magB = b.x() * b.x() + b.z() * b.z();
        double magC = c.x() * c.x() + c.z() * c.z();
        double rhsB = (weightA - weightB + magB - magA) / 2.0;
        double rhsC = (weightA - weightC + magC - magA) / 2.0;

        double determinant = bx * cz - bz * cx;
        if (Math.abs(determinant) < 1e-9) {
            throw new IllegalStateException(
                "WeightedVertexPlacement: face centers " + a + ", " + b + ", " + c + " are collinear - not a "
                    + "valid hex mesh vertex (every real vertex has 3 non-collinear face centers around it)"
            );
        }
        double x = (rhsB * cz - bz * rhsC) / determinant;
        double z = (bx * rhsC - rhsB * cx) / determinant;
        return new Point2D(x, z);
    }

    private static Point2D clampDisplacement(Point2D original, Point2D displaced, double baselineCircumradiusBlocks) {
        double dx = displaced.x() - original.x();
        double dz = displaced.z() - original.z();
        double distance = Math.hypot(dx, dz);
        double maxDistance = MAX_VERTEX_DISPLACEMENT_FRACTION * baselineCircumradiusBlocks;
        if (distance <= maxDistance) {
            return displaced;
        }
        double scale = maxDistance / distance;
        return new Point2D(original.x() + dx * scale, original.z() + dz * scale);
    }
}
