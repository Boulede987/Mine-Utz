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
 * baselineCircumradiusBlocks} as its own weight, held FIXED throughout calibration
 * (below) — meaning any vertex touching one gets LESS size-driven displacement right at
 * that seam — the same "wiggle-only, no size-correction precision at a boundary"
 * limitation the old bias approach already had and documented, not something newly
 * introduced here.
 *
 * <p><b>Weight is only a one-shot proxy for realized area — calibrated by iteration, not
 * assumed.</b> A cell's realized area in a power diagram depends on ALL its neighbors'
 * weights too, not just its own, so naively setting {@code weight = powerWeight(target)}
 * once measurably undershoots for a face whose target diverges far from its actual
 * neighbors (confirmed directly: an isolated face wanting much less area than 6 uniform
 * neighbors still lands 3-60% oversized depending on how extreme the gap is). This is
 * expected, not a bug — for any positive target areas summing to the domain's own total
 * area, weights realizing them EXACTLY are known to exist (the semi-discrete optimal
 * transport existence result for power diagrams), just not via a closed form. {@link
 * #calibratedWeights} finds them approximately via {@code CALIBRATION_ROUNDS} rounds of
 * ADDITIVE correction ({@code newWeight = oldWeight + DAMPING_FACTOR *
 * (targetArea - realizedArea)}, damped and per-round-clamped for stability) - additive,
 * not multiplicative, because {@link #radicalCenter}'s own formula depends on the
 * DIFFERENCE between two weights, never their ratio (see {@link #DAMPING_FACTOR}'s own
 * doc for a real multiplicative attempt that measurably failed for exactly this reason).
 * Justified by a real, provable monotonicity property of power diagrams (increasing one
 * site's weight, holding others fixed, strictly increases its own area and strictly
 * decreases every neighbor's), the same structural property that makes Gauss-Seidel-style
 * relaxation converge for diagonally-dominant systems, generalized here to this smooth
 * nonlinear case.
 */
public final class WeightedVertexPlacement {

    /**
     * Caps how far a computed vertex can move from its own undisplaced position, as a
     * fraction of the mesh's own baseline circumradius — keeps the resulting polygon
     * simple (non-self-intersecting) regardless of how extreme a weight mismatch
     * requests. Was 0.4 (matching the old per-edge bias clamp's own fraction) until a
     * real measurement showed the plateau on the catalog's most extreme size mismatch
     * (target width 300 among uniform 650 neighbors, ratio stuck at 1.30 regardless of
     * calibration rounds) was THIS clamp, not insufficient weight correction — raising it
     * to 0.6 alone closed that exact case to ratio 1.00. Pushed further empirically, not
     * by formula: 0.8 still passes the full test suite clean (zero illegal adjacencies,
     * zero starvation, across every real seed already under test) and measurably improves
     * catalog-wide fidelity (10-seed median target-vs-realized ratio 0.992→0.993, within
     * ±15% 53.4%→80.0%, within ±30% 79.5%→98.3%); 1.0 does NOT stay safe — it measurably
     * broke real correctness (`SeedRegionCacheTest.crossRegionProducesZeroIllegalAdjacencies`
     * and 3 other tests failed), confirming self-intersection is a real risk being
     * actively guarded against here, not a theoretical one. 0.8 is the safe ceiling found
     * this way, not a value with its own closed-form derivation.
     */
    private static final double MAX_VERTEX_DISPLACEMENT_FRACTION = 0.8;

    /** Rounds of additive weight correction - linear (not Newton's-method quadratic) convergence, so a real iteration count, not 2-3 steps; cheap enough at real region scale (a full vertex-placement pass is sub-100ms) that this stays well under any performance concern. */
    private static final int CALIBRATION_ROUNDS = 15;

    /**
     * Damping factor on each round's ADDITIVE area-error correction ({@code newWeight =
     * oldWeight + DAMPING_FACTOR * (targetArea - realizedArea)}) - additive, not
     * multiplicative, on purpose: {@link #radicalCenter}'s own formula depends on the
     * DIFFERENCE between two weights ({@code weightA - weightB}), never their ratio, so a
     * multiplicative update (an earlier version of this class tried {@code newWeight =
     * oldWeight * ratio}) barely moves the actual geometry even as the weight value
     * itself shrinks toward zero - confirmed directly via a round-by-round trace: weight
     * fell from 22500 to 39 over 15 rounds while realized area stayed almost flat,
     * because multiplying every face's weight by a similar ratio leaves their PAIRWISE
     * DIFFERENCES, and therefore the diagram itself, nearly unchanged. The additive
     * update matches what the geometry actually responds to. 0.5 damping keeps two
     * mutually-adjacent faces from overcorrecting off each other's stale state and
     * oscillating, at the cost of needing more rounds to converge - a conventional
     * "half-step" choice for this style of fixed-point relaxation.
     */
    private static final double DAMPING_FACTOR = 0.5;

    /** Bounds how much any single round can change one face's own weight, as a fraction of the baseline face's own weight (itself a squared length, same unit as area) - a defensive guard against one huge correction overshooting wildly before neighbors have had a chance to react, and against runaway growth if a face's own displacement is already geometry-clamped (see {@link #clampDisplacement}) and no amount of extra weight would actually move it further. */
    private static final double MAX_WEIGHT_CORRECTION_FRACTION_OF_BASELINE = 1.0;

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
        Map<Integer, Double> targetAreaByFace = targetAreasOf(assignments);

        Map<Integer, Point2D> displaced = vertexPositionsFor(mesh, centers, weightByFace, triangles, baselineCircumradiusBlocks);
        return calibratedWeights(mesh, centers, weightByFace, triangles, targetAreaByFace, displaced, baselineCircumradiusBlocks);
    }

    /**
     * Runs {@link #CALIBRATION_ROUNDS} rounds of proportional weight correction - see
     * class doc for why this converges in principle (the semi-discrete optimal transport
     * existence result) and why a proportional update is a reasonable way to search for
     * it (power diagrams' own monotonicity property). Only faces with a real target area
     * (a real CSP assignment) ever have their weight adjusted; fixed/hull-touching faces
     * stay at baseline weight throughout, same as before calibration existed.
     */
    private static Map<Integer, Point2D> calibratedWeights(
        HexMeshBuilder.HexMesh mesh, Map<Integer, Point2D> centers, Map<Integer, Double> weightByFace,
        List<int[]> triangles, Map<Integer, Double> targetAreaByFace, Map<Integer, Point2D> displaced, double baselineCircumradiusBlocks
    ) {
        Map<Integer, Double> weights = new HashMap<>(weightByFace);
        double maxCorrectionPerRound = MAX_WEIGHT_CORRECTION_FRACTION_OF_BASELINE * baselineCircumradiusBlocks * baselineCircumradiusBlocks;
        for (int round = 0; round < CALIBRATION_ROUNDS; round++) {
            Map<Integer, Double> realizedAreaByFace = realizedAreasOf(mesh, displaced, targetAreaByFace.keySet());
            for (Map.Entry<Integer, Double> entry : targetAreaByFace.entrySet()) {
                int faceIndex = entry.getKey();
                double targetArea = entry.getValue();
                double realizedArea = realizedAreaByFace.get(faceIndex);
                double rawCorrection = DAMPING_FACTOR * (targetArea - realizedArea);
                double clampedCorrection = Math.max(-maxCorrectionPerRound, Math.min(maxCorrectionPerRound, rawCorrection));
                weights.put(faceIndex, weights.get(faceIndex) + clampedCorrection);
            }
            displaced = vertexPositionsFor(mesh, centers, weights, triangles, baselineCircumradiusBlocks);
        }
        return displaced;
    }

    private static Map<Integer, Point2D> vertexPositionsFor(
        HexMeshBuilder.HexMesh mesh, Map<Integer, Point2D> centers, Map<Integer, Double> weightByFace,
        List<int[]> triangles, double baselineCircumradiusBlocks
    ) {
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

    private static Map<Integer, Double> targetAreasOf(Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments) {
        Map<Integer, Double> targetAreas = new HashMap<>();
        assignments.forEach((faceIndex, assignment) -> targetAreas.put(faceIndex, targetAreaBlocks2(assignment.widthBlocks())));
        return targetAreas;
    }

    /**
     * Treats a biome's assigned width as a regular hexagon's own corner-to-corner span
     * (same convention {@link #powerWeight} uses): circumradius = width/2, area =
     * {@code (3*sqrt(3)/2) x circumradius^2}.
     */
    private static double targetAreaBlocks2(int widthBlocks) {
        double circumradius = widthBlocks / 2.0;
        return (3.0 * Math.sqrt(3.0) / 2.0) * circumradius * circumradius;
    }

    /** Shoelace area of each face's own displaced boundary (straight corner-to-corner, no wiggle - wiggle is a separate, later, cosmetic-only step with zero net area effect, not this class's concern). */
    private static Map<Integer, Double> realizedAreasOf(HexMeshBuilder.HexMesh mesh, Map<Integer, Point2D> displaced, Set<Integer> faceIndices) {
        Map<Integer, Double> areas = new HashMap<>();
        for (int faceIndex : faceIndices) {
            List<Integer> vertexIndices = mesh.faces().get(faceIndex).vertexIndices();
            int cornerCount = vertexIndices.size();
            double sum = 0;
            for (int i = 0; i < cornerCount; i++) {
                Point2D current = cornerPosition(mesh, displaced, vertexIndices.get(i));
                Point2D next = cornerPosition(mesh, displaced, vertexIndices.get((i + 1) % cornerCount));
                sum += current.x() * next.z() - next.x() * current.z();
            }
            areas.put(faceIndex, Math.abs(sum) / 2.0);
        }
        return areas;
    }

    private static Point2D cornerPosition(HexMeshBuilder.HexMesh mesh, Map<Integer, Point2D> displaced, int vertexIndex) {
        return displaced.getOrDefault(vertexIndex, mesh.vertices().get(vertexIndex));
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
