package com.mineutze.worldgen.regionsolve.topology.identity;

import com.mineutze.worldgen.regionsolve.topology.Point2D;
import com.mineutze.worldgen.regionsolve.topology.mesh.FaceAdjacency;
import com.mineutze.worldgen.regionsolve.topology.mesh.HexMeshBuilder;

import java.util.Map;

/**
 * One free face's real, achievable width range given its neighbors' committed context -
 * the "compute achievable range first, decide second" step {@link
 * TriangleIdentitySizeAssignment} depends on (see that class's own doc). An interface, not
 * a direct call, on purpose (2026-09-17 user direction): the current mechanism (power-
 * diagram vertex placement, {@link PowerDiagramAchievableSizeRangeCalculator}) is real
 * production code, but this session's own dead-end investigation found real evidence the
 * SIZE layer, not identity/adjacency, may be the dominant real cause (see {@code
 * com.mineutze.worldgen.reachability}'s own doc) - the mechanism itself may need
 * correcting later, and swapping it must not require touching {@link
 * TriangleIdentitySizeAssignment} or any tool built against this contract. Production and
 * reachability/verification tooling are meant to share the SAME interface and, by default,
 * the SAME real implementation, so a tool's findings are about the real mechanism, never a
 * re-guessed proxy of it.
 */
public interface AchievableSizeRangeCalculator {

    AchievableWidthRange rangeFor(
        HexMeshBuilder.HexMesh mesh, FaceAdjacency.Adjacency adjacency, int faceIndex,
        Map<Integer, Integer> committedWidthBlocksByFace, Map<Integer, Point2D> committedVertexPositions,
        double baselineCircumradiusBlocks, double minEdgeLengthBlocks, int probeMinWidthBlocks, int probeMaxWidthBlocks
    );
}
