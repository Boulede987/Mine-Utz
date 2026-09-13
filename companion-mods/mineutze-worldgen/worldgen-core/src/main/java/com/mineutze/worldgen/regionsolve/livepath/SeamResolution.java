package com.mineutze.worldgen.regionsolve.livepath;

import com.mineutze.worldgen.regionsolve.topology.Point2D;

import com.mineutze.worldgen.regionsolve.topology.mesh.HexMeshBuilder;

import com.mineutze.worldgen.regionsolve.topology.mesh.FaceAdjacency;

import com.mineutze.worldgen.regionsolve.topology.identity.TriangleIdentitySizeAssignment;

import com.mineutze.worldgen.regionsolve.topology.placement.WeightedVertexPlacement;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * PROTOTYPE — not yet wired into {@link TriangleFieldGeometryRealization#realizeCluster}.
 * See {@code doc/decisions/2026-09-13-symmetric-batch-boundary-negotiation.md} ("Candidate
 * C") for the full design this implements.
 *
 * <p>Resolves a SEAM — a set of faces straddling two independently-solved, already-
 * PERMANENT interiors — as ONE joint identity+size+geometry call. Not a new mechanism:
 * the exact same {@link TriangleIdentitySizeAssignment#solve} and {@link
 * WeightedVertexPlacement#sequentiallyRealizedVertexPositions} a single cell's own
 * interior already uses, just invoked over the seam's own free-face set with BOTH sides'
 * interiors as real fixed context instead of only one side's. This is the whole point:
 * given the same two interiors and the same seam face set, the result must not depend on
 * which side was discovered/solved first — see {@code SeamResolutionTest} for the direct
 * verification (order-swap, both which side is passed first AND which order the seam's
 * own face set was built in).
 */
final class SeamResolution {

    /**
     * One already-solved, PERMANENT side's own interior. {@code interiorAssignments} is
     * every interior face's real, final identity+size (never a boundary face — a boundary
     * face's own preview is a genuinely separate, still-open concern, not fed in here).
     * {@code interiorVertexPositions} is only the vertices fully owned by three interior
     * faces (a vertex touching even one boundary face is correctly absent — still open,
     * same "commit once all 3 owners known" rule the mesh already uses everywhere else).
     */
    record SideContext(Map<Integer, TriangleIdentitySizeAssignment.Assignment> interiorAssignments, Map<Integer, Point2D> interiorVertexPositions) {
    }

    /** The seam's own resolved identity+size+geometry — everything {@code seamFreeFaceIndices} asked for, nothing about either side's interior (that's already permanent, unchanged, not returned again). */
    record SeamOutcome(Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments, Map<Integer, Point2D> positions) {
    }

    private SeamResolution() {
    }

    static SeamOutcome resolve(
        HexMeshBuilder.HexMesh mesh, FaceAdjacency.Adjacency adjacency, BiomeGraph graph, long worldSeed, double seamAreaBlocks2,
        double baselineCircumradiusBlocks, double minEdgeLengthBlocks, Set<Integer> seamFreeFaceIndices, SideContext sideA, SideContext sideB
    ) {
        Map<Integer, BiomeId> fixedFaceBiomes = new LinkedHashMap<>();
        Map<Integer, Integer> committedWidthBlocksByFace = new LinkedHashMap<>();
        Map<Integer, Point2D> committedVertexPositions = new LinkedHashMap<>();
        mergeInto(sideA, fixedFaceBiomes, committedWidthBlocksByFace, committedVertexPositions);
        mergeInto(sideB, fixedFaceBiomes, committedWidthBlocksByFace, committedVertexPositions);

        Map<Integer, TriangleIdentitySizeAssignment.Assignment> seamAssignments = TriangleIdentitySizeAssignment.solve(
            adjacency, seamFreeFaceIndices, fixedFaceBiomes, graph, worldSeed, seamAreaBlocks2, committedWidthBlocksByFace,
            mesh, baselineCircumradiusBlocks, minEdgeLengthBlocks, committedVertexPositions
        ).orElseThrow(() -> new IllegalStateException(
            "SeamResolution.resolve: no legal identity assignment exists for this seam given both sides' real permanent "
                + "interiors - a genuine dead end, not expected for a well-formed scenario. seamFaceCount="
                + seamFreeFaceIndices.size() + ", fixedFaceCount=" + fixedFaceBiomes.size()
        ));

        Map<Integer, Point2D> seamPositions = WeightedVertexPlacement.sequentiallyRealizedVertexPositions(
            mesh, adjacency, seamAssignments, baselineCircumradiusBlocks, minEdgeLengthBlocks, committedWidthBlocksByFace, committedVertexPositions, worldSeed
        );

        return new SeamOutcome(seamAssignments, seamPositions);
    }

    private static void mergeInto(
        SideContext side, Map<Integer, BiomeId> fixedFaceBiomes, Map<Integer, Integer> committedWidthBlocksByFace, Map<Integer, Point2D> committedVertexPositions
    ) {
        side.interiorAssignments().forEach((faceIndex, assignment) -> {
            fixedFaceBiomes.put(faceIndex, assignment.biome());
            committedWidthBlocksByFace.put(faceIndex, assignment.widthBlocks());
        });
        committedVertexPositions.putAll(side.interiorVertexPositions());
    }
}
