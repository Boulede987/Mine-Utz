package com.mineutze.worldgen.regionsolve.topology.placement;

import com.mineutze.worldgen.regionsolve.topology.Point2D;

import com.mineutze.worldgen.regionsolve.topology.mesh.HexMeshBuilder;

import com.mineutze.worldgen.regionsolve.topology.identity.TriangleIdentitySizeAssignment;

import com.mineutze.worldgen.regionsolve.topology.mesh.FaceAdjacency;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The export/measurement-only batch path behind {@code
 * WeightedVertexPlacement.displacedVertexPositions} — see that method for why it isn't
 * the shipped mod's live path ({@code SequentialVertexPlacement} is). Jointly calibrates
 * every free face's weight at once, over {@link #CALIBRATION_ROUNDS} rounds, not
 * committing one face at a time.
 */
final class CalibratedVertexPlacement {

    /** Rounds of additive weight correction - linear (not Newton's-method quadratic) convergence, so a real iteration count, not 2-3 steps; cheap at real region scale (full vertex-placement pass sub-100ms), well under any performance concern. */
    private static final int CALIBRATION_ROUNDS = 15;

    /**
     * Damping factor on each round's ADDITIVE area-error correction ({@code newWeight =
     * oldWeight + DAMPING_FACTOR * (targetArea - realizedArea)}) - additive, not
     * multiplicative on purpose: {@link PowerDiagramMath#radicalCenter}'s formula depends
     * on the DIFFERENCE between two weights ({@code weightA - weightB}), never their
     * ratio, so a multiplicative update barely moves the geometry as weight shrinks toward
     * zero (see DECISIONS.md 2026-09-02 "Additive weight correction" for the measured
     * multiplicative failure). 0.5 damping keeps two mutually-adjacent faces from
     * overcorrecting off each other's stale state and oscillating, at the cost of more
     * rounds to converge.
     */
    private static final double DAMPING_FACTOR = 0.5;

    /** Bounds how much any single round can change one face's weight, as a fraction of baseline face weight (a squared length, same unit as area) - defensive guard against one huge correction overshooting before neighbors react, and against runaway growth if a face's displacement is already geometry-clamped (see {@link JointVertexGeometry#geometricallyValidPositions}) and no extra weight would move it further. */
    private static final double MAX_WEIGHT_CORRECTION_FRACTION_OF_BASELINE = 1.0;

    private CalibratedVertexPlacement() {
    }

    static Map<Integer, Point2D> displace(
        HexMeshBuilder.HexMesh mesh, FaceAdjacency.Adjacency adjacency,
        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments, double baselineCircumradiusBlocks,
        double minEdgeLengthBlocks, Map<Integer, Integer> committedWidthBlocksByFace, Map<Integer, Point2D> committedVertexPositions
    ) {
        Map<Integer, Point2D> centers = MeshFaceGeometry.centersOf(mesh);
        Map<Integer, Double> weightByFace = MeshFaceGeometry.weightsOf(mesh, assignments, committedWidthBlocksByFace, baselineCircumradiusBlocks);
        List<int[]> triangles = adjacency.mutuallyAdjacentFaceTriples(centers.keySet());
        Map<Integer, Double> targetAreaByFace = MeshFaceGeometry.targetAreasOf(assignments);
        Map<Integer, WeightedVertexPlacement.AreaRange> legalAreaRangeByFace = MeshFaceGeometry.legalAreaRangesOf(assignments);

        Map<Integer, Point2D> displaced = JointVertexGeometry.vertexPositionsFor(
            mesh, centers, weightByFace, triangles, minEdgeLengthBlocks, targetAreaByFace, legalAreaRangeByFace, baselineCircumradiusBlocks, committedVertexPositions
        );
        Map<Integer, Integer> pairPartnerByFace = pairPartnersOf(assignments);
        return calibratedWeights(
            mesh, centers, weightByFace, triangles, targetAreaByFace, legalAreaRangeByFace, displaced, baselineCircumradiusBlocks,
            minEdgeLengthBlocks, committedVertexPositions, pairPartnerByFace
        );
    }

    /**
     * Reads pairing straight off each {@code Assignment} (see its own doc) - identity
     * already decided it once, geometry never needs to re-derive it or see a {@code
     * BiomeGraph}.
     *
     * <p><b>Known simplification (2026-09-14):</b> {@code Assignment} now carries a full
     * self-adjacent GROUP (any size), not just one partner - this export/measurement-only
     * path (never the shipped mod's live path, see class doc) still only pairs with ONE
     * representative member (the lowest-indexed) rather than correcting a full N-way group
     * jointly. Left as-is deliberately: extending {@link #correctPairedFacesJointly} to N
     * members wasn't worth doing for a dead code path while the real live path ({@code
     * SequentialVertexPlacement}) was the one actually being measured today - revisit if
     * this export path is ever needed for a real N-way measurement.
     */
    private static Map<Integer, Integer> pairPartnersOf(Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments) {
        Map<Integer, Integer> pairPartnerByFace = new HashMap<>();
        assignments.forEach((faceIndex, assignment) -> {
            if (!assignment.groupPartnerFaceIndices().isEmpty()) {
                pairPartnerByFace.put(faceIndex, java.util.Collections.min(assignment.groupPartnerFaceIndices()));
            }
        });
        return pairPartnerByFace;
    }

