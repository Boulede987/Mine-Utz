package com.mineutze.worldgen.regionsolve.topology.placement;

import com.mineutze.worldgen.regionsolve.topology.HexGeometry;

import com.mineutze.worldgen.regionsolve.topology.Point2D;

import com.mineutze.worldgen.regionsolve.topology.mesh.HexMeshBuilder;

import com.mineutze.worldgen.regionsolve.topology.identity.TriangleIdentitySizeAssignment;

import com.mineutze.worldgen.regionsolve.topology.mesh.HexFace;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-face data prep shared by every {@link WeightedVertexPlacement} collaborator: a
 * face's own target/legal area from its CSP assignment, its own weight, its own lattice
 * center, and its own realized area from any position map. Zero placement-algorithm logic
 * here, only "read the mesh/assignments, compute a per-face number."
 */
final class MeshFaceGeometry {

    private MeshFaceGeometry() {
    }

    static Map<Integer, Double> targetAreasOf(Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments) {
        Map<Integer, Double> targetAreas = new HashMap<>();
        assignments.forEach((faceIndex, assignment) -> targetAreas.put(faceIndex, targetAreaBlocks2(assignment.widthBlocks())));
        return targetAreas;
    }

    /**
     * Per-face ABSOLUTE legal area bound, from each face's already pair-aware {@code
     * legalMinWidthBlocks}/{@code legalMaxWidthBlocks} (see {@link
     * TriangleIdentitySizeAssignment.Assignment} - already the correct fair-share bound for
     * a self-adjacent pair member, not the full biome range). {@code Integer.MIN_VALUE}/
     * {@code MAX_VALUE} ("unconstrained" sentinel, used by synthetic test assignments with
     * no real CSP-level legal-range concern) map to a fully permissive range, not a real
     * bound.
     */
    static Map<Integer, WeightedVertexPlacement.AreaRange> legalAreaRangesOf(Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments) {
        Map<Integer, WeightedVertexPlacement.AreaRange> legalAreaRanges = new HashMap<>();
        assignments.forEach((faceIndex, assignment) -> {
            double minArea = assignment.legalMinWidthBlocks() == Integer.MIN_VALUE ? 0.0 : targetAreaBlocks2(assignment.legalMinWidthBlocks());
            double maxArea = assignment.legalMaxWidthBlocks() == Integer.MAX_VALUE ? Double.MAX_VALUE : targetAreaBlocks2(assignment.legalMaxWidthBlocks());
            legalAreaRanges.put(faceIndex, new WeightedVertexPlacement.AreaRange(minArea, maxArea));
        });
        return legalAreaRanges;
    }

    /**
     * Treats a biome's assigned width as a regular hexagon's own corner-to-corner span
     * (same convention {@link PowerDiagramMath#powerWeight} uses): circumradius = width/2,
     * area = {@link HexGeometry#areaForCircumradius} of that circumradius.
     */
    static double targetAreaBlocks2(int widthBlocks) {
        return HexGeometry.areaForCircumradius(HexGeometry.circumradiusForWidth(widthBlocks));
    }

    /** Shoelace area of each face's own displaced boundary (straight corner-to-corner, no wiggle - wiggle is a separate, later, cosmetic-only step with zero net area effect, not this class's concern). */
    static Map<Integer, Double> realizedAreasOf(HexMeshBuilder.HexMesh mesh, Map<Integer, Point2D> displaced, Set<Integer> faceIndices) {
        Map<Integer, Double> areas = new HashMap<>();
        for (int faceIndex : faceIndices) {
            List<Integer> vertexIndices = mesh.faces().get(faceIndex).vertexIndices();
            List<Point2D> corners = new ArrayList<>();
            for (int vertexIndex : vertexIndices) {
                corners.add(cornerPosition(mesh, displaced, vertexIndex));
            }
            areas.put(faceIndex, HexPolygonSafety.shoelaceArea(corners));
        }
        return areas;
    }

    static Point2D cornerPosition(HexMeshBuilder.HexMesh mesh, Map<Integer, Point2D> displaced, int vertexIndex) {
        return displaced.getOrDefault(vertexIndex, mesh.vertices().get(vertexIndex));
    }

    static Map<Integer, Point2D> centersOf(HexMeshBuilder.HexMesh mesh) {
        Map<Integer, Point2D> centers = new HashMap<>();
        for (int faceIndex = 0; faceIndex < mesh.faces().size(); faceIndex++) {
            centers.put(faceIndex, centroidOf(mesh.faces().get(faceIndex), mesh.vertices()));
        }
        return centers;
    }

    /** A regular hex face's own centroid IS its axial lattice center exactly (the mesh is still perfectly regular/undisplaced at this point - displacement is {@link WeightedVertexPlacement}'s own output, not an input). */
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
     * earlier batch's decision for this same physical face) wins over the generic {@code
     * baselineCircumradiusBlocks} placeholder — fix for the cross-region seam bug {@code
     * WeightedVertexPlacement.displacedVertexPositions}'s store-aware overload describes.
     * A face with neither (true hull-touching, no context) falls back to baseline,
     * unchanged from before this class became store-aware.
     */
    static Map<Integer, Double> weightsOf(
        HexMeshBuilder.HexMesh mesh, Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments,
        Map<Integer, Integer> committedWidthBlocksByFace, double baselineCircumradiusBlocks
    ) {
        double baselineWeight = baselineCircumradiusBlocks * baselineCircumradiusBlocks;
        Map<Integer, Double> weights = new HashMap<>();
        for (int faceIndex = 0; faceIndex < mesh.faces().size(); faceIndex++) {
            TriangleIdentitySizeAssignment.Assignment assignment = assignments.get(faceIndex);
            if (assignment != null) {
                weights.put(faceIndex, PowerDiagramMath.powerWeight(assignment.widthBlocks()));
                continue;
            }
            Integer committedWidth = committedWidthBlocksByFace.get(faceIndex);
            weights.put(faceIndex, committedWidth != null ? PowerDiagramMath.powerWeight(committedWidth) : baselineWeight);
        }
        return weights;
    }
}
