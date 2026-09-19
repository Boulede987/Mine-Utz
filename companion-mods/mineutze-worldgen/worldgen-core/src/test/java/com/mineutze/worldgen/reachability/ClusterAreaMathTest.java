package com.mineutze.worldgen.reachability;

import com.mineutze.worldgen.biomegraph.BiomeId;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterAreaMathTest {

    private static final BiomeId A = new BiomeId("test", "a");
    private static final BiomeId B = new BiomeId("test", "b");

    private static Function<AxialPos, Optional<BiomeId>> committedFrom(Map<AxialPos, BiomeId> committed) {
        return pos -> Optional.ofNullable(committed.get(pos));
    }

    @Test
    void clusterOfWalksContiguousSameBiomeOnlyStoppingAtADifferentBiome() {
        AxialPos p0 = new AxialPos(0, 0);
        AxialPos p1 = p0.neighbor(0);
        AxialPos p2 = p1.neighbor(0);
        Map<AxialPos, BiomeId> committed = Map.of(p0, A, p1, A, p2, B);
        Set<AxialPos> cluster = ClusterAreaMath.clusterOf(p0, A, Map.of(), committedFrom(committed));
        assertEquals(Set.of(p0, p1), cluster);
    }

    @Test
    void clusterOfMixesProvisionalAndCommittedValues() {
        AxialPos p0 = new AxialPos(0, 0);
        AxialPos p1 = p0.neighbor(0);
        Map<AxialPos, BiomeId> provisional = Map.of(p1, A);
        Set<AxialPos> cluster = ClusterAreaMath.clusterOf(p0, A, provisional, committedFrom(Map.of()));
        assertEquals(Set.of(p0, p1), cluster);
    }

    @Test
    void clusterOfIsJustTheStartWhenAllNeighborsAreUndecidedOrDifferent() {
        AxialPos p0 = new AxialPos(0, 0);
        Set<AxialPos> cluster = ClusterAreaMath.clusterOf(p0, A, Map.of(), committedFrom(Map.of()));
        assertEquals(Set.of(p0), cluster);
    }

    @Test
    void isSealedIsFalseWhenAnyBoundaryNeighborIsUndecided() {
        AxialPos p0 = new AxialPos(0, 0);
        assertFalse(ClusterAreaMath.isSealed(Set.of(p0), committedFrom(Map.of())));
    }

    @Test
    void isSealedIsTrueWhenEveryBoundaryNeighborIsDecided() {
        AxialPos p0 = new AxialPos(0, 0);
        Map<AxialPos, BiomeId> committed = new HashMap<>();
        for (AxialPos neighbor : p0.neighbors()) {
            committed.put(neighbor, B);
        }
        assertTrue(ClusterAreaMath.isSealed(Set.of(p0), committedFrom(committed)));
    }

    @Test
    void isSealedIgnoresEdgesInsideTheClusterItself() {
        AxialPos p0 = new AxialPos(0, 0);
        AxialPos p1 = p0.neighbor(0);
        Map<AxialPos, BiomeId> committed = new HashMap<>();
        for (AxialPos neighbor : p0.neighbors()) {
            if (!neighbor.equals(p1)) {
                committed.put(neighbor, B);
            }
        }
        for (AxialPos neighbor : p1.neighbors()) {
            if (!neighbor.equals(p0)) {
                committed.put(neighbor, B);
            }
        }
        // p0-p1 share an edge (both in the cluster) - not required to be "decided" against each other.
        assertTrue(ClusterAreaMath.isSealed(Set.of(p0, p1), committedFrom(committed)));
    }
}
