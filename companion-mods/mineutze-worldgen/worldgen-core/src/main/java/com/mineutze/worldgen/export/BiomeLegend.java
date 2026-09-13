package com.mineutze.worldgen.export;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;

import java.util.LinkedHashMap;
import java.util.Map;

/** One color + one ASCII glyph per biome, assigned once, shared by {@link PngExporter}, {@link AsciiExporter}, {@link JsonExporter} - all 3 outputs agree on how a biome looks. */
public final class BiomeLegend {

    private final Map<BiomeId, Integer> rgbByBiome = new LinkedHashMap<>();
    private final Map<BiomeId, Character> glyphByBiome = new LinkedHashMap<>();

    public BiomeLegend(BiomeGraph graph) {
        graph.nodes().stream().map(BiomeNode::id).sorted().forEach(id -> {
            rgbByBiome.put(id, colorFor(id));
            glyphByBiome.put(id, glyphFor(id, glyphByBiome.values()));
        });
    }

    public int rgbOf(BiomeId id) {
        Integer rgb = rgbByBiome.get(id);
        if (rgb == null) {
            throw new IllegalArgumentException("No legend color for biome " + id + " — not in the graph this legend was built from");
        }
        return rgb;
    }

    public char glyphOf(BiomeId id) {
        Character glyph = glyphByBiome.get(id);
        if (glyph == null) {
            throw new IllegalArgumentException("No legend glyph for biome " + id + " — not in the graph this legend was built from");
        }
        return glyph;
    }

    public Map<BiomeId, Character> glyphs() {
        return Map.copyOf(glyphByBiome);
    }

    /** Deterministic hash of biome id into fixed-saturation, fixed-brightness hue - distinct, readable colors regardless of graph size. */
    private static int colorFor(BiomeId id) {
        int hash = id.toString().hashCode();
        float hue = (hash & 0xFFFF) / (float) 0xFFFF;
        return java.awt.Color.HSBtoRGB(hue, 0.65f, 0.9f) & 0xFFFFFF;
    }

    /** First unused letter of biome's own path, falls back to digits/other letters on collision - keeps glyphs recognizable, not arbitrary. */
    private static char glyphFor(BiomeId id, java.util.Collection<Character> alreadyUsed) {
        String candidates = id.path().toUpperCase() + "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        for (char candidate : candidates.toCharArray()) {
            if (!alreadyUsed.contains(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Ran out of distinct glyphs assigning a legend character for " + id);
    }
}
