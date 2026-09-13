package com.mineutze.worldgen.biomegraph;

/**
 * Identifies a biome family. Not Minecraft's ResourceLocation - this module
 * has no Minecraft/NeoForge dependency (see build.gradle), so algorithms
 * unit-test as plain JUnit, no game bootstrap. ResourceLocation conversion
 * happens at integration boundary in main mod module.
 */
public record BiomeId(String namespace, String path) implements Comparable<BiomeId> {

    public BiomeId {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("BiomeId namespace must not be blank");
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("BiomeId path must not be blank");
        }
    }

    @Override
    public int compareTo(BiomeId other) {
        int namespaceComparison = namespace.compareTo(other.namespace);
        return namespaceComparison != 0 ? namespaceComparison : path.compareTo(other.path);
    }

    @Override
    public String toString() {
        return namespace + ":" + path;
    }
}
