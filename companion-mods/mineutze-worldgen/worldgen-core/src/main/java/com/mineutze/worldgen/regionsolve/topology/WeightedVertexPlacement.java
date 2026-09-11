package com.mineutze.worldgen.regionsolve.topology;

import java.util.ArrayList;
import java.util.HashMap;
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
     * Bisection precision for {@link #maxSafeScale} — each iteration halves the search
     * interval, so 30 iterations gives roughly {@code 2^-30} (~1e-9) relative precision on
     * the returned scale, far finer than block-scale geometry needs; cheap regardless
     * since each iteration is one 6-corner polygon-simplicity check.
     */
    private static final int BISECTION_ITERATIONS = 30;

    /**
     * Cap on {@link #geometricallyValidPositions}'s own fixed-point rounds. Each face
     * validates ITSELF assuming uniform scaling, but the per-vertex minimum-reconciliation
     * across faces can leave different corners of the SAME face at different final
     * scales - a shape no single face's own check ever actually validated. Found as a
     * real bug, not a hypothetical: adding the minimum-edge-length check surfaced a real
     * "5+ cells not covered by any face" failure in {@code
     * SeedRegionCacheTest.differentRegionsProduceIndependentResults} that plain
     * simplicity alone had apparently never triggered. Fixed by re-validating and
     * re-shrinking to a real fixed point instead of trusting one independent pass - the
     * same lesson as {@link TriangleIdentitySizeAssignment}'s own binary-AC-3-wasn't-
     * enough history, at the geometry layer instead of the CSP layer. Each round only
     * ever shrinks positions further toward the always-valid original, so this converges
     * monotonically; 20 rounds is generous headroom for a hex face's own small (≤6)
     * neighborhood to settle, not a value picked to just barely clear one seed.
     */
    private static final int MAX_VALIDATION_ROUNDS = 20;

    /**
     * Floor on a face's own realized area, as a fraction of ITS OWN target area - a THIRD
     * geometric validity condition alongside simplicity and minimum edge length, added
     * for a different failure mode than either: a face whose edges are all comfortably
     * long and whose polygon is perfectly simple can still land badly undersized overall
     * (measured directly: a real 370-block-target beach face realized only 260.8 blocks,
     * ratio 0.70, roughly HALF the target AREA - not a self-intersection or a thin-edge
     * problem, just the calibration loop's own known residual, see this class's own doc
     * on weight being "only a one-shot proxy for realized area"). Unlike the other two
     * conditions (checked against an ABSOLUTE floor, since a collapsed edge or a folded
     * polygon is bad regardless of what biome it is), this one is relative to the face's
     * OWN target - a 300-block-target biome realizing 250 blocks is fine, a 1000-block
     * one realizing 250 is not, so one shared fraction (not one shared absolute number)
     * is the right shape for this specific guard. 0 disables the check entirely for a
     * face with no real target (fixed/hull-touching), same convention as the other two.
     *
     * <p><b>Must be capped at the baseline hexagon's own area, not applied raw.</b> Found
     * as a real bug via a pre-existing test breaking: for an OVERSIZED target (bigger
     * than baseline), {@code 0.7 x targetArea} can exceed the area of the ORIGINAL
     * undisplaced hexagon itself - meaning even {@code t=0} (always valid for the other
     * two conditions) fails this one, breaking the binary search's whole foundation.
     * Worse, since area only GROWS as {@code t} increases toward an oversized target,
     * the fix this condition would want (grow more) is the OPPOSITE of what shrinking
     * toward original does - confirmed directly: an oversized face got ZERO growth at
     * all once this went uncapped, since the search kept shrinking (the right move for
     * simplicity/min-edge) while that only ever made the area gap worse. Capping the
     * floor at the baseline area (see {@link #geometricallyValidPositions}'s own call
     * site) restores "always valid at t=0" for every case: an oversized target's floor
     * becomes trivially satisfied by baseline area (correctly permissive, doesn't block
     * growth), while an undersized target's floor stays real and below baseline.
     */
    private static final double MIN_REALIZED_AREA_FRACTION_OF_TARGET = 0.7;

    /**
     * A FOURTH, absolute condition: no vertex may ever move farther than this fraction of
     * the mesh's own baseline circumradius from its own undisplaced lattice position - a
     * global safety net the other three conditions don't provide, since each of them only
     * ever examines ONE face's own 6 corners in isolation. A face can be perfectly simple,
     * have every edge comfortably long, and have plenty of area, while still having grown
     * so large that it physically reaches into a DISTANT, non-mesh-adjacent face's own
     * territory - none of the other three checks can see that, because none of them ever
     * looks at any OTHER face's geometry.
     *
     * <p>This is a real, measured, previously-reopened gap, not a defensive guess: the
     * original design had exactly this cap (as the sole clamp, before the per-face
     * adaptive checks existed), empirically swept to 0.8 as the largest value that stayed
     * clean of real illegal adjacencies (1.0 measurably broke correctness - illegal
     * adjacencies reappeared). When the per-face simplicity/min-edge/min-area conditions
     * were built, this absolute cap was dropped entirely, on the assumption the new
     * LOCAL checks would be enough - they are not, for exactly the reason above, and this
     * is very likely the real root cause of the face-polygon-overlap bug tracked at
     * {@code ModBiomeGraphTest.overworldHasAKnownFacePolygonOverlapBug} (worldSeed=2031):
     * confirmed directly by re-adding this cap and re-testing that exact seed.
     *
     * <p>0.8 is reused here, not re-derived, because the underlying question is the same
     * one already answered empirically for the flat-clamp era: how far can ANY vertex
     * move before it risks reaching territory it has no business reaching. The other
     * three conditions still do real, independent work (this cap alone never guaranteed
     * simplicity, minimum edge length, or minimum area - each was found as its own real,
     * separate bug with its own separate fix).
     */
    private static final double MAX_VERTEX_DISPLACEMENT_FRACTION = 0.8; // Tried raising to 0.9 (2026-09-06): alone it's safe (measured real improvement, 384->349 size-fidelity violations, no illegal-adjacency/starvation regression) - but COMBINED with pickWidth's baseline-bias (see TriangleIdentitySizeAssignment.SIZE_PICK_BASELINE_BIAS_EXPONENT), it breaks TriangleFieldGeometryRealizationTest.clustersAgreeRegardlessOfWhichQueryPointIsSolvedFirst - a real order-dependence regression neither change causes alone. Reverted to the historically-proven-safe 0.8 pending further investigation of that interaction - do not raise this again without re-testing that exact combination.

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

    /** Bounds how much any single round can change one face's own weight, as a fraction of the baseline face's own weight (itself a squared length, same unit as area) - a defensive guard against one huge correction overshooting wildly before neighbors have had a chance to react, and against runaway growth if a face's own displacement is already geometry-clamped (see {@link #geometricallyValidPositions}) and no amount of extra weight would actually move it further. */
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
     *
     * <p>Equivalent to calling the 7-argument overload with no already-decided context —
     * every face falls back to {@code baselineCircumradiusBlocks} exactly as before this
     * class became store-aware. Kept so every existing caller/test compiles unchanged.
     */
    public static Map<Integer, Point2D> displacedVertexPositions(
        HexMeshBuilder.HexMesh mesh, TriangleAdjacency.Adjacency adjacency,
        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments, double baselineCircumradiusBlocks,
        double minEdgeLengthBlocks
    ) {
        return displacedVertexPositions(mesh, adjacency, assignments, baselineCircumradiusBlocks, minEdgeLengthBlocks, Map.of(), Map.of());
    }

    /**
     * Store-aware overload: {@code committedWidthBlocksByFace} supplies a REAL width for a
     * face that has no {@code assignments} entry this batch because it was already decided
     * by an earlier batch (via {@code DecidedFaceStore}) — used in place of the generic
     * {@code baselineCircumradiusBlocks} fallback, and (like every unassigned face) held
     * FIXED throughout calibration, never recalibrated. This is the direct fix for the
     * cross-region seam bug: two batches independently computing the same shared vertex
     * used to feed a DIFFERENT weight for whichever neighbor face wasn't their own free
     * assignment (baseline placeholder vs. the neighbor's own real chosen width),
     * producing two different radical centers for what should be one physical point.
     *
     * <p>{@code committedVertexPositions} supplies a vertex's own FINAL position, already
     * committed by an earlier batch (via {@code DecidedVertexStore}) — treated as an
     * immovable anchor: used as-is for every face that owns it (so neighboring, still-fresh
     * corners validate against its TRUE final shape), never recomputed or reshrunk. Combined
     * with the fixed-weight behavior above, this guarantees a vertex committed once is
     * reproduced identically by every later batch that touches it, closing the
     * watertightness gap at its actual root instead of only removing the region-shaped
     * partition that used to make it visible.
     */
    public static Map<Integer, Point2D> displacedVertexPositions(
        HexMeshBuilder.HexMesh mesh, TriangleAdjacency.Adjacency adjacency,
        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments, double baselineCircumradiusBlocks,
        double minEdgeLengthBlocks, Map<Integer, Integer> committedWidthBlocksByFace, Map<Integer, Point2D> committedVertexPositions
    ) {
        Map<Integer, Point2D> centers = centersOf(mesh);
        Map<Integer, Double> weightByFace = weightsOf(mesh, assignments, committedWidthBlocksByFace, baselineCircumradiusBlocks);
        List<int[]> triangles = adjacency.mutuallyAdjacentTriples(centers.keySet());
        Map<Integer, Double> targetAreaByFace = targetAreasOf(assignments);

        Map<Integer, Point2D> displaced = vertexPositionsFor(
            mesh, centers, weightByFace, triangles, minEdgeLengthBlocks, targetAreaByFace, baselineCircumradiusBlocks, committedVertexPositions
        );
        return calibratedWeights(
            mesh, centers, weightByFace, triangles, targetAreaByFace, displaced, baselineCircumradiusBlocks, minEdgeLengthBlocks, committedVertexPositions
        );
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
        List<int[]> triangles, Map<Integer, Double> targetAreaByFace, Map<Integer, Point2D> displaced,
        double baselineCircumradiusBlocks, double minEdgeLengthBlocks, Map<Integer, Point2D> committedVertexPositions
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
            displaced = vertexPositionsFor(mesh, centers, weights, triangles, minEdgeLengthBlocks, targetAreaByFace, baselineCircumradiusBlocks, committedVertexPositions);
        }
        return displaced;
    }

    /**
     * Every mesh vertex's RAW radical-center position (see {@link #radicalCenter}), then
     * clamped for geometric safety by {@link #geometricallyValidPositions} — two separate
     * concerns kept separate: this method computes what the SIZE math wants, the validity
     * pass decides how much of that the GEOMETRY can actually tolerate.
     */
    private static Map<Integer, Point2D> vertexPositionsFor(
        HexMeshBuilder.HexMesh mesh, Map<Integer, Point2D> centers, Map<Integer, Double> weightByFace,
        List<int[]> triangles, double minEdgeLengthBlocks, Map<Integer, Double> targetAreaByFace, double baselineCircumradiusBlocks,
        Map<Integer, Point2D> committedVertexPositions
    ) {
        Map<Integer, Point2D> raw = new HashMap<>();
        for (int[] triangle : triangles) {
            int vertexIndex = TriangleAdjacency.sharedVertexOf(mesh, triangle);
            Point2D radicalCenter = radicalCenter(
                centers.get(triangle[0]), weightByFace.get(triangle[0]),
                centers.get(triangle[1]), weightByFace.get(triangle[1]),
                centers.get(triangle[2]), weightByFace.get(triangle[2])
            );
            raw.put(vertexIndex, radicalCenter);
        }
        return geometricallyValidPositions(mesh, Map.copyOf(raw), minEdgeLengthBlocks, targetAreaByFace, baselineCircumradiusBlocks, committedVertexPositions);
    }

    /**
     * Replaces a single flat displacement-distance cap with a per-face, per-round
     * geometric check: no face's own hexagon is ever allowed to become a self-intersecting
     * polygon, no single edge of it is ever allowed to collapse below {@code
     * minEdgeLengthBlocks}, AND its own realized area is never allowed to drop below
     * {@link #MIN_REALIZED_AREA_FRACTION_OF_TARGET} of its own target — no matter how
     * extreme the weight mismatch driving it. A flat
     * distance cap picked one number for the whole mesh (safe only because it was small
     * enough for the worst case seen so far); this instead asks, for each face, "how far
     * can THIS face's own 6 corners move before its own shape breaks?" — so an easy,
     * uncrowded face can move farther than a flat cap would ever allow, while a genuinely
     * tight face is held back exactly as far as its own real geometry requires.
     *
     * <p>The minimum-edge-length half of this check exists because self-intersection alone
     * is NOT enough: a face can shrink one of its own edges toward zero length while
     * staying perfectly simple the whole way there (two adjacent corners merging is not a
     * crossing). Measured directly against the real 48-biome catalog before this existed:
     * a 30-seed sweep found realized edge lengths as low as {@code 7e-12} blocks (a
     * literal point, not a thin sliver) and 95 real illegal biome adjacencies in {@code
     * com.mineutze.worldgen.regionsolve.RegionIntegrityValidator}'s own pixel-grid check —
     * a buffer face squeezed thinner
     * than one macro cell contains zero cell centers there, so the rasterizer's
     * point-in-polygon test skips it locally and the two faces on either side of it end up
     * pixel-adjacent with nothing sampled in between. This can never happen at a single
     * shared mesh vertex between two illegal-to-touch biomes (hex vertex-degree-3 means
     * any 2 faces meeting a third at one vertex are ALSO mesh-adjacent to each other, so
     * the CSP already forbids an illegal pair from ever sharing one there) — it happens
     * along a STRETCH of a legal buffer face's own boundary instead, which no legality
     * check (graph-level or self-intersection-level) was ever positioned to catch.
     *
     * <p>A vertex belongs to up to 3 faces at once, and its final position must be the
     * SAME value read by all of them (this is what makes a shared edge watertight between
     * two faces — see {@link EdgeDisplacement}'s own doc). So each face independently
     * computes the largest UNIFORM scale ({@link #maxSafeScale}) that keeps its own 6
     * corners valid, then every vertex takes the SMALLEST scale demanded by any face that
     * owns it — the most conservative requirement always wins, never averaged.
     *
     * <p>One pass of that is NOT enough on its own: a face validates ITSELF assuming all 6
     * of its own corners scale UNIFORMLY together, but after per-vertex min-reconciliation,
     * different corners of that SAME face can end up at DIFFERENT final scales (each driven
     * by whichever other face shares that specific corner) — a shape neither owning face's
     * own uniform-scale check ever actually tested. Found as a real bug: adding the
     * minimum-edge-length check (not simplicity alone, which apparently stayed lucky)
     * surfaced a genuine "cells not covered by any face" failure from exactly this gap.
     * Fixed by repeating validation-and-reshrink to a real fixed point: each round,
     * re-check every face's CURRENT (possibly non-uniformly-shrunk) shape and shrink
     * further, toward the always-valid original, only as much as still needed. Since every
     * round only ever moves positions closer to the original undisplaced vertex (never
     * farther), this is a monotonically tightening sequence bounded below by a shape
     * that's always valid, so it converges; a round where no face needed further
     * shrinking is the fixed point, and the loop stops there instead of always spending
     * the full {@link #MAX_VALIDATION_ROUNDS}.
     */
    /**
     * {@code committedVertexPositions} entries are treated as immovable anchors, not
     * candidates to validate/shrink: both the "original" and "candidate" reference used for
     * such a vertex is its own committed position (never {@code mesh.vertices()}'s raw
     * lattice point, never {@code current}'s freshly-computed one), so {@link #lerp} between
     * two equal points returns that exact point regardless of scale — a committed vertex
     * can never drift, at any round, by any amount, while every OTHER face that shares it
     * still validates against its TRUE final shape instead of a stale or approximate stand-
     * in. This is what makes a vertex committed once, by one batch, reproduced identically
     * by every later batch that touches it — see {@link DecidedVertexStore}'s own doc.
     */
    private static Map<Integer, Point2D> geometricallyValidPositions(
        HexMeshBuilder.HexMesh mesh, Map<Integer, Point2D> rawPositions, double minEdgeLengthBlocks,
        Map<Integer, Double> targetAreaByFace, double baselineCircumradiusBlocks, Map<Integer, Point2D> committedVertexPositions
    ) {
        double baselineAreaBlocks2 = (3.0 * Math.sqrt(3.0) / 2.0) * baselineCircumradiusBlocks * baselineCircumradiusBlocks;
        double maxDisplacementBlocks = MAX_VERTEX_DISPLACEMENT_FRACTION * baselineCircumradiusBlocks;
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
                double minAreaBlocks2 = Math.min(
                    targetAreaByFace.getOrDefault(faceIndex, 0.0) * MIN_REALIZED_AREA_FRACTION_OF_TARGET, baselineAreaBlocks2
                );
                double furtherScale = maxSafeScale(original, candidate, minEdgeLengthBlocks, minAreaBlocks2, maxDisplacementBlocks);
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
                next.put(vertexIndex, lerp(mesh.vertices().get(vertexIndex), entry.getValue(), scale));
            }
            current = Map.copyOf(next);
            if (!anyFaceStillNeedsShrinking) {
                break;
            }
        }
        return current;
    }

    /**
     * The largest {@code t} in {@code [0, 1]} such that moving every one of
     * {@code original}'s corners {@code t} of the way toward its matching {@code candidate}
     * corner keeps the resulting polygon {@link #isGeometricallyValid valid} (simple AND
     * no edge shorter than {@code minEdgeLengthBlocks}). Binary search, not a closed form —
     * there is no formula for "how far is too far" once more than one corner has moved at
     * once. Assumes validity is monotonic in {@code t} (true at {@code t=0}, since the
     * undisplaced regular hexagon is always simple and every one of its edges equals the
     * mesh's own baseline circumradius exactly; breaks past some threshold, never flips
     * back to valid beyond that) — a real assumption about this specific deformation (all
     * corners moving continuously outward from a valid starting shape), not a general
     * truth about arbitrary polygons. {@code minEdgeLengthBlocks} must stay below the
     * baseline circumradius for this base case to hold — the caller's own responsibility,
     * same as {@code MAX_VERTEX_DISPLACEMENT_FRACTION} was before it.
     */
    private static double maxSafeScale(
        List<Point2D> original, List<Point2D> candidate, double minEdgeLengthBlocks, double minAreaBlocks2, double maxDisplacementBlocks
    ) {
        if (isGeometricallyValid(original, lerpAll(original, candidate, 1.0), minEdgeLengthBlocks, minAreaBlocks2, maxDisplacementBlocks)) {
            return 1.0;
        }
        double lowSafe = 0.0;
        double highUnsafe = 1.0;
        for (int iteration = 0; iteration < BISECTION_ITERATIONS; iteration++) {
            double mid = (lowSafe + highUnsafe) / 2.0;
            if (isGeometricallyValid(original, lerpAll(original, candidate, mid), minEdgeLengthBlocks, minAreaBlocks2, maxDisplacementBlocks)) {
                lowSafe = mid;
            } else {
                highUnsafe = mid;
            }
        }
        return lowSafe;
    }

    private static boolean isGeometricallyValid(
        List<Point2D> original, List<Point2D> corners, double minEdgeLengthBlocks, double minAreaBlocks2, double maxDisplacementBlocks
    ) {
        return isSimplePolygon(corners) && hasMinimumEdgeLength(corners, minEdgeLengthBlocks) && hasMinimumArea(corners, minAreaBlocks2)
            && hasBoundedDisplacement(original, corners, maxDisplacementBlocks);
    }

    /** Each corner checked against its OWN original position - a global cap independent of the other 5 corners' own behavior, see {@link #MAX_VERTEX_DISPLACEMENT_FRACTION}'s own doc for why the other three conditions can't provide this on their own. */
    private static boolean hasBoundedDisplacement(List<Point2D> original, List<Point2D> corners, double maxDisplacementBlocks) {
        for (int i = 0; i < original.size(); i++) {
            if (Math.hypot(corners.get(i).x() - original.get(i).x(), corners.get(i).z() - original.get(i).z()) > maxDisplacementBlocks) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasMinimumEdgeLength(List<Point2D> corners, double minEdgeLengthBlocks) {
        int cornerCount = corners.size();
        for (int i = 0; i < cornerCount; i++) {
            Point2D current = corners.get(i);
            Point2D next = corners.get((i + 1) % cornerCount);
            if (Math.hypot(current.x() - next.x(), current.z() - next.z()) < minEdgeLengthBlocks) {
                return false;
            }
        }
        return true;
    }

    /** Shoelace area of this candidate hexagon against {@code minAreaBlocks2} - see {@link #MIN_REALIZED_AREA_FRACTION_OF_TARGET}'s own doc for why this is a per-face RELATIVE floor, not an absolute one like the other two conditions. */
    private static boolean hasMinimumArea(List<Point2D> corners, double minAreaBlocks2) {
        int cornerCount = corners.size();
        double sum = 0;
        for (int i = 0; i < cornerCount; i++) {
            Point2D current = corners.get(i);
            Point2D next = corners.get((i + 1) % cornerCount);
            sum += current.x() * next.z() - next.x() * current.z();
        }
        return Math.abs(sum) / 2.0 >= minAreaBlocks2;
    }

    private static List<Point2D> lerpAll(List<Point2D> original, List<Point2D> candidate, double t) {
        List<Point2D> result = new ArrayList<>(original.size());
        for (int i = 0; i < original.size(); i++) {
            result.add(lerp(original.get(i), candidate.get(i), t));
        }
        return result;
    }

    private static Point2D lerp(Point2D from, Point2D to, double t) {
        return new Point2D(from.x() + (to.x() - from.x()) * t, from.z() + (to.z() - from.z()) * t);
    }

    /**
     * True if no two of this polygon's own NON-ADJACENT edges cross — adjacent edges
     * (consecutive corners) always legitimately share an endpoint and are never checked
     * against each other. Plain segment-intersection over every non-adjacent edge pair;
     * with only 6 corners this is a handful of checks, not a performance concern.
     */
    private static boolean isSimplePolygon(List<Point2D> corners) {
        int cornerCount = corners.size();
        for (int i = 0; i < cornerCount; i++) {
            Point2D edgeAStart = corners.get(i);
            Point2D edgeAEnd = corners.get((i + 1) % cornerCount);
            for (int j = i + 2; j < cornerCount; j++) {
                if (i == 0 && j == cornerCount - 1) {
                    continue; // edge (n-1, 0) is adjacent to edge (0, 1), wrapping around
                }
                Point2D edgeBStart = corners.get(j);
                Point2D edgeBEnd = corners.get((j + 1) % cornerCount);
                if (segmentsProperlyIntersect(edgeAStart, edgeAEnd, edgeBStart, edgeBEnd)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Standard orientation-based proper-crossing test: true only when each segment strictly straddles the other's line (shared or touching endpoints are NOT a crossing). */
    private static boolean segmentsProperlyIntersect(Point2D a1, Point2D a2, Point2D b1, Point2D b2) {
        double d1 = orientation(b1, b2, a1);
        double d2 = orientation(b1, b2, a2);
        double d3 = orientation(a1, a2, b1);
        double d4 = orientation(a1, a2, b2);
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0));
    }

    private static double orientation(Point2D a, Point2D b, Point2D c) {
        return (b.x() - a.x()) * (c.z() - a.z()) - (b.z() - a.z()) * (c.x() - a.x());
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

    /**
     * A face with a real {@code assignments} entry (freshly CSP-solved this batch) always
     * wins; otherwise a real, already-committed width (from {@code DecidedFaceStore}, an
     * earlier batch's own decision for this same physical face) wins over the generic
     * {@code baselineCircumradiusBlocks} placeholder — the fix for the seam bug documented
     * on the store-aware {@code displacedVertexPositions} overload above. A face with
     * neither (a true hull-touching face with no context at all) still falls back to
     * baseline, unchanged from before this class became store-aware.
     */
    private static Map<Integer, Double> weightsOf(
        HexMeshBuilder.HexMesh mesh, Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments,
        Map<Integer, Integer> committedWidthBlocksByFace, double baselineCircumradiusBlocks
    ) {
        double baselineWeight = baselineCircumradiusBlocks * baselineCircumradiusBlocks;
        Map<Integer, Double> weights = new HashMap<>();
        for (int faceIndex = 0; faceIndex < mesh.faces().size(); faceIndex++) {
            TriangleIdentitySizeAssignment.Assignment assignment = assignments.get(faceIndex);
            if (assignment != null) {
                weights.put(faceIndex, powerWeight(assignment.widthBlocks()));
                continue;
            }
            Integer committedWidth = committedWidthBlocksByFace.get(faceIndex);
            weights.put(faceIndex, committedWidth != null ? powerWeight(committedWidth) : baselineWeight);
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
}
