package com.mineutze.worldgen.regionsolve.topology;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Face-to-face adjacency over a {@link DelaunayTriangulation#triangulateFaces}
 * result. A triangle has exactly 3 edges, and any edge is shared by AT MOST
 * one other triangle (0 if it's on the point set's own convex hull, 1
 * otherwise) — so every face has AT MOST 3 real neighbors, always, by
 * construction. This is the exact invariant the whole triangle-mesh design
 * exists to guarantee (see doc/DESIGN.md's discussion on why triangles were
 * chosen over squares/other shapes: it caps how many other biomes any one
 * placement can ever be adjacent to) — {@code TriangleAdjacencyTest} asserts
 * it directly, not just relies on the geometry being right.
 *
 * <p>{@code hullTouchingFaceIndices} matters for a windowed query ({@link
 * SeedGrid}): a face with a hull edge is missing real neighbor context that
 * exists just outside whatever box was triangulated, and a caller building a
 * triangulation with margin (points beyond the box it actually cares about)
 * should treat any face inside {@code hullTouchingFaceIndices} as untrustworthy
 * — not enough margin was used to give it full context — rather than acting
 * on its (incomplete) 3-or-fewer neighbor count as final.
 */
public final class TriangleAdjacency {

    public record Adjacency(Map<Integer, Set<Integer>> neighborFaceIndices, Set<Integer> hullTouchingFaceIndices) {
    }

    private TriangleAdjacency() {
    }

    public static Adjacency of(List<IndexedTriangle> faces) {
        Map<NodeEdge, List<Integer>> facesByEdge = new LinkedHashMap<>();
        for (int faceIndex = 0; faceIndex < faces.size(); faceIndex++) {
            for (NodeEdge edge : faces.get(faceIndex).edges()) {
                facesByEdge.computeIfAbsent(edge, key -> new ArrayList<>()).add(faceIndex);
            }
        }

        Map<Integer, Set<Integer>> neighbors = new LinkedHashMap<>();
        for (int faceIndex = 0; faceIndex < faces.size(); faceIndex++) {
            neighbors.put(faceIndex, new LinkedHashSet<>());
        }

        Set<Integer> hullTouching = new LinkedHashSet<>();
        for (Map.Entry<NodeEdge, List<Integer>> entry : facesByEdge.entrySet()) {
            List<Integer> sharingFaces = entry.getValue();
            if (sharingFaces.size() == 2) {
                int first = sharingFaces.get(0);
                int second = sharingFaces.get(1);
                neighbors.get(first).add(second);
                neighbors.get(second).add(first);
            } else if (sharingFaces.size() == 1) {
                hullTouching.add(sharingFaces.get(0));
            } else {
                throw new IllegalStateException(
                    "edge " + entry.getKey() + " shared by " + sharingFaces.size() + " faces - not a valid triangulation"
                );
            }
        }

        return new Adjacency(Map.copyOf(neighbors), Set.copyOf(hullTouching));
    }
}
