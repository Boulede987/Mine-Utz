package com.mineutze.worldgen.regionsolve.topology.placement;

import com.mineutze.worldgen.regionsolve.topology.Point2D;

import com.mineutze.worldgen.regionsolve.topology.mesh.HexMeshBuilder;

import com.mineutze.worldgen.regionsolve.topology.mesh.FaceAdjacency;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Real, achievable realized-area range for ONE face given its neighbors' real fixed/
 * committed context - behind {@code WeightedVertexPlacement.achievableAreaRangeFor}, used
 * by {@link TriangleIdentitySizeAssignment} to hard-exclude a candidate biome whose ENTIRE
 * size range can't be met before the CSP even commits to it.
 */
final class AchievableAreaProbe {

    private AchievableAreaProbe() {
    }

    /**
     * Deliberately WITHOUT target-relative clamp (no target exists yet - min/max-fraction-
     * of-target bounds are no-ops for a face absent from {@code targetAreaByFace}), leaving
     * only ABSOLUTE clamps (simplicity, minimum edge length, max-displacement fraction)
     * active.
     *
     * <p><b>{@code probeMinWidthBlocks}/{@code probeMaxWidthBlocks} must be REAL, catalog-
     * derived bounds</b> (e.g. smallest/largest width across every biome the caller could
     * assign here), not arbitrary extremes - an astronomically large probe weight silently
     * collapses to the same value regardless of real neighbor context. See DECISIONS.md
     * (undated, pre-2026-09-11).
     *
     * <p>Two single-shot evaluations, not a full calibration loop - no target to iterate
     * toward yet, only "how far can this face's weight push its boundary before absolute
     * clamps (or real neighbor resistance) stop it." Real, not guessed: increasing one
     * site's weight, holding every other fixed, strictly increases its own realized area
     * (documented monotonicity property of this power diagram - see {@link
     * WeightedVertexPlacement} class doc) - so the two probe extremes genuinely bound
     * everything achievable between them, no sweep needed.
     *
     * <p>Also consults already-committed VERTEX positions, not just committed WIDTHS - an
     * encased face's estimate must reflect its TRUE remaining freedom (often none), so
     * {@code TriangleIdentitySizeAssignment} can correctly exclude a biome whose range
     * can't be met. See DECISIONS.md 2026-09-11 "achievableAreaRangeFor now consults
     * committed VERTEX positions."
     */
    static WeightedVertexPlacement.AreaRange rangeFor(
        HexMeshBuilder.HexMesh mesh, FaceAdjacency.Adjacency adjacency, int faceIndex,
        Map<Integer, Integer> committedWidthBlocksByFace, Map<Integer, Point2D> committedVertexPositions,
        double baselineCircumradiusBlocks, double minEdgeLengthBlocks, int probeMinWidthBlocks, int probeMaxWidthBlocks
    ) {
        Map<Integer, Point2D> centers = MeshFaceGeometry.centersOf(mesh);
        List<int[]> triangles = adjacency.mutuallyAdjacentFaceTriples(centers.keySet());
        double baselineWeight = baselineCircumradiusBlocks * baselineCircumradiusBlocks;

        double probeLowWeight = PowerDiagramMath.powerWeight(probeMinWidthBlocks);
        double probeHighWeight = PowerDiagramMath.powerWeight(probeMaxWidthBlocks);

        double minArea = probedAreaOf(mesh, centers, triangles, faceIndex, committedWidthBlocksByFace, committedVertexPositions, baselineWeight, baselineCircumradiusBlocks, minEdgeLengthBlocks, probeLowWeight);
        double maxArea = probedAreaOf(mesh, centers, triangles, faceIndex, committedWidthBlocksByFace, committedVertexPositions, baselineWeight, baselineCircumradiusBlocks, minEdgeLengthBlocks, probeHighWeight);
        return new WeightedVertexPlacement.AreaRange(minArea, maxArea);
    }

    private static double probedAreaOf(
        HexMeshBuilder.HexMesh mesh, Map<Integer, Point2D> centers, List<int[]> triangles, int faceIndex,
        Map<Integer, Integer> committedWidthBlocksByFace, Map<Integer, Point2D> committedVertexPositions, double baselineWeight, double baselineCircumradiusBlocks,
        double minEdgeLengthBlocks, double probeWeight
    ) {
        Map<Integer, Double> weightByFace = new HashMap<>();
        for (int otherFace : centers.keySet()) {
            if (otherFace == faceIndex) {
                weightByFace.put(otherFace, probeWeight);
                continue;
            }
            Integer committedWidth = committedWidthBlocksByFace.get(otherFace);
            weightByFace.put(otherFace, committedWidth != null ? PowerDiagramMath.powerWeight(committedWidth) : baselineWeight);
        }
        Map<Integer, Point2D> displaced = JointVertexGeometry.vertexPositionsFor(
            mesh, centers, weightByFace, triangles, minEdgeLengthBlocks, Map.of(), Map.of(), baselineCircumradiusBlocks, committedVertexPositions
        );
        return MeshFaceGeometry.realizedAreasOf(mesh, displaced, Set.of(faceIndex)).get(faceIndex);
    }
}
