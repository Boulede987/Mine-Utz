package com.mineutze.worldgen.biomegraph;

/**
 * Identifies a biome family. Deliberately not Minecraft's ResourceLocation —
 * this module has no Minecraft/NeoForge dependency (see build.gradle
 * comment), so its algorithms can be unit tested as plain JUnit with no
 * game bootstrap. Conversion to/from ResourceLocation happens at the
 * integration boundary in the main mod module.
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
