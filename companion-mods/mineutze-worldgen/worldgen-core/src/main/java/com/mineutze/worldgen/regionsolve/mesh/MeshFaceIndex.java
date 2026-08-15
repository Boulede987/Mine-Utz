package com.mineutze.worldgen.regionsolve.mesh;

import com.mineutze.worldgen.regionsolve.topology.IndexedTriangle;
import com.mineutze.worldgen.regionsolve.topology.Point2D;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Uniform-grid spatial index over a {@link FineMesh}'s faces, so {@link
 * MeshRasterizer} doesn't have to linear-scan every face for every
 * macro-cell query — that scan is O(cells x faces), which was fine at a
 * few hundred cells/faces per region but became a multi-minute stall per
 * region once macro cells shrank enough to push both counts into the tens
 * of thousands (measured directly: 196s for one region at
 * macroCellSizeBlocks=4/regionSizeCellsPerSide=512, before this existed).
 *
 * <p>A face is registered in every bucket its bounding box overlaps, not
 * just its centroid's bucket — a query point's own bucket is then
 * GUARANTEED to list every face that could possibly contain it: if a face
 * contains a point, the point lies within that face's bounding box too, so
 * the point's bucket necessarily overlapped that bbox during construction
 * and the face was registered there. No neighbor-bucket expansion needed
 * at query time, and no risk of a false negative.
 *
 * <p>Bucket grid size is self-tuning from the mesh's own face count and
 * bounding box (target ~{@link #TARGET_FACES_PER_BUCKET} faces per bucket
 * on average) rather than coupled to any germ-spacing or fine-mesh
 * constant — keeps this class correct regardless of how finely {@code
 * FineMeshPointScatter} is tuned, now or later.
 */
final class MeshFaceIndex {

    private static final int TARGET_FACES_PER_BUCKET = 4;

    private final double originX;
    private final double originZ;
    private final double bucketSize;
    private final Map<Long, List<Integer>> facesByBucket;

    private MeshFaceIndex(double originX, double originZ, double bucketSize, Map<Long, List<Integer>> facesByBucket) {
        this.originX = originX;
        this.originZ = originZ;
        this.bucketSize = bucketSize;
        this.facesByBucket = facesByBucket;
    }

    static MeshFaceIndex build(FineMesh mesh) {
        List<IndexedTriangle> faces = mesh.faces();

        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (ScatteredPoint point : mesh.points()) {
            minX = Math.min(minX, point.position().x());
            maxX = Math.max(maxX, point.position().x());
            minZ = Math.min(minZ, point.position().z());
            maxZ = Math.max(maxZ, point.position().z());
        }
        double span = Math.max(1.0, Math.max(maxX - minX, maxZ - minZ));

        int targetBucketCount = Math.max(1, faces.size() / TARGET_FACES_PER_BUCKET);
        int bucketsPerSide = Math.max(1, (int) Math.ceil(Math.sqrt(targetBucketCount)));
        double bucketSize = span / bucketsPerSide;

        Map<Long, List<Integer>> facesByBucket = new HashMap<>();
        for (int faceId = 0; faceId < faces.size(); faceId++) {
            IndexedTriangle face = faces.get(faceId);
            Point2D a = mesh.position(face.a());
            Point2D b = mesh.position(face.b());
            Point2D c = mesh.position(face.c());
            double faceMinX = Math.min(a.x(), Math.min(b.x(), c.x()));
            double faceMaxX = Math.max(a.x(), Math.max(b.x(), c.x()));
            double faceMinZ = Math.min(a.z(), Math.min(b.z(), c.z()));
            double faceMaxZ = Math.max(a.z(), Math.max(b.z(), c.z()));

            int bucketMinX = bucketIndex(faceMinX, minX, bucketSize);
            int bucketMaxX = bucketIndex(faceMaxX, minX, bucketSize);
            int bucketMinZ = bucketIndex(faceMinZ, minZ, bucketSize);
            int bucketMaxZ = bucketIndex(faceMaxZ, minZ, bucketSize);

            for (int bx = bucketMinX; bx <= bucketMaxX; bx++) {
                for (int bz = bucketMinZ; bz <= bucketMaxZ; bz++) {
                    facesByBucket.computeIfAbsent(bucketKey(bx, bz), key -> new ArrayList<>()).add(faceId);
                }
            }
        }

        return new MeshFaceIndex(minX, minZ, bucketSize, facesByBucket);
    }

    /** Every face whose bounding box overlaps {@code point}'s own bucket — a guaranteed superset of every face that could actually contain the point. */
    List<Integer> candidatesNear(Point2D point) {
        int bx = bucketIndex(point.x(), originX, bucketSize);
        int bz = bucketIndex(point.z(), originZ, bucketSize);
        return facesByBucket.getOrDefault(bucketKey(bx, bz), List.of());
    }

    private static int bucketIndex(double coord, double origin, double bucketSize) {
        return (int) Math.floor((coord - origin) / bucketSize);
    }

    private static long bucketKey(int bx, int bz) {
        return (((long) bx) << 32) ^ (bz & 0xFFFFFFFFL);
    }
}
