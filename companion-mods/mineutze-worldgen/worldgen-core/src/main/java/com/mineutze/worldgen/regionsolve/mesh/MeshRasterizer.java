package com.mineutze.worldgen.regionsolve.mesh;

import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.regionsolve.MacroCellPos;
import com.mineutze.worldgen.regionsolve.RegionSolveInput;
import com.mineutze.worldgen.regionsolve.WorldgenTrace;
import com.mineutze.worldgen.regionsolve.topology.IndexedTriangle;
import com.mineutze.worldgen.regionsolve.topology.Point2D;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The bridge back from continuous mesh geometry to the discrete
 * {@code Map<MacroCellPos,BiomeId>} every downstream consumer (density
 * functions, the export CLI, {@code SeedRegionCache}) is hard-typed to —
 * see the mesh-rewrite plan's "scope boundary": the mesh only exists
 * inside this geometry stage, nothing else in the codebase needs to know
 * it happened.
 *
 * <p>Two-tier lookup per macro-cell center, cheapest case first: if the
 * (undisplaced) triangle containing the cell's center has no displaced
 * vertex at all, its identity is exact and correct with zero approximation
 * — most cells are patch-interior, far from any boundary, and take this
 * path. Only a cell whose containing triangle actually touches a displaced
 * vertex pays for a real point-in-(displaced)-triangle check, first
 * against that triangle's own displaced shape, then its edge-neighbors' —
 * displacement is small and local, so the true answer is always one of
 * those. A cell that still resolves nothing (degenerate edge case, or
 * outside the mesh's convex hull despite the boundary ring) falls back to
 * the nearest triangle centroid and logs it via {@link WorldgenTrace} —
 * every other last-resort tier in this pipeline logs its forced picks the
 * same way, and a high fire rate here would mean the boundary ring itself
 * needs widening, not something to silently paper over.
 */
public final class MeshRasterizer {

    private MeshRasterizer() {
    }

    public static Map<MacroCellPos, BiomeId> rasterize(FineMesh mesh, MeshAssignment assignment, Map<Integer, Point2D> displaced, RegionSolveInput input) {
        Map<MacroCellPos, BiomeId> result = new LinkedHashMap<>();
        for (MacroCellPos cell : input.gridConfig().cellsInRegion(input.region(), input.worldSeed())) {
            Point2D cellPoint = new Point2D(cell.x(), cell.z());
            result.put(cell, resolveCellBiome(mesh, assignment, displaced, cellPoint));
        }
        return result;
    }

    private static BiomeId resolveCellBiome(FineMesh mesh, MeshAssignment assignment, Map<Integer, Point2D> displaced, Point2D cellPoint) {
        Optional<Integer> undisplacedContainer = locateInUndisplacedMesh(mesh, cellPoint);
        if (undisplacedContainer.isPresent()) {
            int faceId = undisplacedContainer.get();
            if (!touchesAnyDisplacedVertex(mesh.face(faceId), displaced)) {
                BiomeId fastPathBiome = assignment.faceIdentity().get(faceId);
                if (fastPathBiome != null) {
                    return fastPathBiome;
                }
            } else {
                Optional<BiomeId> slowPathBiome = locateViaDisplacedNeighborhood(mesh, assignment, displaced, faceId, cellPoint);
                if (slowPathBiome.isPresent()) {
                    return slowPathBiome.get();
                }
            }
        }

        BiomeId fallback = nearestDisplacedCentroidBiome(mesh, assignment, displaced, cellPoint);
        WorldgenTrace.log("MESH RASTERIZE fallback nearest-centroid for " + cellPoint + " = " + fallback);
        return fallback;
    }

    /**
     * Picks the containing face whose CENTROID is nearest the point, not
     * just the first one found by ascending id. This matters a lot more
     * than it looks: {@code FineMeshPointScatter}'s germ and filler points
     * are deliberately placed at exact integer macro-cell coordinates (so
     * a biome's identity/growth target lines up cleanly with the grid this
     * eventually rasterizes back to) — which means a macro-cell's own
     * query point very often sits EXACTLY on a mesh vertex where several
     * triangles fan together. The same-side containment test correctly
     * reports every one of those fan triangles as "containing" a point
     * exactly on their shared vertex (zero cross product is neither
     * negative nor positive); picking "first by id" in that case is
     * arbitrary and unrelated to which face actually owns that area,
     * which fragmented output into a salt-and-pepper mess across entire
     * regions before this fix — every filler/germ-coincident cell
     * effectively got a near-random face. Nearest-centroid resolves the
     * ambiguity the same principled way every ordinary fallback in this
     * pipeline already does.
     */
    private static Optional<Integer> locateInUndisplacedMesh(FineMesh mesh, Point2D point) {
        int bestFaceId = -1;
        double bestDistanceSquared = Double.MAX_VALUE;
        for (int faceId = 0; faceId < mesh.faces().size(); faceId++) {
            IndexedTriangle face = mesh.face(faceId);
            Point2D a = mesh.position(face.a());
            Point2D b = mesh.position(face.b());
            Point2D c = mesh.position(face.c());
            if (!containsPoint(a, b, c, point)) {
                continue;
            }
            double distanceSquared = distanceSquaredToCentroid(a, b, c, point);
            if (distanceSquared < bestDistanceSquared) {
                bestDistanceSquared = distanceSquared;
                bestFaceId = faceId;
            }
        }
        return bestFaceId < 0 ? Optional.empty() : Optional.of(bestFaceId);
    }

    private static double distanceSquaredToCentroid(Point2D a, Point2D b, Point2D c, Point2D point) {
        double centroidX = (a.x() + b.x() + c.x()) / 3.0;
        double centroidZ = (a.z() + b.z() + c.z()) / 3.0;
        double dx = centroidX - point.x();
        double dz = centroidZ - point.z();
        return dx * dx + dz * dz;
    }

    private static boolean touchesAnyDisplacedVertex(IndexedTriangle face, Map<Integer, Point2D> displaced) {
        return displaced.containsKey(face.a()) || displaced.containsKey(face.b()) || displaced.containsKey(face.c());
    }

    private static Optional<BiomeId> locateViaDisplacedNeighborhood(FineMesh mesh, MeshAssignment assignment, Map<Integer, Point2D> displaced, int undisplacedFaceId, Point2D point) {
        if (containsDisplacedFace(mesh, displaced, undisplacedFaceId, point)) {
            return Optional.ofNullable(assignment.faceIdentity().get(undisplacedFaceId));
        }
        for (int neighborFaceId : mesh.edgeAdjacentFaces(undisplacedFaceId)) {
            if (containsDisplacedFace(mesh, displaced, neighborFaceId, point)) {
                return Optional.ofNullable(assignment.faceIdentity().get(neighborFaceId));
            }
        }
        return Optional.empty();
    }

    private static boolean containsDisplacedFace(FineMesh mesh, Map<Integer, Point2D> displaced, int faceId, Point2D point) {
        IndexedTriangle face = mesh.face(faceId);
        Point2D a = displaced.getOrDefault(face.a(), mesh.position(face.a()));
        Point2D b = displaced.getOrDefault(face.b(), mesh.position(face.b()));
        Point2D c = displaced.getOrDefault(face.c(), mesh.position(face.c()));
        return containsPoint(a, b, c, point);
    }

    private static BiomeId nearestDisplacedCentroidBiome(FineMesh mesh, MeshAssignment assignment, Map<Integer, Point2D> displaced, Point2D point) {
        int nearestFaceId = -1;
        double nearestDistanceSquared = Double.MAX_VALUE;
        for (int faceId : assignment.faceIdentity().keySet()) {
            IndexedTriangle face = mesh.face(faceId);
            Point2D a = displaced.getOrDefault(face.a(), mesh.position(face.a()));
            Point2D b = displaced.getOrDefault(face.b(), mesh.position(face.b()));
            Point2D c = displaced.getOrDefault(face.c(), mesh.position(face.c()));
            double centroidX = (a.x() + b.x() + c.x()) / 3.0;
            double centroidZ = (a.z() + b.z() + c.z()) / 3.0;
            double dx = centroidX - point.x();
            double dz = centroidZ - point.z();
            double distanceSquared = dx * dx + dz * dz;
            if (distanceSquared < nearestDistanceSquared) {
                nearestDistanceSquared = distanceSquared;
                nearestFaceId = faceId;
            }
        }
        if (nearestFaceId < 0) {
            throw new IllegalStateException("MeshRasterizer: no assigned face exists anywhere in the mesh - MeshGapFiller's full-coverage guarantee must have been violated upstream, a real bug, not a legitimately unsolvable region.");
        }
        return assignment.faceIdentity().get(nearestFaceId);
    }

    /** Standard same-sign-of-cross-product test, tolerant of either triangle winding order. */
    private static boolean containsPoint(Point2D a, Point2D b, Point2D c, Point2D point) {
        double d1 = sign(point, a, b);
        double d2 = sign(point, b, c);
        double d3 = sign(point, c, a);
        boolean hasNegative = d1 < 0 || d2 < 0 || d3 < 0;
        boolean hasPositive = d1 > 0 || d2 > 0 || d3 > 0;
        return !(hasNegative && hasPositive);
    }

    private static double sign(Point2D p1, Point2D p2, Point2D p3) {
        return (p1.x() - p3.x()) * (p2.z() - p3.z()) - (p2.x() - p3.x()) * (p1.z() - p3.z());
    }
}
