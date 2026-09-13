package com.mineutze.worldgen.regionsolve.topology.mesh;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Face-to-face adjacency over any {@link MeshFace} list: groups faces by shared edge, an
 * edge shared by 2 faces links them as neighbors, shared by exactly 1 marks that face
 * hull-touching. Never inspects a face's concrete shape — the neighbor cap a caller ends
 * up with (at most 6 for {@link HexFace}, the only real shape production meshes with
 * today) is a property of whichever {@code MeshFace} was passed in, not enforced here;
 * nothing named "triangle" here means the mesh is triangular faces — {@link
 * Adjacency#mutuallyAdjacentFaceTriples} finds GROUPS OF THREE mutually-adjacent faces (a
 * graph-theory triple, one per real mesh vertex), an entirely different concept from a
 * face's polygon shape.
 *
 * <p>{@code hullTouchingFaceIndices} matters for a windowed query ({@link SeedGrid}/{@link
 * HexGrid}): a face with a hull edge is missing real neighbor context existing just
 * outside whatever box was meshed - a caller building its mesh with margin should treat
 * any face inside {@code hullTouchingFaceIndices} as untrustworthy (not enough margin for
 * full context), not act on its incomplete neighbor count as final.
 */
public final class FaceAdjacency {

    public record Adjacency(Map<Integer, Set<Integer>> neighborFaceIndices, Set<Integer> hullTouchingFaceIndices) {

        /**
         * Every group of 3 mutually-adjacent faces among {@code faceIndices} - each is
         * exactly one real mesh vertex on a hex mesh, by {@link HexMeshBuilder}'s
         * vertex-degree-3 guarantee: EVERY vertex has EXACTLY 3 faces meeting there, all
         * pairwise adjacent, no other 3-way mutual-adjacency configuration can occur.
         * Finding every {@code (A, B, C)} where A-B, A-C, B-C are ALL edges in this
         * adjacency graph is exactly finding every real vertex among {@code faceIndices},
         * no separate vertex data structure needed. Each triple reported once, canonically
         * ordered ({@code a < b < c}).
         *
         * <p>Shared by {@link TriangleIdentitySizeAssignment}'s ternary constraint-
         * propagation (free faces only, during backtracking) and {@link
         * WeightedVertexPlacement}'s power-diagram vertex placement (every face with a real
         * position, after CSP has run) - both need "which face-triples correspond to a
         * real mesh vertex," over different face-index subsets.
         */
        public List<int[]> mutuallyAdjacentFaceTriples(Set<Integer> faceIndices) {
            List<int[]> triples = new ArrayList<>();
            for (int firstFace : faceIndices) {
                triples.addAll(faceTriplesAnchoredAt(firstFace, faceIndices));
            }
            return triples;
        }

        /**
         * Every real mesh-vertex triple where {@code firstFace} is the LOWEST-indexed
         * member (canonical-order anchor, so each real triple found once across the whole
         * {@link #mutuallyAdjacentFaceTriples} scan) - other two members both HIGHER-
         * indexed neighbors of {@code firstFace}, ALSO mutually adjacent to each other.
         */
        private List<int[]> faceTriplesAnchoredAt(int firstFace, Set<Integer> faceIndices) {
            List<Integer> higherNeighbors = neighborFaceIndices.getOrDefault(firstFace, Set.of()).stream()
                .filter(neighborCandidate -> neighborCandidate > firstFace && faceIndices.contains(neighborCandidate))
                .sorted()
                .toList();

            List<int[]> triples = new ArrayList<>();
            for (int higherIndexA = 0; higherIndexA < higherNeighbors.size(); higherIndexA++) {
                for (int higherIndexB = higherIndexA + 1; higherIndexB < higherNeighbors.size(); higherIndexB++) {
                    int secondFace = higherNeighbors.get(higherIndexA);
                    int thirdFace = higherNeighbors.get(higherIndexB);
                    if (neighborFaceIndices.getOrDefault(secondFace, Set.of()).contains(thirdFace)) {
                        triples.add(new int[]{firstFace, secondFace, thirdFace});
                    }
                }
            }
            return triples;
        }
    }

    private FaceAdjacency() {
    }

