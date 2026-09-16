package com.mineutze.worldgen.coherence;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.EdgeData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.NodeData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.RequiredNeighbor;
import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads {@link BiomeGraphData} as plain rule data - the only point in this whole package
 * that touches anything from outside it (see the package's own doc for why: never a real
 * {@code BiomeGraph}, never the live solver/mesh). Everything downstream of this class
 * works only against these lookup tables.
 */
public final class RawRuleTable {

    private final List<BiomeId> allBiomeIds;
    private final Map<BiomeId, SizeRange> sizeRangeById;
    private final Set<UnorderedPair> legalPairs;
    private final Map<BiomeId, Map<BiomeId, Integer>> requiredNeighborMinCountsById;

    private RawRuleTable(
        List<BiomeId> allBiomeIds, Map<BiomeId, SizeRange> sizeRangeById, Set<UnorderedPair> legalPairs,
        Map<BiomeId, Map<BiomeId, Integer>> requiredNeighborMinCountsById
    ) {
        this.allBiomeIds = allBiomeIds;
        this.sizeRangeById = sizeRangeById;
        this.legalPairs = legalPairs;
        this.requiredNeighborMinCountsById = requiredNeighborMinCountsById;
    }

    public static RawRuleTable from(BiomeGraphData data) {
        List<BiomeId> allBiomeIds = new ArrayList<>();
        Map<BiomeId, SizeRange> sizeRangeById = new LinkedHashMap<>();
        Map<BiomeId, Map<BiomeId, Integer>> requiredNeighborMinCountsById = new LinkedHashMap<>();
        for (NodeData node : data.nodes()) {
            BiomeId id = new BiomeId(node.namespace(), node.path());
            allBiomeIds.add(id);
            sizeRangeById.put(id, new SizeRange(node.minWidthBlocks(), node.maxWidthBlocks()));
            Map<BiomeId, Integer> requiredNeighbors = new LinkedHashMap<>();
            for (RequiredNeighbor requiredNeighbor : node.requiredNeighbors()) {
                requiredNeighbors.put(new BiomeId(requiredNeighbor.namespace(), requiredNeighbor.path()), requiredNeighbor.minCount());
            }
            requiredNeighborMinCountsById.put(id, Map.copyOf(requiredNeighbors));
        }
        Set<UnorderedPair> legalPairs = new LinkedHashSet<>();
        for (EdgeData edge : data.edges()) {
            BiomeId a = new BiomeId(edge.aNamespace(), edge.aPath());
            BiomeId b = new BiomeId(edge.bNamespace(), edge.bPath());
            legalPairs.add(new UnorderedPair(a, b));
        }
        return new RawRuleTable(
            List.copyOf(allBiomeIds), Map.copyOf(sizeRangeById), Set.copyOf(legalPairs), Map.copyOf(requiredNeighborMinCountsById)
        );
    }

    public List<BiomeId> allBiomeIds() {
        return allBiomeIds;
    }

    public SizeRange sizeRangeOf(BiomeId id) {
        SizeRange range = sizeRangeById.get(id);
        if (range == null) {
            throw new IllegalArgumentException("RawRuleTable: unknown biome " + id);
        }
        return range;
    }

    /** Unconditional: a biome is never legally adjacent to itself, no exception - not just "usually false in the rule table." */
    public boolean isLegal(BiomeId a, BiomeId b) {
        if (a.equals(b)) {
            return false;
        }
        return legalPairs.contains(new UnorderedPair(a, b));
    }

    public Map<BiomeId, Integer> requiredNeighborsOf(BiomeId id) {
        return requiredNeighborMinCountsById.getOrDefault(id, Map.of());
    }

    public record SizeRange(int minWidthBlocks, int maxWidthBlocks) {
        public SizeRange {
            if (minWidthBlocks <= 0 || maxWidthBlocks < minWidthBlocks) {
                throw new IllegalArgumentException("RawRuleTable: invalid size range [" + minWidthBlocks + "," + maxWidthBlocks + "]");
            }
        }
    }

    /** Canonicalized so {a,b} and {b,a} are the same key - order never matters for legality. */
    record UnorderedPair(BiomeId a, BiomeId b) {
        UnorderedPair {
            if (a.compareTo(b) > 0) {
                BiomeId swap = a;
                a = b;
                b = swap;
            }
        }
    }
}
