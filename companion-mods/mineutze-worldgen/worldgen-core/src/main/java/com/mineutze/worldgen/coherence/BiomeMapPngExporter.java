package com.mineutze.worldgen.coherence;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Draws a {@link BiomeMap} as a PNG (2026-09-20): every sample coloured by its biome (the same
 * palette as {@link ToroidalPatternPngExporter}), a dark line wherever the biome changes. The tile
 * is drawn as the skewed rhombus it is, sample {@code (i, j)} at {@code x = (i + j/2) * scale},
 * {@code y = j * sqrt(3)/2 * scale}. Each pixel looks up the sample under it, so any scale works
 * without gaps, and the outline is one pixel wide however far the picture is zoomed.
 */
final class BiomeMapPngExporter {

    private static final int OUTSIDE_TILE = -1;
    private static final int OUTLINE_RGB = new Color(20, 20, 20).getRGB();
    private static final int BACKGROUND_RGB = Color.WHITE.getRGB();
    private static final double ROW_HEIGHT = Math.sqrt(3) / 2;

    private BiomeMapPngExporter() {
    }

    /** The whole tile, {@code pixelsPerSample} pixels per sample along a tile vector. */
    static void exportFull(BiomeMap map, Path file, int pixelsPerSample) {
        int width = (int) Math.ceil((map.size() + map.size() / 2.0) * pixelsPerSample) + 1;
        int height = (int) Math.round(map.size() * ROW_HEIGHT * pixelsPerSample);
        exportRegion(map, file, pixelsPerSample, 0, 0, width, height);
    }

    /** A {@code width x height} window whose top-left corner is pixel ({@code left}, {@code top}) of the full picture at this scale. */
    static void exportRegion(BiomeMap map, Path file, int pixelsPerSample, int left, int top, int width, int height) {
        if (pixelsPerSample < 1 || width < 1 || height < 1) {
            throw new IllegalArgumentException("BiomeMapPngExporter: scale and size must be positive, got " + pixelsPerSample + ", " + width + "x" + height);
        }
        int[][] biomeAtPixel = new int[height + 1][width + 1];
        for (int y = 0; y <= height; y++) {
            for (int x = 0; x <= width; x++) {
                biomeAtPixel[y][x] = biomeUnderPixel(map, pixelsPerSample, left + x, top + y);
            }
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int biome = biomeAtPixel[y][x];
                if (biome == OUTSIDE_TILE) {
                    image.setRGB(x, y, BACKGROUND_RGB);
                } else if (isBorder(biomeAtPixel, x, y)) {
                    image.setRGB(x, y, OUTLINE_RGB);
                } else {
                    image.setRGB(x, y, ToroidalPatternPngExporter.colorOf(biome).getRGB());
                }
            }
        }
        write(image, file);
    }

    private static boolean isBorder(int[][] biomeAtPixel, int x, int y) {
        int here = biomeAtPixel[y][x];
        int right = biomeAtPixel[y][x + 1];
        int below = biomeAtPixel[y + 1][x];
        return (right != OUTSIDE_TILE && right != here) || (below != OUTSIDE_TILE && below != here);
    }

    private static int biomeUnderPixel(BiomeMap map, int pixelsPerSample, int pixelX, int pixelY) {
        double j = pixelY / (ROW_HEIGHT * pixelsPerSample);
        double i = pixelX / (double) pixelsPerSample - j / 2;
        int sampleI = (int) Math.floor(i);
        int sampleJ = (int) Math.floor(j);
        if (sampleI < 0 || sampleI >= map.size() || sampleJ < 0 || sampleJ >= map.size()) {
            return OUTSIDE_TILE;
        }
        return map.biomeAt(sampleI, sampleJ);
    }

    private static void write(BufferedImage image, Path file) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (!ImageIO.write(image, "png", file.toFile())) {
                throw new IllegalStateException("BiomeMapPngExporter: no PNG writer available for " + file);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("BiomeMapPngExporter: could not write " + file, failure);
        }
    }
}
