package com.mineutze.worldgen.plan;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.IntStream;

/**
 * Draws a window of the infinite world as a player would see it (2026-09-20): plain axis-aligned
 * world coordinates, one {@link RealizedBiomeField} lookup per pixel, north (y) downward. Unlike the
 * single-tile picture this can straddle tile edges, which is what shows whether tiles meet without
 * a seam; the edges can optionally be drawn as thin red lines to show exactly where they are.
 */
final class PlaneWindowPngExporter {

    static final int TILE_EDGE_RGB = new Color(230, 20, 20).getRGB();

    private static final int OUTLINE_RGB = new Color(20, 20, 20).getRGB();

    private PlaneWindowPngExporter() {
    }

    /** The window has its top-left corner at ({@code leftBlock}, {@code topBlock}) and is {@code widthBlocks x heightBlocks} big; each pixel covers {@code blocksPerPixel} blocks square. */
    static void export(
        RealizedBiomeField field, HexTorusGeometry geometry, Path file, double leftBlock, double topBlock, double widthBlocks, double heightBlocks,
        double blocksPerPixel, boolean markTileEdges
    ) {
        if (widthBlocks <= 0 || heightBlocks <= 0 || blocksPerPixel <= 0) {
            throw new IllegalArgumentException("PlaneWindowPngExporter: window and scale must be positive, got " + widthBlocks + "x" + heightBlocks + " at " + blocksPerPixel);
        }
        int width = (int) Math.ceil(widthBlocks / blocksPerPixel);
        int height = (int) Math.ceil(heightBlocks / blocksPerPixel);
        int[][] biomeAtPixel = new int[height][width];
        IntStream.range(0, height).parallel().forEach(row -> {
            for (int column = 0; column < width; column++) {
                biomeAtPixel[row][column] = field.biomeIndexAt(leftBlock + (column + 0.5) * blocksPerPixel, topBlock + (row + 0.5) * blocksPerPixel);
            }
        });

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                boolean border = (column + 1 < width && biomeAtPixel[row][column + 1] != biomeAtPixel[row][column])
                    || (row + 1 < height && biomeAtPixel[row + 1][column] != biomeAtPixel[row][column]);
                int rgb = border ? OUTLINE_RGB : ToroidalPatternPngExporter.colorOf(biomeAtPixel[row][column]).getRGB();
                if (markTileEdges && isOnTileEdge(geometry, leftBlock + (column + 0.5) * blocksPerPixel, topBlock + (row + 0.5) * blocksPerPixel, blocksPerPixel)) {
                    rgb = TILE_EDGE_RGB;
                }
                image.setRGB(column, row, rgb);
            }
        }
        write(image, file);
    }

    /**
     * Tile edges are the lines {@code u = whole number} and {@code v = whole number} of the tile's own
     * coordinates ({@code P = u * A + v * B}). A point is on one when it is within one pixel of it.
     */
    private static boolean isOnTileEdge(HexTorusGeometry geometry, double x, double y, double blocksPerPixel) {
        double ax = geometry.tileVectorAX();
        double ay = geometry.tileVectorAY();
        double bx = geometry.tileVectorBX();
        double by = geometry.tileVectorBY();
        double area = ax * by - ay * bx;
        double u = (by * x - bx * y) / area;
        double v = (ax * y - ay * x) / area;
        double spacingOfULines = area / Math.hypot(bx, by);
        double spacingOfVLines = area / Math.hypot(ax, ay);
        return Math.abs(u - Math.rint(u)) * spacingOfULines < blocksPerPixel || Math.abs(v - Math.rint(v)) * spacingOfVLines < blocksPerPixel;
    }

    private static void write(BufferedImage image, Path file) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (!ImageIO.write(image, "png", file.toFile())) {
                throw new IllegalStateException("PlaneWindowPngExporter: no PNG writer available for " + file);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("PlaneWindowPngExporter: could not write " + file, failure);
        }
    }
}
