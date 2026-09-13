package com.mineutze.worldgen.regionsolve.topology.identity;

import com.mineutze.worldgen.regionsolve.topology.mesh.FaceAdjacency;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Pairs up mutually-adjacent, same-biome free faces whose biome has {@link
 * com.mineutze.worldgen.biomegraph.BiomeNode#selfAdjacencyLimit} {@code > 0} - see {@link
 * FaceWidthPicker#splitWidthForPair} for why a pair matters (one shared felt-region target,
 * split, not each face drawing its own full-range target). {@link FaceIdentityCsp}'s own
 * self-adjacency check already guarantees, by construction, no face ends up with more
 * same-biome neighbors than its limit allows - for every real graph today (limit=1
 * everywhere used) that's at most ONE partner per face, so this only finds pairs, never
 * general N-way clusters.
 */
final class SelfAdjacentPairing {

    private SelfAdjacentPairing() {
    }

    /**
     * @return a symmetric map (both directions present for every pair) from face index to
     * its one paired partner's face index - a face with no self-adjacency-enabled biome,
     * or one whose biome IS self-adjacency-enabled but landed with no actual same-biome
     * neighbor this instance, simply has no entry.
     */
    static Map<Integer, Integer> pairedFaces(Map<Integer, BiomeId> assigned, FaceAdjacency.Adjacency adjacency, BiomeGraph graph) {
        Map<Integer, Integer> pairPartnerByFace = new HashMap<>();
        for (Map.Entry<Integer, BiomeId> entry : assigned.entrySet()) {
            pairFaceWithFirstEligibleNeighbor(entry.getKey(), entry.getValue(), assigned, adjacency, graph, pairPartnerByFace);
        }
        return pairPartnerByFace;
    }

    /** Pairs {@code faceIndex} with its first (lowest-index) unpaired same-biome neighbor, if any - a no-op if {@code faceIndex} is already paired or its biome isn't self-adjacency-enabled. Mutates {@code pairPartnerByFace} with both directions of the new pair. */
    private static void pairFaceWithFirstEligibleNeighbor(
        int faceIndex, BiomeId biome, Map<Integer, BiomeId> assigned, FaceAdjacency.Adjacency adjacency,
        BiomeGraph graph, Map<Integer, Integer> pairPartnerByFace
    ) {
        if (pairPartnerByFace.containsKey(faceIndex) || graph.node(biome).selfAdjacencyLimit() <= 0) {
            return;
        }
        firstUnpairedSameBiomeNeighbor(faceIndex, biome, assigned, adjacency, pairPartnerByFace).ifPresent(neighbor -> {
            pairPartnerByFace.put(faceIndex, neighbor);
            pairPartnerByFace.put(neighbor, faceIndex);
        });
    }

    private static Optional<Integer> firstUnpairedSameBiomeNeighbor(
        int faceIndex, BiomeId biome, Map<Integer, BiomeId> assigned, FaceAdjacency.Adjacency adjacency, Map<Integer, Integer> pairPartnerByFace
    ) {
        List<Integer> neighbors = new ArrayList<>(adjacency.neighborFaceIndices().getOrDefault(faceIndex, Set.of()));
        Collections.sort(neighbors);
        return neighbors.stream()
            .filter(neighbor -> !pairPartnerByFace.containsKey(neighbor))
            .filter(neighbor -> biome.equals(assigned.get(neighbor)))
            .findFirst();
    }
}
