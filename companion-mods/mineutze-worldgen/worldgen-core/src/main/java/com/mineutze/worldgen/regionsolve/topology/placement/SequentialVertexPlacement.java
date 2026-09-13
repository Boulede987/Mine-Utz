package com.mineutze.worldgen.regionsolve.topology.placement;

import com.mineutze.worldgen.regionsolve.topology.EdgeDisplacement;

import com.mineutze.worldgen.regionsolve.topology.Point2D;

import com.mineutze.worldgen.regionsolve.topology.mesh.HexMeshBuilder;

import com.mineutze.worldgen.regionsolve.topology.identity.TriangleIdentitySizeAssignment;

import com.mineutze.worldgen.regionsolve.topology.mesh.FaceAdjacency;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The "no tug-of-war, ever" mechanism (2026-09-11) behind {@code
 * WeightedVertexPlacement.sequentiallyRealizedVertexPositions} — the shipped mod's real
 * live path (via {@code TriangleFieldGeometryRealization.realizeCluster}), replacing
 * {@code CalibratedVertexPlacement}'s JOINT, simultaneous multi-face iteration with
 * genuinely SEQUENTIAL, one-face-at-a-time commitment: every free face's weight is
 * resolved via direct binary search against ONLY what's already fixed at that exact
 * moment (external committed widths/vertex positions, plus every cluster-mate already
 * processed earlier in THIS SAME call) - never against another face still being decided.
 * Once resolved, a face's weight is added to that "already fixed" context for everyone
 * processed after it, and never revisited for the rest of this call.
 *
 * <p><b>Why this is correct without calibration's own iteration</b>: with every OTHER
 * input held fixed, a face's realized area is a well-defined, MONOTONIC function of its
 * own weight alone (increasing weight strictly increases its own area, holding others
 * fixed - same documented power-diagram property {@code CalibratedVertexPlacement} relies
 * on) - a single binary search converges directly to the weight whose area is closest to
 * target, no repeated correction rounds needed, no coupling left to iterate against.
 *
 * <p><b>Real, deliberate simplification found while building this, not glossed over</b>:
 * binary search runs against the RAW (unclamped) radicalCenter area, not the safety-
 * clamped one - clamping applied exactly ONCE, after search converges, via {@link
 * HexPolygonSafety#maxSafeScale} scoped to THIS FACE'S OWN 6 corners only (not the
 * whole-mesh reconciliation {@link JointVertexGeometry#geometricallyValidPositions} does,
 * found during design to be the WRONG tool here: it validates every face in the mesh
 * each round, including faces not yet decided, whose "shape" is a meaningless mix of one
 * real corner and five placeholder-lattice ones - reusing it here would let a not-yet-real
 * neighbor's fake shape spuriously constrain a real, already-being-decided face). Raw
 * radicalCenter area is monotonic in weight regardless (more fundamental than "clamped
 * area stays monotonic," which this design deliberately avoids needing to assume).
 *
 * <p>Processing order: the free face with the MOST already-solved neighbors goes next -
 * directly reusing {@link TriangleIdentitySizeAssignment}'s {@code
 * mostConstrainedUnassignedFace} principle one level down (geometry instead of identity),
 * maximizing real context every face gets before its own turn.
 *
 * <p>A shared vertex is only added to the growing "locked" context once ALL 3 owning
 * faces have been processed (same rule {@code
 * TriangleFieldGeometryRealization.commitClusterVerticesTentatively} uses one level up,
 * applied here within a single pass) - a face's WEIGHT is genuinely locked forever the
 * moment it's processed (the real "no tug-of-war" guarantee), but the final POSITION of a
 * shared corner can still see a small last-mile refinement from a not-yet-processed
 * co-owner's later turn - same tentative-until-truly-final property the real live path
 * already has, just one level finer here.
 *
 * <p><b>Self-adjacent PAIRS are resolved as ONE joint binary search, not two independent
 * ones (2026-09-12 fix).</b> A pair is meant to read as ONE felt region across two hexes
 * (see {@code TriangleIdentitySizeAssignment} class doc) - resolving each half's weight
 * independently against its own half-target let each side fight its own separate,
 * smaller battle, measurably under-realizing the pair's true combined area (see {@code
 * TriangleIdentitySizeAssignmentTest.pairedPatchesRealizeAsFaithfullyAsSingletonFaces}).
 * When {@link #realize} reaches the FIRST face of an unsolved pair, it resolves ONE
 * shared weight via binary search against the pair's COMBINED target (both faces' own
 * local triangle rings merged, both treated as the search candidate simultaneously) -
 * assigns that SAME weight to both, then finalizes each face's own corners independently
 * (each still safety-clamped against its own half-target/legal range, unchanged). Since
 * this is still a single monotonic binary search (not repeated competitive correction
 * rounds like {@code CalibratedVertexPlacement}), it has no tug-of-war/convergence-rate
 * problem at all - it converges to the joint target directly, same as a singleton face's
 * own search converges to its target.
 *
 * <p><b>Protected-neighbor weight clamp (2026-09-13 fix).</b> A face's WEIGHT locks
 * forever the instant it's processed, but its realized AREA still depends on every
 * other owner of its own corners too - a corner shared with two already-solved owners
 * commits the instant the LATE-arriving third face finishes its own turn (see {@link
 * #commitNewlySolvedCorners}), and until now that late face's own weight search
 * optimized purely for ITS OWN target, with zero awareness it might drag an
 * already-solved neighbor's own already-achieved realized area outside that neighbor's
 * own LEGAL size range - confirmed by direct trace to be the real cause of self-adjacent
 * pairs' own jointly-resolved area getting silently undone by an unrelated third
 * neighbor's later turn. Fixed WITHOUT reopening a tug-of-war (never revisits an
 * already-solved face): once a face (or joint pair) resolves its own raw weight, {@link
 * #weightRespectingProtectedNeighbors} checks whether committing that raw weight would
 * push any already-solved "protected" co-owner outside its own legal area range - if so,
 * bisects the smallest pullback toward NEUTRAL (this face's own pre-resolution baseline,
 * never the protected neighbor's target) that keeps every protected neighbor legal - one
 * more one-shot bisection on the LATE face only, same shape as every other safety clamp
 * in this class.
 */
final class SequentialVertexPlacement {

    // Never a fixed/alphabetical order - same standing convention as every other seeded
    // hash in this codebase (see TriangleIdentitySizeAssignment's salts' doc for the real
    // bug this class of mistake caused once already).
    private static final long SEQUENTIAL_ORDER_SALT = 0x3E27_9ACD_1F84_B650L;

    // How far above targetArea the weight search bound reaches - not a probe VALUE trusted
    // as an estimate (see AchievableAreaProbe for why that pattern is wrong), just a
    // generous SEARCH INTERVAL edge for bisection to converge within. Weight and area are
    // both O(radius^2), so a bound this far above target comfortably contains any real
    // weight that could ever be needed.
    private static final double SEQUENTIAL_SEARCH_HIGH_MULTIPLIER = 1000.0;

    /** Classic hash-spreading multiplier (same convention as {@code java.util.Objects.hash}, and {@code EdgeDisplacement}'s {@code HASH_MULTIPLIER}). */
    private static final long HASH_MULTIPLIER = 31;

    /** Bisection precision for {@link #resolveWeightFor} - same reasoning as {@link HexPolygonSafety}'s copy, an independent binary search for a different quantity (weight, not scale) that happens to want the same precision/cost tradeoff. */
    private static final int BISECTION_ITERATIONS = 30;

    private SequentialVertexPlacement() {
    }

    static Map<Integer, Point2D> realize(
        HexMeshBuilder.HexMesh mesh, FaceAdjacency.Adjacency adjacency,
        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments, double baselineCircumradiusBlocks,
        double minEdgeLengthBlocks, Map<Integer, Integer> committedWidthBlocksByFace, Map<Integer, Point2D> committedVertexPositions,
        long worldSeed
    ) {
        Map<Integer, Point2D> centers = MeshFaceGeometry.centersOf(mesh);
        Map<Integer, Double> targetAreaByFace = MeshFaceGeometry.targetAreasOf(assignments);
        Map<Integer, WeightedVertexPlacement.AreaRange> legalAreaRangeByFace = MeshFaceGeometry.legalAreaRangesOf(assignments);
        Set<Integer> freeFaces = assignments.keySet();
        double baselineWeight = baselineCircumradiusBlocks * baselineCircumradiusBlocks;

        Map<Integer, Double> weightByFace = new HashMap<>();
        for (int faceIndex = 0; faceIndex < mesh.faces().size(); faceIndex++) {
            Integer committedWidth = committedWidthBlocksByFace.get(faceIndex);
            weightByFace.put(faceIndex, committedWidth != null ? PowerDiagramMath.powerWeight(committedWidth) : baselineWeight);
        }

        Set<Integer> solved = new HashSet<>();
        for (int faceIndex = 0; faceIndex < mesh.faces().size(); faceIndex++) {
            if (!freeFaces.contains(faceIndex)) {
                solved.add(faceIndex);
            }
        }

        Map<Integer, Point2D> displaced = new HashMap<>(committedVertexPositions);
        List<Integer> processingOrder = processingOrderFor(freeFaces, adjacency, solved, worldSeed);
        Map<Integer, Integer> pairPartnerByFace = pairPartnersOf(assignments);

        for (int faceIndex : processingOrder) {
            if (solved.contains(faceIndex)) {
                continue; // already resolved jointly as its partner's own turn below
            }
            Integer partner = pairPartnerByFace.get(faceIndex);
            if (partner != null && freeFaces.contains(partner) && !solved.contains(partner)) {
                resolvePairJointly(
                    mesh, adjacency, centers, faceIndex, partner, weightByFace, targetAreaByFace, legalAreaRangeByFace,
                    minEdgeLengthBlocks, baselineCircumradiusBlocks, displaced, solved
                );
            } else {
                resolveSingleFace(
                    mesh, adjacency, centers, faceIndex, weightByFace, targetAreaByFace, legalAreaRangeByFace,
                    minEdgeLengthBlocks, baselineCircumradiusBlocks, displaced, solved
                );
            }
        }

        // A real, found-via-crash gap: a mesh-vertex triple whose all 3 owners are
        // EXTERNALLY fixed (none ever in freeFaces) is never touched by the loop above -
        // it only visits FREE faces' local rings. Yet
        // TriangleFieldGeometryRealization.commitClusterVerticesTentatively unconditionally
        // expects an entry for every triple where all 3 owners have "some value" (free-or-
        // fixed) - a real NullPointerException, not hypothetical, first hit when a real
        // cluster solve had a vertex touching 3 already-committed neighbors never jointly
        // computed by any earlier solve - same gap promoteFaceToPermanent already names
        // and tolerates one level up. No safety clamp needed here - all 3 owners' weights
        // already final, decided elsewhere; this fills in known geometry, not deciding size.
        for (int[] triangle : adjacency.mutuallyAdjacentFaceTriples(centers.keySet())) {
            if (freeFaces.contains(triangle[0]) || freeFaces.contains(triangle[1]) || freeFaces.contains(triangle[2])) {
                continue;
            }
            int vertexIndex = FaceAdjacency.sharedVertexOf(mesh, triangle);
            if (displaced.containsKey(vertexIndex)) {
                continue;
            }
            displaced.put(vertexIndex, PowerDiagramMath.radicalCenter(
                centers.get(triangle[0]), weightByFace.get(triangle[0]),
                centers.get(triangle[1]), weightByFace.get(triangle[1]),
                centers.get(triangle[2]), weightByFace.get(triangle[2])
            ));
        }
        return displaced;
    }

    /** Reads pairing straight off each {@code Assignment} (see its own doc) - identity already decided it once, geometry never needs to re-derive it or see a {@code BiomeGraph}. */
    private static Map<Integer, Integer> pairPartnersOf(Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments) {
        Map<Integer, Integer> pairPartnerByFace = new HashMap<>();
        assignments.forEach((faceIndex, assignment) -> {
            if (assignment.pairedWithFaceIndex() != TriangleIdentitySizeAssignment.Assignment.NOT_PAIRED) {
                pairPartnerByFace.put(faceIndex, assignment.pairedWithFaceIndex());
            }
        });
        return pairPartnerByFace;
    }

    /** One singleton face's own turn - resolve its weight against only its own target, finalize its corners, mark it solved, commit any now-fully-solved corner. */
    private static void resolveSingleFace(
        HexMeshBuilder.HexMesh mesh, FaceAdjacency.Adjacency adjacency, Map<Integer, Point2D> centers, int faceIndex,
        Map<Integer, Double> weightByFace, Map<Integer, Double> targetAreaByFace, Map<Integer, WeightedVertexPlacement.AreaRange> legalAreaRangeByFace,
        double minEdgeLengthBlocks, double baselineCircumradiusBlocks, Map<Integer, Point2D> displaced, Set<Integer> solved
    ) {
        List<int[]> localTriangles = localTrianglesOf(mesh, adjacency, faceIndex);
        double neutralWeight = weightByFace.get(faceIndex);
        double rawWeight = resolveWeightFor(mesh, centers, faceIndex, localTriangles, weightByFace, targetAreaByFace.get(faceIndex), displaced);
        double resolvedWeight = weightRespectingProtectedNeighbors(
            mesh, adjacency, centers, Set.of(faceIndex), localTriangles, weightByFace, neutralWeight, rawWeight, legalAreaRangeByFace, displaced, solved
        );
        weightByFace.put(faceIndex, resolvedWeight);

        Map<Integer, Point2D> finalCorners = finalizedCornersOf(
            mesh, centers, faceIndex, localTriangles, weightByFace, minEdgeLengthBlocks, targetAreaByFace.get(faceIndex),
            legalAreaRangeByFace.get(faceIndex), baselineCircumradiusBlocks, displaced
        );
        solved.add(faceIndex);
        commitNewlySolvedCorners(mesh, localTriangles, solved, finalCorners, displaced);
    }

    /**
     * A self-adjacent pair's own joint turn - see class doc for why this is ONE binary
     * search (against the pair's COMBINED target, over both faces' own local triangle
     * rings merged) instead of two independent ones. Both faces get the SAME resolved
     * weight.
     *
     * <p><b>The safety clamp afterward must ALSO be shared, not independent (real bug
     * found and fixed while measuring this fix, not assumed safe)</b>: the raw joint
     * search converges to the combined target essentially exactly every time (measured
     * directly), but each face's own {@link HexPolygonSafety#maxSafeScale} check runs
     * against ITS OWN local geometry (its own OTHER, non-shared neighbors) and can demand
     * a smaller scale than its partner's - applying each face's own independent scale
     * silently undid the whole joint fix, shrinking one side back toward baseline while
     * letting the other keep its full raw share. Fixed the same way a shared VERTEX
     * already gets the minimum of every owning face's own scale elsewhere in this
     * pipeline: compute both faces' own required scale, take the MINIMUM, apply that ONE
     * shared scale to both - preserves the ratio the joint search actually established.
     */
    private static void resolvePairJointly(
        HexMeshBuilder.HexMesh mesh, FaceAdjacency.Adjacency adjacency, Map<Integer, Point2D> centers, int faceIndex, int partner,
        Map<Integer, Double> weightByFace, Map<Integer, Double> targetAreaByFace, Map<Integer, WeightedVertexPlacement.AreaRange> legalAreaRangeByFace,
        double minEdgeLengthBlocks, double baselineCircumradiusBlocks, Map<Integer, Point2D> displaced, Set<Integer> solved
    ) {
        List<int[]> localTrianglesA = localTrianglesOf(mesh, adjacency, faceIndex);
        List<int[]> localTrianglesB = localTrianglesOf(mesh, adjacency, partner);
        List<int[]> jointTriangles = jointTrianglesOf(mesh, localTrianglesA, localTrianglesB);
        double jointTarget = targetAreaByFace.get(faceIndex) + targetAreaByFace.get(partner);
        double neutralWeight = weightByFace.get(faceIndex);
        double rawJointWeight = resolveJointWeightFor(mesh, centers, faceIndex, partner, jointTriangles, weightByFace, jointTarget, displaced);
        double resolvedWeight = weightRespectingProtectedNeighbors(
            mesh, adjacency, centers, Set.of(faceIndex, partner), jointTriangles, weightByFace, neutralWeight, rawJointWeight, legalAreaRangeByFace, displaced, solved
        );
        weightByFace.put(faceIndex, resolvedWeight);
        weightByFace.put(partner, resolvedWeight);

        FaceLerpInputs inputsA = lerpInputsFor(mesh, centers, faceIndex, localTrianglesA, weightByFace, targetAreaByFace.get(faceIndex), legalAreaRangeByFace.get(faceIndex), baselineCircumradiusBlocks, displaced);
        FaceLerpInputs inputsB = lerpInputsFor(mesh, centers, partner, localTrianglesB, weightByFace, targetAreaByFace.get(partner), legalAreaRangeByFace.get(partner), baselineCircumradiusBlocks, displaced);
        double scaleA = HexPolygonSafety.maxSafeScale(inputsA.original(), inputsA.candidate(), minEdgeLengthBlocks, inputsA.bounds().minAreaBlocks2(), inputsA.bounds().maxAreaBlocks2(), inputsA.bounds().maxDisplacementBlocks());
        double scaleB = HexPolygonSafety.maxSafeScale(inputsB.original(), inputsB.candidate(), minEdgeLengthBlocks, inputsB.bounds().minAreaBlocks2(), inputsB.bounds().maxAreaBlocks2(), inputsB.bounds().maxDisplacementBlocks());
        double sharedScale = Math.min(scaleA, scaleB);
        Map<Integer, Point2D> finalCornersA = finalCornersFrom(inputsA, sharedScale);
        Map<Integer, Point2D> finalCornersB = finalCornersFrom(inputsB, sharedScale);

        solved.add(faceIndex);
        solved.add(partner);
        commitNewlySolvedCorners(mesh, localTrianglesA, solved, finalCornersA, displaced);
        commitNewlySolvedCorners(mesh, localTrianglesB, solved, finalCornersB, displaced);
    }

    /** Commits whichever of {@code faceIndex}'s own local-triangle corners now have all 3 owners solved - the shared "lock a vertex once its last owner is decided" step both {@link #resolveSingleFace} and {@link #resolvePairJointly} need. */
    private static void commitNewlySolvedCorners(
        HexMeshBuilder.HexMesh mesh, List<int[]> localTriangles, Set<Integer> solved, Map<Integer, Point2D> finalCorners, Map<Integer, Point2D> displaced
    ) {
        for (int[] triangle : localTriangles) {
            int vertexIndex = FaceAdjacency.sharedVertexOf(mesh, triangle);
            if (displaced.containsKey(vertexIndex)) {
                continue;
            }
            if (solved.contains(triangle[0]) && solved.contains(triangle[1]) && solved.contains(triangle[2])) {
                displaced.put(vertexIndex, finalCorners.get(vertexIndex));
            }
        }
    }

    /**
     * Bounds a just-resolved raw weight (one face, or a joint pair sharing one weight)
     * so it never pushes an already-solved "protected" co-owner's own realized area
     * outside THAT co-owner's own legal range - see class doc. Bisects toward NEUTRAL,
     * never toward the protected neighbor's own target - this stays a one-shot pullback
     * on the late face only, never a second tug-of-war.
     *
     * <p><b>Only protects a co-owner that's genuinely SAFE at NEUTRAL (real bug found and
     * fixed while measuring this fix, not assumed safe)</b>: a co-owner already outside
     * its own legal range for reasons that have NOTHING to do with this corner (real,
     * common - the live path already has known pre-existing legal-range violations) can
     * never be satisfied by anything we pick, so checking it unfiltered forced the
     * bisection all the way to neutral pointlessly - dragging THIS face's own accuracy
     * back toward baseline for a neighbor we have no power to fix. Confirmed directly:
     * first version (no filter) made mean SINGLETON deviation measurably WORSE (0.203 to
     * 0.279 on {@code TriangleIdentitySizeAssignmentTest
     * .pairedPatchesRealizeAsFaithfullyAsSingletonFacesOnTheLivePath}) even though the
     * paired-patch number it targeted barely moved - a hollow pass, fixed by only ever
     * protecting a co-owner we could actually keep legal.
     */
    private static double weightRespectingProtectedNeighbors(
        HexMeshBuilder.HexMesh mesh, FaceAdjacency.Adjacency adjacency, Map<Integer, Point2D> centers,
        Set<Integer> ownFaces, List<int[]> ownTriangles, Map<Integer, Double> weightByFace, double neutralWeight, double rawWeight,
        Map<Integer, WeightedVertexPlacement.AreaRange> legalAreaRangeByFace, Map<Integer, Point2D> displaced, Set<Integer> solved
    ) {
        Set<Integer> candidateCoOwners = protectedCoOwnersOf(ownTriangles, ownFaces, solved, legalAreaRangeByFace);
        Set<Integer> protectedCoOwners = new HashSet<>();
        for (int coOwner : candidateCoOwners) {
            if (isCoOwnerAreaWithinLegalRangeAt(mesh, adjacency, centers, ownFaces, coOwner, neutralWeight, weightByFace, legalAreaRangeByFace, displaced)) {
                protectedCoOwners.add(coOwner);
            }
        }
        if (protectedCoOwners.isEmpty()
            || allProtectedNeighborsSafeAt(mesh, adjacency, centers, ownFaces, protectedCoOwners, rawWeight, weightByFace, legalAreaRangeByFace, displaced)) {
            return rawWeight;
        }
        double lowScale = 0.0;
        double highScale = 1.0;
        for (int iteration = 0; iteration < BISECTION_ITERATIONS; iteration++) {
            double midScale = (lowScale + highScale) / 2.0;
            double midWeight = neutralWeight + midScale * (rawWeight - neutralWeight);
            if (allProtectedNeighborsSafeAt(mesh, adjacency, centers, ownFaces, protectedCoOwners, midWeight, weightByFace, legalAreaRangeByFace, displaced)) {
                lowScale = midScale;
            } else {
                highScale = midScale;
            }
        }
        return neutralWeight + lowScale * (rawWeight - neutralWeight);
    }

    /** Already-solved faces sharing one of OUR own triangles, where every OTHER owner of that same triangle is ALSO already solved - that corner commits the instant our own turn finishes (same rule {@link #commitNewlySolvedCorners} uses), so that face's own already-achieved realized area is now at risk from whatever weight we pick. */
    private static Set<Integer> protectedCoOwnersOf(
        List<int[]> ownTriangles, Set<Integer> ownFaces, Set<Integer> solved, Map<Integer, WeightedVertexPlacement.AreaRange> legalAreaRangeByFace
    ) {
        Set<Integer> protectedCoOwners = new HashSet<>();
        for (int[] triangle : ownTriangles) {
            List<Integer> others = new ArrayList<>();
            for (int face : triangle) {
                if (!ownFaces.contains(face)) {
                    others.add(face);
                }
            }
            if (others.stream().allMatch(solved::contains)) {
                for (int other : others) {
                    if (legalAreaRangeByFace.containsKey(other)) {
                        protectedCoOwners.add(other);
                    }
                }
            }
        }
        return protectedCoOwners;
    }

    private static boolean allProtectedNeighborsSafeAt(
        HexMeshBuilder.HexMesh mesh, FaceAdjacency.Adjacency adjacency, Map<Integer, Point2D> centers,
        Set<Integer> ownFaces, Set<Integer> protectedCoOwners, double candidateWeight, Map<Integer, Double> weightByFace,
        Map<Integer, WeightedVertexPlacement.AreaRange> legalAreaRangeByFace, Map<Integer, Point2D> displaced
    ) {
        for (int coOwner : protectedCoOwners) {
            if (!isCoOwnerAreaWithinLegalRangeAt(mesh, adjacency, centers, ownFaces, coOwner, candidateWeight, weightByFace, legalAreaRangeByFace, displaced)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isCoOwnerAreaWithinLegalRangeAt(
        HexMeshBuilder.HexMesh mesh, FaceAdjacency.Adjacency adjacency, Map<Integer, Point2D> centers,
        Set<Integer> ownFaces, int coOwner, double candidateWeight, Map<Integer, Double> weightByFace,
        Map<Integer, WeightedVertexPlacement.AreaRange> legalAreaRangeByFace, Map<Integer, Point2D> displaced
    ) {
        double area = realizedAreaOfCoOwnerUnderCandidate(mesh, adjacency, centers, coOwner, ownFaces, candidateWeight, weightByFace, displaced);
        WeightedVertexPlacement.AreaRange range = legalAreaRangeByFace.get(coOwner);
        return area >= range.minAreaBlocks2() && area <= range.maxAreaBlocks2();
    }

    /** {@code coOwnerFace}'s own realized area, recomputed with OUR candidate weight substituted wherever one of {@code ownFaces} appears in ITS OWN local triangles - same "override one face's weight, read another face's shoelace area" trick {@link #resolveJointWeightFor} already uses. */
    private static double realizedAreaOfCoOwnerUnderCandidate(
        HexMeshBuilder.HexMesh mesh, FaceAdjacency.Adjacency adjacency, Map<Integer, Point2D> centers, int coOwnerFace,
        Set<Integer> ownFaces, double candidateWeight, Map<Integer, Double> weightByFace, Map<Integer, Point2D> displaced
    ) {
        List<int[]> coOwnerTriangles = localTrianglesOf(mesh, adjacency, coOwnerFace);
        Map<Integer, Point2D> positions = cornerPositionsForMultiOverride(mesh, centers, coOwnerTriangles, weightByFace, ownFaces, candidateWeight, displaced);
        return shoelaceAreaOfFace(mesh, coOwnerFace, positions);
    }

    /** Same as {@link #cornerPositionsFor}, but the overridden weight applies to a SET of faces at once (a joint pair shares one weight) instead of exactly one. */
    private static Map<Integer, Point2D> cornerPositionsForMultiOverride(
        HexMeshBuilder.HexMesh mesh, Map<Integer, Point2D> centers, List<int[]> triangles, Map<Integer, Double> weightByFace,
        Set<Integer> overrideFaces, double overrideWeight, Map<Integer, Point2D> displaced
    ) {
        Map<Integer, Point2D> positions = new HashMap<>();
        for (int[] triangle : triangles) {
            int vertexIndex = FaceAdjacency.sharedVertexOf(mesh, triangle);
            Point2D locked = displaced.get(vertexIndex);
            if (locked != null) {
                positions.put(vertexIndex, locked);
                continue;
            }
            double weightA = overrideFaces.contains(triangle[0]) ? overrideWeight : weightByFace.get(triangle[0]);
            double weightB = overrideFaces.contains(triangle[1]) ? overrideWeight : weightByFace.get(triangle[1]);
            double weightC = overrideFaces.contains(triangle[2]) ? overrideWeight : weightByFace.get(triangle[2]);
            positions.put(vertexIndex, PowerDiagramMath.radicalCenter(centers.get(triangle[0]), weightA, centers.get(triangle[1]), weightB, centers.get(triangle[2]), weightC));
        }
        return positions;
    }

    /** Union of two faces' own local triangle rings, deduplicated by the real mesh vertex each triangle shares (a shared-boundary triangle appears in BOTH faces' own rings, since both own it). */
    private static List<int[]> jointTrianglesOf(HexMeshBuilder.HexMesh mesh, List<int[]> localTrianglesA, List<int[]> localTrianglesB) {
        Map<Integer, int[]> byVertexIndex = new HashMap<>();
        for (int[] triangle : localTrianglesA) {
            byVertexIndex.put(FaceAdjacency.sharedVertexOf(mesh, triangle), triangle);
        }
        for (int[] triangle : localTrianglesB) {
            byVertexIndex.putIfAbsent(FaceAdjacency.sharedVertexOf(mesh, triangle), triangle);
        }
        return new ArrayList<>(byVertexIndex.values());
    }

    /** Direct binary search for the weight whose RAW combined area (faceA's + faceB's, same candidate weight for both) is closest to {@code jointTargetArea} - same shape as {@link #resolveWeightFor}, one level up (a pair instead of a single face). */
    private static double resolveJointWeightFor(
        HexMeshBuilder.HexMesh mesh, Map<Integer, Point2D> centers, int faceA, int faceB, List<int[]> jointTriangles,
        Map<Integer, Double> weightByFace, double jointTargetArea, Map<Integer, Point2D> displaced
    ) {
        double lowWeight = PowerDiagramMath.powerWeight(1);
        double highWeight = jointTargetArea * SEQUENTIAL_SEARCH_HIGH_MULTIPLIER;
        double lowArea = jointRawAreaAt(mesh, centers, faceA, faceB, jointTriangles, weightByFace, lowWeight, displaced);
        double highArea = jointRawAreaAt(mesh, centers, faceA, faceB, jointTriangles, weightByFace, highWeight, displaced);
        if (jointTargetArea <= lowArea) {
            return lowWeight;
        }
        if (jointTargetArea >= highArea) {
            return highWeight;
        }
        for (int iteration = 0; iteration < BISECTION_ITERATIONS; iteration++) {
            double midWeight = (lowWeight + highWeight) / 2.0;
            double midArea = jointRawAreaAt(mesh, centers, faceA, faceB, jointTriangles, weightByFace, midWeight, displaced);
            if (midArea < jointTargetArea) {
                lowWeight = midWeight;
            } else {
                highWeight = midWeight;
            }
        }
        return (lowWeight + highWeight) / 2.0;
    }

    private static double jointRawAreaAt(
        HexMeshBuilder.HexMesh mesh, Map<Integer, Point2D> centers, int faceA, int faceB, List<int[]> jointTriangles,
        Map<Integer, Double> weightByFace, double candidateWeight, Map<Integer, Point2D> displaced
    ) {
        Map<Integer, Point2D> positions = jointCornerPositionsFor(mesh, centers, faceA, faceB, jointTriangles, weightByFace, candidateWeight, displaced);
        return shoelaceAreaOfFace(mesh, faceA, positions) + shoelaceAreaOfFace(mesh, faceB, positions);
    }

    /** Same as {@link #cornerPositionsFor}, but BOTH {@code faceA} and {@code faceB} are the search candidate at once (same weight) - the mechanism that makes the pair's own shared boundary land at the true bisector once resolved. */
    private static Map<Integer, Point2D> jointCornerPositionsFor(
        HexMeshBuilder.HexMesh mesh, Map<Integer, Point2D> centers, int faceA, int faceB, List<int[]> jointTriangles,
        Map<Integer, Double> weightByFace, double candidateWeight, Map<Integer, Point2D> displaced
    ) {
        Map<Integer, Point2D> positions = new HashMap<>();
        for (int[] triangle : jointTriangles) {
            int vertexIndex = FaceAdjacency.sharedVertexOf(mesh, triangle);
            Point2D locked = displaced.get(vertexIndex);
            if (locked != null) {
                positions.put(vertexIndex, locked);
                continue;
            }
            double weightA = weightForCandidate(triangle[0], faceA, faceB, candidateWeight, weightByFace);
            double weightB = weightForCandidate(triangle[1], faceA, faceB, candidateWeight, weightByFace);
            double weightC = weightForCandidate(triangle[2], faceA, faceB, candidateWeight, weightByFace);
            positions.put(vertexIndex, PowerDiagramMath.radicalCenter(centers.get(triangle[0]), weightA, centers.get(triangle[1]), weightB, centers.get(triangle[2]), weightC));
        }
        return positions;
    }

    private static double weightForCandidate(int face, int faceA, int faceB, double candidateWeight, Map<Integer, Double> weightByFace) {
        return (face == faceA || face == faceB) ? candidateWeight : weightByFace.get(face);
    }

    /** Every real mesh-vertex triple touching {@code faceIndex}'s own 6 corners - {@code faceIndex} plus its neighbors is exactly enough for {@link FaceAdjacency.Adjacency#mutuallyAdjacentFaceTriples} to find all of them, same ring-restriction trick {@code AchievableAreaProbe} uses. */
    private static List<int[]> localTrianglesOf(HexMeshBuilder.HexMesh mesh, FaceAdjacency.Adjacency adjacency, int faceIndex) {
        Set<Integer> ring = new LinkedHashSet<>();
        ring.add(faceIndex);
        ring.addAll(adjacency.neighborFaceIndices().getOrDefault(faceIndex, Set.of()));
        return adjacency.mutuallyAdjacentFaceTriples(ring).stream()
            .filter(triangle -> triangle[0] == faceIndex || triangle[1] == faceIndex || triangle[2] == faceIndex)
            .toList();
    }

    /**
     * Greedy, adaptive "most already-solved neighbors next" order - recomputing the
     * solved-neighbor count after each pick, same {@code mostConstrainedUnassignedFace}-
     * style greedy principle {@link TriangleIdentitySizeAssignment} uses for identity, one
     * level down. Package-private (not private) so a real test can determine the actual
     * order directly rather than predicting/forcing it via indirect construction - found,
     * while building such a test, that adaptive recomputation can let a face's own
     * solved-neighbor count catch up mid-pass (processing a THIRD face can retroactively
     * raise a SECOND face's count), so a naive "give A one more fixed neighbor than B"
     * trick doesn't reliably guarantee A processes before B.
     */
    static List<Integer> processingOrderFor(Set<Integer> freeFaces, FaceAdjacency.Adjacency adjacency, Set<Integer> initiallySolved, long worldSeed) {
        List<Integer> order = new ArrayList<>();
        Set<Integer> remaining = new HashSet<>(freeFaces);
        Set<Integer> solvedSoFar = new HashSet<>(initiallySolved);
        while (!remaining.isEmpty()) {
            int next = remaining.stream()
                .max(Comparator.<Integer>comparingInt(faceIndex -> countSolvedNeighbors(faceIndex, adjacency, solvedSoFar))
                    .thenComparingLong(faceIndex -> orderTieBreakHash(worldSeed, faceIndex)))
                .orElseThrow();
            order.add(next);
            remaining.remove(next);
            solvedSoFar.add(next);
        }
        return order;
    }

    private static int countSolvedNeighbors(int faceIndex, FaceAdjacency.Adjacency adjacency, Set<Integer> solved) {
        int count = 0;
        for (int neighbor : adjacency.neighborFaceIndices().getOrDefault(faceIndex, Set.of())) {
            if (solved.contains(neighbor)) {
                count++;
            }
        }
        return count;
    }

    private static long orderTieBreakHash(long worldSeed, int faceIndex) {
        long hash = worldSeed ^ SEQUENTIAL_ORDER_SALT;
        hash = hash * HASH_MULTIPLIER + faceIndex;
        return new Random(hash).nextLong();
    }

    /** Direct binary search for the weight whose RAW (unclamped) realized area is closest to {@code targetArea} - see this class's own doc for why raw, not clamped. */
    private static double resolveWeightFor(
        HexMeshBuilder.HexMesh mesh, Map<Integer, Point2D> centers, int faceIndex, List<int[]> localTriangles,
        Map<Integer, Double> weightByFace, double targetArea, Map<Integer, Point2D> displaced
    ) {
        double lowWeight = PowerDiagramMath.powerWeight(1);
        double highWeight = targetArea * SEQUENTIAL_SEARCH_HIGH_MULTIPLIER;
        double lowArea = rawAreaAt(mesh, centers, faceIndex, localTriangles, weightByFace, lowWeight, displaced);
        double highArea = rawAreaAt(mesh, centers, faceIndex, localTriangles, weightByFace, highWeight, displaced);
        if (targetArea <= lowArea) {
            return lowWeight;
        }
        if (targetArea >= highArea) {
            return highWeight;
        }
        for (int iteration = 0; iteration < BISECTION_ITERATIONS; iteration++) {
            double midWeight = (lowWeight + highWeight) / 2.0;
            double midArea = rawAreaAt(mesh, centers, faceIndex, localTriangles, weightByFace, midWeight, displaced);
            if (midArea < targetArea) {
                lowWeight = midWeight;
            } else {
                highWeight = midWeight;
            }
        }
        return (lowWeight + highWeight) / 2.0;
    }

    private static double rawAreaAt(
        HexMeshBuilder.HexMesh mesh, Map<Integer, Point2D> centers, int faceIndex, List<int[]> localTriangles,
        Map<Integer, Double> weightByFace, double candidateWeight, Map<Integer, Point2D> displaced
    ) {
        Map<Integer, Point2D> positions = cornerPositionsFor(mesh, centers, faceIndex, localTriangles, weightByFace, candidateWeight, displaced);
        return shoelaceAreaOfFace(mesh, faceIndex, positions);
    }

    /** {@code faceIndex}'s RAW (unclamped) corner positions for a candidate weight - an already-locked corner (from {@code displaced}) used as-is, matching every other immovable-anchor rule in this class. */
    private static Map<Integer, Point2D> cornerPositionsFor(
        HexMeshBuilder.HexMesh mesh, Map<Integer, Point2D> centers, int faceIndex, List<int[]> localTriangles,
        Map<Integer, Double> weightByFace, double candidateWeight, Map<Integer, Point2D> displaced
    ) {
        Map<Integer, Point2D> positions = new HashMap<>();
        for (int[] triangle : localTriangles) {
            int vertexIndex = FaceAdjacency.sharedVertexOf(mesh, triangle);
            Point2D locked = displaced.get(vertexIndex);
            if (locked != null) {
                positions.put(vertexIndex, locked);
                continue;
            }
            double weightA = triangle[0] == faceIndex ? candidateWeight : weightByFace.get(triangle[0]);
            double weightB = triangle[1] == faceIndex ? candidateWeight : weightByFace.get(triangle[1]);
            double weightC = triangle[2] == faceIndex ? candidateWeight : weightByFace.get(triangle[2]);
            positions.put(vertexIndex, PowerDiagramMath.radicalCenter(centers.get(triangle[0]), weightA, centers.get(triangle[1]), weightB, centers.get(triangle[2]), weightC));
        }
        return positions;
    }

    /** A vertex with no real mesh-vertex triple this session (rare - a face near the mesh's outer margin, ring reaching a hull-touching neighbor) falls back to its raw undisplaced lattice position, same convention every other corner-lookup here uses. */
    private static double shoelaceAreaOfFace(HexMeshBuilder.HexMesh mesh, int faceIndex, Map<Integer, Point2D> positions) {
        List<Integer> vertexIndices = mesh.faces().get(faceIndex).vertexIndices();
        List<Point2D> corners = new ArrayList<>();
        for (int vertexIndex : vertexIndices) {
            corners.add(positions.getOrDefault(vertexIndex, mesh.vertices().get(vertexIndex)));
        }
        return HexPolygonSafety.shoelaceArea(corners);
    }

    /**
     * {@code faceIndex}'s FINAL, safety-clamped corner positions, once its weight is
     * resolved - reuses {@link HexPolygonSafety#maxSafeScale} directly, scoped to only this
     * face's 6 corners (never the whole-mesh {@link JointVertexGeometry#geometricallyValidPositions}
     * reconciliation - see class doc for why that'd be wrong here). No already-locked
     * corner can exist among this face's 6 at this point - proof: a vertex only locks once
     * ALL 3 owners are solved, and {@code faceIndex} itself, one of its own corners' 3
     * owners, isn't yet solved until THIS call finishes - {@code displaced.get(vertexIndex)}
     * still consulted defensively for clarity, but provably a no-op for any of this face's
     * corners.
     */
    private static Map<Integer, Point2D> finalizedCornersOf(
        HexMeshBuilder.HexMesh mesh, Map<Integer, Point2D> centers, int faceIndex, List<int[]> localTriangles,
        Map<Integer, Double> weightByFace, double minEdgeLengthBlocks, double targetArea, WeightedVertexPlacement.AreaRange legalAreaRange, double baselineCircumradiusBlocks,
        Map<Integer, Point2D> displaced
    ) {
        FaceLerpInputs inputs = lerpInputsFor(mesh, centers, faceIndex, localTriangles, weightByFace, targetArea, legalAreaRange, baselineCircumradiusBlocks, displaced);
        double scale = HexPolygonSafety.maxSafeScale(inputs.original(), inputs.candidate(), minEdgeLengthBlocks, inputs.bounds().minAreaBlocks2(), inputs.bounds().maxAreaBlocks2(), inputs.bounds().maxDisplacementBlocks());
        return finalCornersFrom(inputs, scale);
    }

    /** {@code faceIndex}'s own original/candidate corner pairs and safety bounds, BEFORE picking a scale - split out from {@link #finalizedCornersOf} so {@link #resolvePairJointly} can compute both pair members' own required scale first, then apply the SHARED (minimum) one to both - see that method's own doc for why. */
    private record FaceLerpInputs(List<Integer> vertexIndices, List<Point2D> original, List<Point2D> candidate, HexPolygonSafety.SafetyBounds bounds) {
    }

    private static FaceLerpInputs lerpInputsFor(
        HexMeshBuilder.HexMesh mesh, Map<Integer, Point2D> centers, int faceIndex, List<int[]> localTriangles,
        Map<Integer, Double> weightByFace, double targetArea, WeightedVertexPlacement.AreaRange legalAreaRange, double baselineCircumradiusBlocks,
        Map<Integer, Point2D> displaced
    ) {
        Map<Integer, Point2D> rawPositions = cornerPositionsFor(mesh, centers, faceIndex, localTriangles, weightByFace, weightByFace.get(faceIndex), displaced);

        List<Integer> vertexIndices = mesh.faces().get(faceIndex).vertexIndices();
        List<Point2D> original = new ArrayList<>();
        List<Point2D> candidate = new ArrayList<>();
        for (int vertexIndex : vertexIndices) {
            Point2D locked = displaced.get(vertexIndex);
            Point2D basePosition = locked != null ? locked : mesh.vertices().get(vertexIndex);
            original.add(basePosition);
            candidate.add(locked != null ? locked : rawPositions.getOrDefault(vertexIndex, basePosition));
        }
        HexPolygonSafety.SafetyBounds bounds = HexPolygonSafety.safetyBoundsFor(targetArea, legalAreaRange, baselineCircumradiusBlocks);
        return new FaceLerpInputs(vertexIndices, original, candidate, bounds);
    }

    private static Map<Integer, Point2D> finalCornersFrom(FaceLerpInputs inputs, double scale) {
        List<Point2D> finalCorners = HexPolygonSafety.lerpAll(inputs.original(), inputs.candidate(), scale);
        Map<Integer, Point2D> result = new HashMap<>();
        for (int cornerIndex = 0; cornerIndex < inputs.vertexIndices().size(); cornerIndex++) {
            result.put(inputs.vertexIndices().get(cornerIndex), finalCorners.get(cornerIndex));
        }
        return result;
    }
}
