package com.mineutze.worldgen.plan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.mineutze.worldgen.plan.ToroidalTestTables.A;
import static com.mineutze.worldgen.plan.ToroidalTestTables.B;
import static com.mineutze.worldgen.plan.ToroidalTestTables.C;
import static com.mineutze.worldgen.plan.ToroidalTestTables.threeColoring;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToroidalPatternPngExporterTest {

    private static final List<com.mineutze.worldgen.biomegraph.BiomeId> BIOME_ORDER = List.of(A, B, C);

    @Test
    void writesAReadablePngWithRoomForTheWholeSkewedGrid(@TempDir Path directory) throws IOException {
        ToroidalGrid grid = new ToroidalGrid(6, 6);
        Path file = directory.resolve("pattern.png");

        ToroidalPatternPngExporter.export(grid, threeColoring(grid, A, B, C), BIOME_ORDER, file);

        BufferedImage image = ImageIO.read(file.toFile());
        assertTrue(image.getWidth() > 6 * ToroidalPatternPngExporter.HEX_RADIUS_PIXELS, "wider than a straight row, the grid is skewed by its rows");
        assertTrue(image.getHeight() > 6 * ToroidalPatternPngExporter.HEX_RADIUS_PIXELS);
    }

    @Test
    void differentBiomesAreDrawnInDifferentColors(@TempDir Path directory) throws IOException {
        ToroidalGrid grid = new ToroidalGrid(6, 6);
        Path file = directory.resolve("pattern.png");
        Map<ToroidalPos, com.mineutze.worldgen.biomegraph.BiomeId> coloring = threeColoring(grid, A, B, C);

        ToroidalPatternPngExporter.export(grid, coloring, BIOME_ORDER, file);

        BufferedImage image = ImageIO.read(file.toFile());
        int firstCell = image.getRGB(ToroidalPatternPngExporter.centerXOf(new ToroidalPos(1, 1)), ToroidalPatternPngExporter.centerYOf(new ToroidalPos(1, 1)));
        int neighborCell = image.getRGB(ToroidalPatternPngExporter.centerXOf(new ToroidalPos(2, 1)), ToroidalPatternPngExporter.centerYOf(new ToroidalPos(2, 1)));
        assertNotEquals(firstCell, neighborCell, "cells (1,1) and (2,1) hold different biomes in the 3-coloring");
    }

    @Test
    void sameBiomeIsDrawnInTheSameColor(@TempDir Path directory) throws IOException {
        ToroidalGrid grid = new ToroidalGrid(6, 6);
        Path file = directory.resolve("pattern.png");

        ToroidalPatternPngExporter.export(grid, threeColoring(grid, A, B, C), BIOME_ORDER, file);

        BufferedImage image = ImageIO.read(file.toFile());
        assertEquals(
            image.getRGB(ToroidalPatternPngExporter.centerXOf(new ToroidalPos(1, 1)), ToroidalPatternPngExporter.centerYOf(new ToroidalPos(1, 1))),
            image.getRGB(ToroidalPatternPngExporter.centerXOf(new ToroidalPos(4, 1)), ToroidalPatternPngExporter.centerYOf(new ToroidalPos(4, 1))),
            "the 3-coloring repeats every 3 columns"
        );
    }

    @Test
    void createsMissingParentDirectories(@TempDir Path directory) {
        ToroidalGrid grid = new ToroidalGrid(6, 6);
        Path file = directory.resolve("nested").resolve("deeper").resolve("pattern.png");

        ToroidalPatternPngExporter.export(grid, threeColoring(grid, A, B, C), BIOME_ORDER, file);

        assertTrue(Files.exists(file));
    }

    @Test
    void rejectsAnAssignmentHoldingABiomeMissingFromTheColorOrder(@TempDir Path directory) {
        ToroidalGrid grid = new ToroidalGrid(6, 6);

        assertThrows(
            IllegalArgumentException.class,
            () -> ToroidalPatternPngExporter.export(grid, threeColoring(grid, A, B, C), List.of(A, B), directory.resolve("pattern.png"))
        );
    }
}
