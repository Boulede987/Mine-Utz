package com.mineutze.worldgen.export.triangle;

import com.mineutze.worldgen.biomegraph.AdjacencyRule;
import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;
import com.mineutze.worldgen.biomegraph.BiomeSizeRange;
import com.mineutze.worldgen.regionsolve.MacroCellPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Direct test - flagged by test-coverage audit as having zero dedicated tests for the rendering helper extracted out of TriangleFieldRegionExportCli. */
class TriangleFieldPngExporterTest {

    private static final BiomeId DESERT = new BiomeId("test", "desert");
    private static final BiomeId SAVANNA = new BiomeId("test", "savanna");

    private static BiomeGraph twoBiomeGraph() {
        return new BiomeGraph(
            List.of(new BiomeNode(DESERT, new BiomeSizeRange(100, 200)), new BiomeNode(SAVANNA, new BiomeSizeRange(100, 200))),
            List.of(AdjacencyRule.allow(DESERT, SAVANNA, 10))
        );
    }

    @Test
    void evenlySpacedPaletteAssignsOneDistinctColorPerBiomeInSortedOrder() {
        Map<BiomeId, Integer> palette = TriangleFieldPngExporter.evenlySpacedPalette(twoBiomeGraph());

        assertEquals(2, palette.size());
        assertNotEquals(palette.get(DESERT), palette.get(SAVANNA));
        // desert sorts first (hue 0.0), savanna second (hue 0.5) - same formula the class itself uses.
        assertEquals(Color.HSBtoRGB(0.0f, 0.75f, 0.9f) & 0xFFFFFF, palette.get(DESERT));
        assertEquals(Color.HSBtoRGB(0.5f, 0.75f, 0.9f) & 0xFFFFFF, palette.get(SAVANNA));
    }

    @Test
    void writeRegionImageColorsEachCellWithItsPaletteEntry(@TempDir Path tempDir) throws IOException {
        Map<BiomeId, Integer> palette = TriangleFieldPngExporter.evenlySpacedPalette(twoBiomeGraph());
        Map<MacroCellPos, BiomeId> assignment = Map.of(
            new MacroCellPos(0, 0), DESERT,
            new MacroCellPos(1, 0), SAVANNA,
            new MacroCellPos(0, 1), SAVANNA,
            new MacroCellPos(1, 1), DESERT
        );
        Path file = tempDir.resolve("region.png");

        TriangleFieldPngExporter.writeRegionImage(assignment, file, palette);
        BufferedImage image = ImageIO.read(file.toFile());

        assertEquals(2, image.getWidth());
        assertEquals(2, image.getHeight());
        assertEquals(palette.get(DESERT), image.getRGB(0, 0) & 0xFFFFFF);
        assertEquals(palette.get(SAVANNA), image.getRGB(1, 0) & 0xFFFFFF);
    }

    @Test
    void writeLegendImageProducesASwatchPerBiomeWithTheRightHeight(@TempDir Path tempDir) throws IOException {
        Map<BiomeId, Integer> palette = TriangleFieldPngExporter.evenlySpacedPalette(twoBiomeGraph());
        Path file = tempDir.resolve("legend.png");

        TriangleFieldPngExporter.writeLegendImage(twoBiomeGraph(), palette, file);
        BufferedImage image = ImageIO.read(file.toFile());

        // height = padding*2 + biomeCount*rowHeight = 14*2 + 2*44 = 116, per the class's own constants.
        assertEquals(116, image.getHeight());
        assertTrue(image.getWidth() > 0);
        // desert (sorted first) gets the first swatch, starting at (padding, padding) = (14, 14).
        assertEquals(palette.get(DESERT), image.getRGB(15, 15) & 0xFFFFFF, "sampled just inside the first swatch, past its black outline");
    }
}
