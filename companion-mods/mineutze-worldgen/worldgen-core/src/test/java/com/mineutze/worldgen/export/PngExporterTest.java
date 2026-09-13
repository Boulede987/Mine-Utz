package com.mineutze.worldgen.export;

import com.mineutze.worldgen.biomegraph.AdjacencyRule;
import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;
import com.mineutze.worldgen.biomegraph.BiomeSizeRange;
import com.mineutze.worldgen.regionsolve.MacroCellPos;
import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mineutze.worldgen.regionsolve.RegionPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Direct test against small hand-built {@link ExportSnapshot} - flagged by test-coverage audit as having zero dedicated tests. Reads written PNG back to check real pixel colors. */
class PngExporterTest {

    private static final int PIXELS_PER_CELL = 4; // matches PngExporter's own private constant
    private static final BiomeId DESERT = new BiomeId("test", "desert");
    private static final BiomeId SAVANNA = new BiomeId("test", "savanna");

    private static ExportSnapshot snapshotWithOneGapCell() {
        BiomeGraph graph = new BiomeGraph(
            List.of(new BiomeNode(DESERT, new BiomeSizeRange(100, 200)), new BiomeNode(SAVANNA, new BiomeSizeRange(100, 200))),
            List.of(AdjacencyRule.allow(DESERT, SAVANNA, 10))
        );
        MacroGridConfig grid = new MacroGridConfig(4, 2);
        Map<MacroCellPos, BiomeId> cells = new HashMap<>();
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) {
                if (x == 3 && z == 3) {
                    continue; // deliberate gap cell
                }
                cells.put(new MacroCellPos(x, z), x < 2 ? DESERT : SAVANNA);
            }
        }
        return new ExportSnapshot(graph, grid, 1234L, new RegionPos(0, 0), 2, cells, Map.of());
    }

    @Test
    void imageSizeMatchesSpanCellsTimesPixelsPerCell(@TempDir Path tempDir) throws IOException {
        ExportSnapshot snapshot = snapshotWithOneGapCell();
        Path file = tempDir.resolve("out.png");

        PngExporter.write(file, snapshot, new BiomeLegend(snapshot.graph()));
        BufferedImage image = ImageIO.read(file.toFile());

        assertEquals(snapshot.spanCells() * PIXELS_PER_CELL, image.getWidth());
        assertEquals(snapshot.spanCells() * PIXELS_PER_CELL, image.getHeight());
    }

    @Test
    void colorsACellBlockWithItsLegendColor(@TempDir Path tempDir) throws IOException {
        ExportSnapshot snapshot = snapshotWithOneGapCell();
        BiomeLegend legend = new BiomeLegend(snapshot.graph());
        Path file = tempDir.resolve("out.png");

        PngExporter.write(file, snapshot, legend);
        BufferedImage image = ImageIO.read(file.toFile());

        // Cell (0,0) is desert - sample the middle of its 4x4 pixel block.
        int pixel = image.getRGB(1, 1) & 0xFFFFFF;
        assertEquals(legend.rgbOf(DESERT), pixel);
    }

    @Test
    void colorsAMissingCellsBlockWithTheGapColor(@TempDir Path tempDir) throws IOException {
        ExportSnapshot snapshot = snapshotWithOneGapCell();
        Path file = tempDir.resolve("out.png");

        PngExporter.write(file, snapshot, new BiomeLegend(snapshot.graph()));
        BufferedImage image = ImageIO.read(file.toFile());

        // Cell (3,3) is the deliberate gap - its 4x4 block starts at pixel (12, 12).
        int pixel = image.getRGB(13, 13) & 0xFFFFFF;
        assertEquals(new Color(128, 128, 128).getRGB() & 0xFFFFFF, pixel, "a missing cell must render as the distinct gap color, never a wrong biome or a crash");
    }
}
