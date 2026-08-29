package com.mineutze.worldgen.export;

import com.mineutze.worldgen.biomegraph.AdjacencyRule;
import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;
import com.mineutze.worldgen.biomegraph.BiomeSizeRange;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Direct test - flagged by this session's test-coverage audit as having zero dedicated tests despite a real glyph-collision-resolution algorithm shared by all 3 export writers. */
class BiomeLegendTest {

    private static BiomeId id(String path) {
        return new BiomeId("test", path);
    }

    private static BiomeNode node(String path) {
        return new BiomeNode(id(path), new BiomeSizeRange(100, 200));
    }

    @Test
    void glyphOfUsesTheFirstLetterOfThePathWhenUnambiguous() {
        BiomeLegend legend = new BiomeLegend(new BiomeGraph(List.of(node("ocean")), List.of()));

        assertEquals('O', legend.glyphOf(id("ocean")));
    }

    @Test
    void glyphCollisionFallsBackToTheNextLetterOfItsOwnPath() {
        // Both start with 'D'; "desert" sorts first alphabetically and claims 'D',
        // so "dunes" must fall back to its own second letter, 'U'.
        BiomeGraph graph = new BiomeGraph(List.of(node("desert"), node("dunes")), List.of(AdjacencyRule.allow(id("desert"), id("dunes"), 10)));
        BiomeLegend legend = new BiomeLegend(graph);

        assertEquals('D', legend.glyphOf(id("desert")));
        assertEquals('U', legend.glyphOf(id("dunes")));
        assertNotEquals(legend.glyphOf(id("desert")), legend.glyphOf(id("dunes")));
    }

    @Test
    void rgbIsDeterministicAcrossEquivalentGraphs() {
        BiomeLegend first = new BiomeLegend(new BiomeGraph(List.of(node("desert")), List.of()));
        BiomeLegend second = new BiomeLegend(new BiomeGraph(List.of(node("desert")), List.of()));

        assertEquals(first.rgbOf(id("desert")), second.rgbOf(id("desert")));
    }

    @Test
    void rgbAndGlyphThrowForABiomeOutsideTheGraphThisLegendWasBuiltFrom() {
        BiomeLegend legend = new BiomeLegend(new BiomeGraph(List.of(node("desert")), List.of()));
        BiomeId unknown = id("nonexistent");

        assertThrows(IllegalArgumentException.class, () -> legend.rgbOf(unknown));
        assertThrows(IllegalArgumentException.class, () -> legend.glyphOf(unknown));
    }

    @Test
    void glyphsExposesEveryAssignedGlyph() {
        BiomeGraph graph = new BiomeGraph(List.of(node("desert"), node("ocean")), List.of(AdjacencyRule.allow(id("desert"), id("ocean"), 10)));
        BiomeLegend legend = new BiomeLegend(graph);

        assertEquals(Map.of(id("desert"), legend.glyphOf(id("desert")), id("ocean"), legend.glyphOf(id("ocean"))), legend.glyphs());
    }
}
