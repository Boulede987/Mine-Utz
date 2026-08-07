package com.mineutze.worldgen.regionsolve.topology;

import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.regionsolve.MacroCellPos;

import java.util.Set;

/**
 * One node of the abstract seed graph: either a fixed boundary cell
 * (already colored by a neighbor region's solve, domain is a single
 * identity) or a free interior seed whose domain still needs narrowing and,
 * later, CSP assignment. See
 * doc/Modelisation/ActivityDiagram/seed-graph-generation.puml.
 */
public record SeedGraphNode(int id, MacroCellPos position, boolean fixed, Set<BiomeId> domain) {

    public SeedGraphNode {
        if (domain == null) {
            throw new IllegalArgumentException("SeedGraphNode " + id + " domain must not be null");
        }
        if (fixed && domain.size() != 1) {
            throw new IllegalArgumentException("Fixed SeedGraphNode " + id + " must have a single-value domain, got " + domain);
        }
    }

    public static SeedGraphNode fixed(int id, MacroCellPos position, BiomeId biome) {
        return new SeedGraphNode(id, position, true, Set.of(biome));
    }

    public static SeedGraphNode free(int id, MacroCellPos position, Set<BiomeId> domain) {
        return new SeedGraphNode(id, position, false, domain);
    }

    public BiomeId fixedBiome() {
        if (!fixed) {
            throw new IllegalStateException("SeedGraphNode " + id + " is not fixed");
        }
        return domain.iterator().next();
    }
}
