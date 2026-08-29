package com.mineutze.worldgen.regionsolve.topology;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a {@link HexMesh} over a padded box: one {@link HexFace} per
 * {@link HexGrid} axial position, corners pooled into one shared vertex list
 * so two faces sharing a corner reference the SAME list entry rather than
 * each computing their own (possibly floating-point-divergent) copy — that
 * shared identity is what makes edge-displacement watertightness free later
 * (a shared vertex never moves independently for two different faces,
 * because there's only ever one copy of it).
 *
 * <p>Corner positions are pooled by rounding to {@link #DEDUP_PRECISION}
 * block precision, far finer than any meaningful geometric distance at this
 * lattice's scale — two faces' own closed-form corner computations for the
 * mathematically-identical shared point can only ever differ by
 * floating-point noise many orders of magnitude below that.
 */
public final class HexMeshBuilder {

    private static final double DEDUP_PRECISION = 1.0e6;

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

        for (int seedIndex = 0; seedIndex < positions.size(); seedIndex++) {
            Point2D center = HexGrid.centerOf(positions.get(seedIndex), sizeBlocks);
            List<Integer> vertexIndices = new ArrayList<>(6);
            for (int corner = 0; corner < 6; corner++) {
                Point2D cornerPoint = cornerOf(center, sizeBlocks, corner);
                vertexIndices.add(vertexIndexFor(cornerPoint, vertexIndexByKey, vertices));
            }
            faces.add(new HexFace(seedIndex, vertexIndices));
        }

        return new HexMesh(List.copyOf(faces), List.copyOf(vertices));
    }

    /** Pointy-top corners, angularly ordered so consecutive indices are adjacent — corner {@code i} sits at {@code 60*i - 30} degrees from center. */
    private static Point2D cornerOf(Point2D center, double sizeBlocks, int cornerIndex) {
        double angle = Math.toRadians(60.0 * cornerIndex - 30.0);
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
