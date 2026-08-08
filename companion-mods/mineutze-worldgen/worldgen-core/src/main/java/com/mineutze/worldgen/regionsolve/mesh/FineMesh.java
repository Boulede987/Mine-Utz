package com.mineutze.worldgen.regionsolve.mesh;

import com.mineutze.worldgen.regionsolve.topology.DelaunayTriangulation;
import com.mineutze.worldgen.regionsolve.topology.IndexedTriangle;
import com.mineutze.worldgen.regionsolve.topology.NodeEdge;
import com.mineutze.worldgen.regionsolve.topology.Point2D;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wraps {@link DelaunayTriangulation#triangulateFaces} over a
 * {@link FineMeshPointScatter} scatter with the adjacency structures every
 * later mesh pass needs: which faces border which other faces across a
 * shared edge, which faces surround a given vertex (for the diagonal-touch
 * check {@code MeshDiagonalPairs} runs), and one canonical {@link MeshEdge}
 * object per shared edge (so displacement can't ever disagree between two
 * faces about where their shared boundary sits). Face ids and vertex ids
 * are plain indices into {@link #faces()}/{@link #points()} — this class
 * never allocates its own identity type for either.
 */
public final class FineMesh {

    private final List<ScatteredPoint> points;
    private final List<IndexedTriangle> faces;
    private final Map<Integer, List<Integer>> faceAdjacency;
    private final Map<Integer, List<Integer>> vertexIncidentFaces;
    private final Map<NodeEdge, MeshEdge> edgesByVertexPair;

    private FineMesh(
        List<ScatteredPoint> points,
        List<IndexedTriangle> faces,
        Map<Integer, List<Integer>> faceAdjacency,
        Map<Integer, List<Integer>> vertexIncidentFaces,
        Map<NodeEdge, MeshEdge> edgesByVertexPair
    ) {
        this.points = points;
        this.faces = faces;
        this.faceAdjacency = faceAdjacency;
        this.vertexIncidentFaces = vertexIncidentFaces;
        this.edgesByVertexPair = edgesByVertexPair;
    }

    public static FineMesh build(List<ScatteredPoint> scatter) {
        List<Point2D> positions = scatter.stream().map(ScatteredPoint::position).toList();
        List<IndexedTriangle> faces = DelaunayTriangulation.triangulateFaces(positions);

        Map<NodeEdge, List<Integer>> facesByEdge = groupFacesByEdge(faces);
        Map<Integer, List<Integer>> faceAdjacency = buildFaceAdjacency(faces, facesByEdge);
        Map<Integer, List<Integer>> vertexIncidentFaces = buildVertexIncidentFaces(faces);
        Map<NodeEdge, MeshEdge> edgesByVertexPair = buildMeshEdges(facesByEdge);

        return new FineMesh(scatter, faces, faceAdjacency, vertexIncidentFaces, edgesByVertexPair);
    }

    private static Map<NodeEdge, List<Integer>> groupFacesByEdge(List<IndexedTriangle> faces) {
        Map<NodeEdge, List<Integer>> facesByEdge = new LinkedHashMap<>();
        for (int faceId = 0; faceId < faces.size(); faceId++) {
            for (NodeEdge edge : faces.get(faceId).edges()) {
                facesByEdge.computeIfAbsent(edge, key -> new ArrayList<>()).add(faceId);
            }
        }
        return facesByEdge;
    }

    private static Map<Integer, List<Integer>> buildFaceAdjacency(List<IndexedTriangle> faces, Map<NodeEdge, List<Integer>> facesByEdge) {
        Map<Integer, List<Integer>> adjacency = new LinkedHashMap<>();
        for (int faceId = 0; faceId < faces.size(); faceId++) {
            List<Integer> neighbors = new ArrayList<>();
            for (NodeEdge edge : faces.get(faceId).edges()) {
                for (int otherFaceId : facesByEdge.get(edge)) {
                    if (otherFaceId != faceId) {
                        neighbors.add(otherFaceId);
                    }
                }
            }
            adjacency.put(faceId, List.copyOf(neighbors));
        }
        return adjacency;
    }

    private static Map<Integer, List<Integer>> buildVertexIncidentFaces(List<IndexedTriangle> faces) {
        Map<Integer, List<Integer>> incident = new LinkedHashMap<>();
        for (int faceId = 0; faceId < faces.size(); faceId++) {
            IndexedTriangle face = faces.get(faceId);
            for (int vertexId : List.of(face.a(), face.b(), face.c())) {
                incident.computeIfAbsent(vertexId, key -> new ArrayList<>()).add(faceId);
            }
        }
        return incident;
    }

    private static Map<NodeEdge, MeshEdge> buildMeshEdges(Map<NodeEdge, List<Integer>> facesByEdge) {
        Map<NodeEdge, MeshEdge> edges = new LinkedHashMap<>();
        facesByEdge.forEach((edge, faceIds) -> edges.put(edge, new MeshEdge(edge, faceIds)));
        return edges;
    }

    public List<ScatteredPoint> points() {
        return points;
    }

    public Point2D position(int vertexId) {
        return points.get(vertexId).position();
    }

    public List<IndexedTriangle> faces() {
        return faces;
    }

    public IndexedTriangle face(int faceId) {
        return faces.get(faceId);
    }

    /** Faces sharing a real edge (not just a vertex) with the given face — up to 3 for a triangle, fewer at a mesh boundary. */
    public List<Integer> edgeAdjacentFaces(int faceId) {
        return faceAdjacency.getOrDefault(faceId, List.of());
    }

    /** Every face touching the given vertex, in face-id order (not yet angularly sorted — see {@code MeshDiagonalPairs} for that). */
    public List<Integer> incidentFaces(int vertexId) {
        return vertexIncidentFaces.getOrDefault(vertexId, List.of());
    }

    public MeshEdge edgeBetween(int vertexA, int vertexB) {
        return edgesByVertexPair.get(NodeEdge.of(vertexA, vertexB));
    }

    public Collection<MeshEdge> edges() {
        return edgesByVertexPair.values();
    }
}
