package com.mineutze.worldgen.regionsolve.topology.mesh;

import com.mineutze.worldgen.regionsolve.topology.Point2D;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a {@link HexMesh} over a padded box: one {@link HexFace} per {@link HexGrid}
 * axial position, corners pooled into one shared vertex list so two faces sharing a
 * corner reference the SAME list entry, not each computing their own (possibly floating-
 * point-divergent) copy — shared identity is what makes edge-displacement watertightness
 * free later (a shared vertex never moves independently for two faces, only one copy
 * exists).
 *
 * <p>Corner positions pooled by rounding to {@link #DEDUP_PRECISION} block precision, far
 * finer than any meaningful geometric distance at this lattice's scale — two faces'
 * closed-form corner computations for the mathematically-identical shared point can only
 * differ by floating-point noise many orders of magnitude below that.
 */
public final class HexMeshBuilder {

    private static final double DEDUP_PRECISION = 1.0e6;

    private static final int CORNERS_PER_HEX_FACE = 6;

    /** A regular hexagon's corners are evenly spaced by this many degrees around its center. */
    private static final double DEGREES_PER_CORNER = 60.0;

    /** Rotates corner 0 off the positive-x axis so the hex reads POINTY-TOP (a flat corner straight up/down, not a flat edge) - see {@link #cornerOf}'s own doc. */
    private static final double POINTY_TOP_ROTATION_OFFSET_DEGREES = -30.0;

    private HexMeshBuilder() {
    }

    public record HexMesh(List<HexFace> faces, List<Point2D> vertices) {
    }

    private record CornerKey(long roundedX, long roundedZ) {
    }

    public static HexMesh build(double sizeBlocks, int minX, int minZ, int maxX, int maxZ) {
        List<HexGrid.AxialPos> positions = HexGrid.axialPositionsIn(sizeBlocks, minX, minZ, maxX, maxZ);

        Map<CornerKey, Integer> vertexIndexByKey = new LinkedHashMap<>();
        List<Point2D> vertices = new ArrayList<>();
        List<HexFace> faces = new ArrayList<>(positions.size());

        for (HexGrid.AxialPos axialPos : positions) {
            faces.add(buildFace(axialPos, sizeBlocks, vertexIndexByKey, vertices));
        }

        return new HexMesh(List.copyOf(faces), List.copyOf(vertices));
    }

    /** One hex face's own 6 corners, pooling each into the shared vertex list (see this class's own doc for why pooling is what makes edge-displacement watertightness free later). */
    private static HexFace buildFace(HexGrid.AxialPos axialPos, double sizeBlocks, Map<CornerKey, Integer> vertexIndexByKey, List<Point2D> vertices) {
        Point2D center = HexGrid.centerOf(axialPos, sizeBlocks);
        List<Integer> vertexIndices = new ArrayList<>(CORNERS_PER_HEX_FACE);
        for (int corner = 0; corner < CORNERS_PER_HEX_FACE; corner++) {
            Point2D cornerPoint = cornerOf(center, sizeBlocks, corner);
            vertexIndices.add(vertexIndexFor(cornerPoint, vertexIndexByKey, vertices));
        }
        return new HexFace(axialPos, vertexIndices);
    }

    /** Pointy-top corners, angularly ordered so consecutive indices are adjacent — corner {@code cornerIndex} sits at {@code DEGREES_PER_CORNER * cornerIndex + POINTY_TOP_ROTATION_OFFSET_DEGREES} degrees from center. */
    private static Point2D cornerOf(Point2D center, double sizeBlocks, int cornerIndex) {
        double angle = Math.toRadians(DEGREES_PER_CORNER * cornerIndex + POINTY_TOP_ROTATION_OFFSET_DEGREES);
        return new Point2D(center.x() + sizeBlocks * Math.cos(angle), center.z() + sizeBlocks * Math.sin(angle));
    }

    private static int vertexIndexFor(Point2D point, Map<CornerKey, Integer> vertexIndexByKey, List<Point2D> vertices) {
        CornerKey key = new CornerKey(Math.round(point.x() * DEDUP_PRECISION), Math.round(point.z() * DEDUP_PRECISION));
        Integer existing = vertexIndexByKey.get(key);
        if (existing != null) {
            return existing;
        }
        int newIndex = vertices.size();
        vertices.add(point);
        vertexIndexByKey.put(key, newIndex);
        return newIndex;
    }
}
