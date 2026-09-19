package com.mineutze.worldgen.regionsolve.topology.identity;

import com.mineutze.worldgen.regionsolve.topology.Point2D;
import com.mineutze.worldgen.regionsolve.topology.mesh.FaceAdjacency;
import com.mineutze.worldgen.regionsolve.topology.mesh.HexMeshBuilder;
import com.mineutze.worldgen.regionsolve.topology.placement.WeightedVertexPlacement;

import java.util.Map;

/**
 * The real, current mechanism (power-diagram / weighted Voronoi vertex placement) behind
 * {@link AchievableSizeRangeCalculator} - see that interface's own doc for why this sits
 * behind a contract instead of being called directly. Pure delegation to {@link
 * WeightedVertexPlacement#achievableAreaRangeFor} - zero behavior change from before this
 * extraction (2026-09-17).
 */
public final class PowerDiagramAchievableSizeRangeCalculator implements AchievableSizeRangeCalculator {

    public static final PowerDiagramAchievableSizeRangeCalculator INSTANCE = new PowerDiagramAchievableSizeRangeCalculator();

    @Override
    public AchievableWidthRange rangeFor(
        HexMeshBuilder.HexMesh mesh, FaceAdjacency.Adjacency adjacency, int faceIndex,
        Map<Integer, Integer> committedWidthBlocksByFace, Map<Integer, Point2D> committedVertexPositions,
        double baselineCircumradiusBlocks, double minEdgeLengthBlocks, int probeMinWidthBlocks, int probeMaxWidthBlocks
    ) {
        WeightedVertexPlacement.AreaRange areaRange = WeightedVertexPlacement.achievableAreaRangeFor(
            mesh, adjacency, faceIndex, committedWidthBlocksByFace, committedVertexPositions,
            baselineCircumradiusBlocks, minEdgeLengthBlocks, probeMinWidthBlocks, probeMaxWidthBlocks
        );
        return AchievableWidthRange.from(areaRange);
    }
}
