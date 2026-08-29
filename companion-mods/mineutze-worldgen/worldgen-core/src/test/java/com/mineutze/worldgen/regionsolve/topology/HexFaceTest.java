package com.mineutze.worldgen.regionsolve.topology;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HexFaceTest {

    @Test
    void edgesAreTheSixConsecutiveCornerPairsWrappingAround() {
        HexFace face = new HexFace(0, List.of(10, 11, 12, 13, 14, 15));
        List<NodeEdge> edges = face.edges();
        assertEquals(6, edges.size());
        assertEquals(
            Set.of(NodeEdge.of(10, 11), NodeEdge.of(11, 12), NodeEdge.of(12, 13), NodeEdge.of(13, 14), NodeEdge.of(14, 15), NodeEdge.of(15, 10)),
            Set.copyOf(edges)
        );
    }

    @Test
    void rejectsAnythingOtherThanExactlySixCorners() {
        assertThrows(IllegalArgumentException.class, () -> new HexFace(0, List.of(1, 2, 3, 4, 5)));
        assertThrows(IllegalArgumentException.class, () -> new HexFace(0, List.of(1, 2, 3, 4, 5, 6, 7)));
    }
}
