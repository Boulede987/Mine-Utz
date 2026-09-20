package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.mineutze.worldgen.plan.ToroidalTestTables.A;
import static com.mineutze.worldgen.plan.ToroidalTestTables.B;
import static com.mineutze.worldgen.plan.ToroidalTestTables.C;
import static com.mineutze.worldgen.plan.ToroidalTestTables.threeColoring;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BiomeMapPngExporterTest {

    private static final ToroidalGrid GRID = new ToroidalGrid(6, 6);
    private static final HexTorusGeometry GEOMETRY = new HexTorusGeometry(6, 6, 300);
    private static final int SIZE = 60;

    private static BiomeMap map() {
        return BiomeMapRasterizer.rasterize(GRID, threeColoring(GRID, A, B, C), GEOMETRY, DivergenceFreeWarp.none(GEOMETRY), SIZE, List.<BiomeId>of(A, B, C));
    }

    @Test
    void theFullImageIsTheSkewedTileAtTheRequestedScale(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("map.png");

        BiomeMapPngExporter.exportFull(map(), file, 2);

        BufferedImage image = ImageIO.read(file.toFile());
        assertEquals(2 * (SIZE + SIZE / 2) + 1, image.getWidth(), 2);
        assertEquals((int) Math.round(2 * SIZE * Math.sqrt(3) / 2), image.getHeight(), 2);
    }

    @Test
    void anInteriorPixelHasTheColorOfItsBiome(@TempDir Path directory) throws IOException {
        BiomeMap map = map();
        Path file = directory.resolve("map.png");
        BiomeMapPngExporter.exportFull(map, file, 4);
        BufferedImage image = ImageIO.read(file.toFile());

        int sampleI = 20;
        int sampleJ = 30;
        int pixelX = (int) Math.round((sampleI + sampleJ / 2.0 + 0.5) * 4);
        int pixelY = (int) Math.round((sampleJ + 0.5) * 4 * Math.sqrt(3) / 2);

        Color expected = ToroidalPatternPngExporter.colorOf(map.biomeAt(sampleI, sampleJ));
        assertEquals(expected.getRGB(), image.getRGB(pixelX, pixelY), "the pixel may sit on an outline; move the sample if the geometry changes");
    }

    @Test
    void borderBetweenDifferentBiomesIsDrawnDark(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("map.png");
        BiomeMapPngExporter.exportFull(map(), file, 4);
        BufferedImage image = ImageIO.read(file.toFile());

        boolean anyDark = false;
        for (int y = 0; y < image.getHeight() && !anyDark; y++) {
            for (int x = 0; x < image.getWidth() && !anyDark; x++) {
                Color pixel = new Color(image.getRGB(x, y));
                anyDark = pixel.getRed() < 60 && pixel.getGreen() < 60 && pixel.getBlue() < 60;
            }
        }
        assertTrue(anyDark, "expected dark outline pixels between differently coloured cells");
    }

    @Test
    void aRegionExportHasExactlyTheRequestedSize(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("crop.png");

        BiomeMapPngExporter.exportRegion(map(), file, 3, 30, 20, 80, 50);

        BufferedImage image = ImageIO.read(file.toFile());
        assertEquals(80, image.getWidth());
        assertEquals(50, image.getHeight());
    }

    @Test
    void createsMissingParentDirectories(@TempDir Path directory) {
        Path file = directory.resolve("a").resolve("b").resolve("map.png");

        BiomeMapPngExporter.exportFull(map(), file, 1);

        assertTrue(Files.exists(file));
    }
}
