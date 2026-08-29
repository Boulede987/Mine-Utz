package com.mineutze.worldgen.regionsolve.topology;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Face-to-face adjacency over any {@link MeshFace} list: groups faces by
 * shared edge, an edge shared by 2 faces links them as neighbors, an edge
 * shared by exactly 1 marks that face hull-touching. This algorithm never
 * inspects a face's concrete shape — the neighbor cap any given caller ends
 * up with (at most 3 for {@link IndexedTriangle}, at most 6 for
 * {@link HexFace}) is a property of whichever {@code MeshFace} was passed
 * in, not something this class enforces itself.
 *
 * <p>{@code hullTouchingFaceIndices} matters for a windowed query ({@link
 * SeedGrid}/{@link HexGrid}): a face with a hull edge is missing real
 * neighbor context that exists just outside whatever box was meshed, and a
 * caller building its mesh with margin (points beyond the box it actually
 * cares about) should treat any face inside {@code hullTouchingFaceIndices}
 * as untrustworthy — not enough margin was used to give it full context —
 * rather than acting on its (incomplete) neighbor count as final.
 */
public final class TriangleAdjacency {

    public record Adjacency(Map<Integer, Set<Integer>> neighborFaceIndices, Set<Integer> hullTouchingFaceIndices) {
    }

    private TriangleAdjacency() {
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

    /** One edge shared by 2 faces links them as neighbors; shared by exactly 1 marks that face hull-touching (see this class's own doc for why); anything else means {@code faces} isn't a valid triangulation. */
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
                "edge " + edge + " shared by " + sharingFaces.size() + " faces - not a valid triangulation"
            );
        }
    }
}