    /**
     * Runs {@link #CALIBRATION_ROUNDS} rounds of proportional weight correction - see
     * {@link WeightedVertexPlacement} class doc for why this converges in principle
     * (semi-discrete optimal transport existence result) and why proportional update is
     * reasonable (power diagrams' monotonicity property). Only faces with a real target
     * area (real CSP assignment) have weight adjusted; fixed/hull-touching faces stay at
     * baseline weight, same as before calibration existed.
     *
     * <p>A self-adjacent PAIR is corrected AS ONE unit, not two independent faces - see
     * {@link #correctPairedFacesJointly}'s own doc for why splitting one felt region into
     * two independently-calibrated half-targets under-served it.
     */
    private static Map<Integer, Point2D> calibratedWeights(
        HexMeshBuilder.HexMesh mesh, Map<Integer, Point2D> centers, Map<Integer, Double> weightByFace,
        List<int[]> triangles, Map<Integer, Double> targetAreaByFace, Map<Integer, WeightedVertexPlacement.AreaRange> legalAreaRangeByFace,
        Map<Integer, Point2D> displaced, double baselineCircumradiusBlocks, double minEdgeLengthBlocks, Map<Integer, Point2D> committedVertexPositions,
        Map<Integer, Integer> pairPartnerByFace
    ) {
        Map<Integer, Double> weights = new HashMap<>(weightByFace);
        double maxCorrectionPerRound = MAX_WEIGHT_CORRECTION_FRACTION_OF_BASELINE * baselineCircumradiusBlocks * baselineCircumradiusBlocks;
        for (int round = 0; round < CALIBRATION_ROUNDS; round++) {
            Map<Integer, Double> realizedAreaByFace = MeshFaceGeometry.realizedAreasOf(mesh, displaced, targetAreaByFace.keySet());
            Set<Integer> corrected = new HashSet<>();
            for (int faceIndex : targetAreaByFace.keySet()) {
                if (!corrected.add(faceIndex)) {
                    continue; // already corrected as the partner's own turn below
                }
                Integer partner = pairPartnerByFace.get(faceIndex);
                if (partner != null && targetAreaByFace.containsKey(partner)) {
                    correctPairedFacesJointly(faceIndex, partner, targetAreaByFace, realizedAreaByFace, weights, maxCorrectionPerRound);
                    corrected.add(partner);
                } else {
                    correctSingleFace(faceIndex, targetAreaByFace.get(faceIndex), realizedAreaByFace.get(faceIndex), weights, maxCorrectionPerRound);
                }
            }
            displaced = JointVertexGeometry.vertexPositionsFor(mesh, centers, weights, triangles, minEdgeLengthBlocks, targetAreaByFace, legalAreaRangeByFace, baselineCircumradiusBlocks, committedVertexPositions);
        }
        return displaced;
    }

    private static void correctSingleFace(int faceIndex, double targetArea, double realizedArea, Map<Integer, Double> weights, double maxCorrectionPerRound) {
        double rawCorrection = DAMPING_FACTOR * (targetArea - realizedArea);
        double clampedCorrection = Math.max(-maxCorrectionPerRound, Math.min(maxCorrectionPerRound, rawCorrection));
        weights.put(faceIndex, weights.get(faceIndex) + clampedCorrection);
    }

    /**
     * A self-adjacent pair is meant to read as ONE felt region across two hexes (see
     * {@code TriangleIdentitySizeAssignment} class doc), so it must compete against
     * EXTERNAL neighbors as one unit too - correcting each half independently toward its
     * own half-target let each side fight its own separate, smaller battle, structurally
     * under-realizing the pair's true combined area even when calibration converged
     * cleanly on paper. Fixed by correcting the JOINT gap (combined target vs. combined
     * realized) once, applying the SAME correction to both faces - since both start with
     * equal weight (same half-target width), this keeps them equal forever, so their
     * mutual boundary always sits at the true geometric bisector, same as a single larger
     * hex would split into two identical halves.
     *
     * <p>Clamp is DOUBLED vs. a single face's own {@code maxCorrectionPerRound} - a real,
     * measured fix, not a guess: the joint gap (combined target minus combined realized)
     * is naturally up to 2x a single face's own gap, so reusing the single-face clamp
     * unchanged halved the pair's own effective convergence rate, leaving many real pairs
     * still climbing (not plateaued) after all {@link #CALIBRATION_ROUNDS} rounds -
     * confirmed directly via {@code TriangleIdentitySizeAssignmentTest
     * .pairedPatchesRealizeAsFaithfullyAsSingletonFaces}'s own before/after numbers.
     */
    private static void correctPairedFacesJointly(
        int faceIndex, int partner, Map<Integer, Double> targetAreaByFace, Map<Integer, Double> realizedAreaByFace,
        Map<Integer, Double> weights, double maxCorrectionPerRound
    ) {
        double maxJointCorrectionPerRound = 2 * maxCorrectionPerRound;
        double jointTarget = targetAreaByFace.get(faceIndex) + targetAreaByFace.get(partner);
        double jointRealized = realizedAreaByFace.get(faceIndex) + realizedAreaByFace.get(partner);
        double rawCorrection = DAMPING_FACTOR * (jointTarget - jointRealized);
        double clampedCorrection = Math.max(-maxJointCorrectionPerRound, Math.min(maxJointCorrectionPerRound, rawCorrection));
        weights.put(faceIndex, weights.get(faceIndex) + clampedCorrection);
        weights.put(partner, weights.get(partner) + clampedCorrection);
    }
}
