package com.mineutze.worldgen.regionsolve.topology.mesh;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VertexKeyTest {

    private static final double SIZE = 200;

    @Test
    void isOrderIndependent() {
        HexGrid.AxialPos p = new HexGrid.AxialPos(0, 0);
        HexGrid.AxialPos q = new HexGrid.AxialPos(1, 0);
        HexGrid.AxialPos r = new HexGrid.AxialPos(0, 1);

        VertexKey key = new VertexKey(p, q, r);
        assertEquals(key, new VertexKey(p, r, q));
        assertEquals(key, new VertexKey(q, p, r));
        assertEquals(key, new VertexKey(q, r, p));
        assertEquals(key, new VertexKey(r, p, q));
        assertEquals(key, new VertexKey(r, q, p));
        assertEquals(key.hashCode(), new VertexKey(r, q, p).hashCode());
    }

    @Test
    void distinctTriplesProduceDistinctKeys() {
        HexGrid.AxialPos p = new HexGrid.AxialPos(0, 0);
        HexGrid.AxialPos q = new HexGrid.AxialPos(1, 0);
        HexGrid.AxialPos r = new HexGrid.AxialPos(0, 1);
        HexGrid.AxialPos s = new HexGrid.AxialPos(1, 1);

        assertTrue(!new VertexKey(p, q, r).equals(new VertexKey(p, q, s)));
    }

    /**
     * Round-trips {@link VertexKey#of} through real mesh's vertex triples, mirroring
     * how {@code WeightedVertexPlacement.sharedVertexOf} already finds 3 mutually-
     * adjacent faces around one vertex — every fully-interior vertex must resolve to
     * distinct key, and same key must never be produced by two DIFFERENT vertices.
     */
    @Test
    void everyFullyInteriorVertexGetsADistinctKey() {
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(SIZE, 0, 0, 4000, 4000);
        FaceAdjacency.Adjacency adjacency = FaceAdjacency.of(mesh.faces());
        Set<Integer> hullTouching = adjacency.hullTouchingFaceIndices();

        Map<Integer, Set<Integer>> facesByVertex = new HashMap<>();
        for (int faceIndex = 0; faceIndex < mesh.faces().size(); faceIndex++) {
            for (int vertexIndex : mesh.faces().get(faceIndex).vertexIndices()) {
                facesByVertex.computeIfAbsent(vertexIndex, key -> new HashSet<>()).add(faceIndex);
            }
        }

        Set<VertexKey> seenKeys = new HashSet<>();
        boolean checkedAny = false;
        for (Set<Integer> incidentFaces : facesByVertex.values()) {
            if (incidentFaces.size() != 3 || incidentFaces.stream().anyMatch(hullTouching::contains)) {
                continue;
            }
            Integer[] indices = incidentFaces.toArray(new Integer[0]);
            VertexKey key = VertexKey.of(mesh.faces().get(indices[0]), mesh.faces().get(indices[1]), mesh.faces().get(indices[2]));
            assertTrue(seenKeys.add(key), "two different interior vertices produced the same VertexKey: " + key);
            checkedAny = true;
        }
        assertTrue(checkedAny, "expected at least one fully-interior vertex to check");
    }
}