    /**
     * The one mesh-vertex index shared by all 3 faces in a real vertex triple (see {@link
     * Adjacency#mutuallyAdjacentFaceTriples}) — guaranteed to exist and be unique by the
     * hex mesh's vertex-degree-3 property. Shared by {@link WeightedVertexPlacement}
     * (computing a vertex's displaced position) and {@code TriangleFieldGeometryRealization}
     * (deciding which vertices are eligible to commit to the global vertex store) — both
     * need the same "which raw vertex index does this triple correspond to" lookup.
     */
    public static int sharedVertexOf(HexMeshBuilder.HexMesh mesh, int[] faceTriple) {
        Set<Integer> shared = new HashSet<>(mesh.faces().get(faceTriple[0]).vertexIndices());
        shared.retainAll(mesh.faces().get(faceTriple[1]).vertexIndices());
        shared.retainAll(mesh.faces().get(faceTriple[2]).vertexIndices());
        if (shared.size() != 1) {
            throw new IllegalStateException(
                "FaceAdjacency.sharedVertexOf: 3 mutually-adjacent faces " + java.util.Arrays.toString(faceTriple) + " share "
                    + shared.size() + " vertice(s), expected exactly 1 - not a valid hex mesh vertex triple"
            );
        }
        return shared.iterator().next();
    }

    /**
     * The 2 mesh-vertex indices shared by two adjacent faces (the endpoints of their
     * common edge) — guaranteed to exist and be exactly 2 by the same hex-mesh structure
     * {@link #sharedVertexOf} relies on for triples. Used wherever a shared edge's real
     * (possibly displaced) length is needed between two specific neighboring faces.
     */
    public static int[] sharedVertexIndicesOf(HexMeshBuilder.HexMesh mesh, int faceA, int faceB) {
        Set<Integer> shared = new HashSet<>(mesh.faces().get(faceA).vertexIndices());
        shared.retainAll(mesh.faces().get(faceB).vertexIndices());
        if (shared.size() != 2) {
            throw new IllegalStateException(
                "FaceAdjacency.sharedVertexIndicesOf: faces " + faceA + " and " + faceB + " share "
                    + shared.size() + " vertice(s), expected exactly 2 - not a valid adjacent face pair"
            );
        }
        int[] result = new int[2];
        int resultIndex = 0;
        for (int vertexIndex : shared) {
            result[resultIndex++] = vertexIndex;
        }
        return result;
    }

    public static Adjacency of(List<? extends MeshFace> faces) {
        Map<NodeEdge, List<Integer>> facesByEdge = groupFacesByEdge(faces);
        Map<Integer, Set<Integer>> neighbors = emptyNeighborSets(faces.size());
        Set<Integer> hullTouching = new LinkedHashSet<>();

        for (Map.Entry<NodeEdge, List<Integer>> entry : facesByEdge.entrySet()) {
            classifyEdge(entry.getKey(), entry.getValue(), neighbors, hullTouching);
        }

        return new Adjacency(Map.copyOf(neighbors), Set.copyOf(hullTouching));
    }

    private static Map<NodeEdge, List<Integer>> groupFacesByEdge(List<? extends MeshFace> faces) {
        Map<NodeEdge, List<Integer>> facesByEdge = new LinkedHashMap<>();
        for (int faceIndex = 0; faceIndex < faces.size(); faceIndex++) {
            for (NodeEdge edge : faces.get(faceIndex).edges()) {
                facesByEdge.computeIfAbsent(edge, key -> new ArrayList<>()).add(faceIndex);
            }
        }
        return facesByEdge;
    }

    private static Map<Integer, Set<Integer>> emptyNeighborSets(int faceCount) {
        Map<Integer, Set<Integer>> neighbors = new LinkedHashMap<>();
        for (int faceIndex = 0; faceIndex < faceCount; faceIndex++) {
            neighbors.put(faceIndex, new LinkedHashSet<>());
        }
        return neighbors;
    }

    /** One edge shared by 2 faces links them as neighbors; shared by exactly 1 marks that face hull-touching (see this class's own doc for why); anything else means {@code faces} isn't a valid mesh. */
    private static void classifyEdge(NodeEdge edge, List<Integer> sharingFaces, Map<Integer, Set<Integer>> neighbors, Set<Integer> hullTouching) {
        if (sharingFaces.size() == 2) {
            int first = sharingFaces.get(0);
            int second = sharingFaces.get(1);
            neighbors.get(first).add(second);
            neighbors.get(second).add(first);
        } else if (sharingFaces.size() == 1) {
            hullTouching.add(sharingFaces.get(0));
        } else {
            throw new IllegalStateException(
                "edge " + edge + " shared by " + sharingFaces.size() + " faces - not a valid mesh"
            );
        }
    }
}
