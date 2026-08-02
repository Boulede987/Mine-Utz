package com.mineutze.worldgen.export;

import com.mineutze.worldgen.regionsolve.MacroCellPos;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/**
 * One color block per macro-cell (see {@link BiomeLegend}), with a darker
 * grid line drawn at every region boundary — the whole point of exporting
 * an NxN swath instead of one region is to *see* whether a patch lines up
 * across that boundary, so the seam has to stay visible in the image.
 */
public final class PngExporter {

    private static final int PIXELS_PER_CELL = 4;
    private static final Color REGION_BOUNDARY_COLOR = new Color(0, 0, 0, 160);
    // A solved region never has a gap; a real-world scan can (an ungenerated chunk,
    // world border, etc). Distinct mid-gray, unlikely to collide with a hashed biome
    // color, so a gap reads as "no data" at a glance instead of a wrong biome.
    private static final Color GAP_COLOR = new Color(128, 128, 128);

    private PngExporter() {
    }

    public static void write(Path file, ExportSnapshot snapshot, BiomeLegend legend) {
        int regionSize = snapshot.gridConfig().regionSizeCellsPerSide();
        int minCellX = snapshot.minRegion().x() * regionSize;
        int minCellZ = snapshot.minRegion().z() * regionSize;
        int span = snapshot.spanCells();
        int imageSize = span * PIXELS_PER_CELL;

        BufferedImage image = new BufferedImage(imageSize, imageSize, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();

        for (int localZ = 0; localZ < span; localZ++) {
            for (int localX = 0; localX < span; localX++) {
                MacroCellPos cell = new MacroCellPos(minCellX + localX, minCellZ + localZ);
                g.setColor(snapshot.cells().containsKey(cell) ? new Color(legend.rgbOf(snapshot.biomeAt(cell))) : GAP_COLOR);
                g.fillRect(localX * PIXELS_PER_CELL, localZ * PIXELS_PER_CELL, PIXELS_PER_CELL, PIXELS_PER_CELL);
            }
        }

        g.setColor(REGION_BOUNDARY_COLOR);
        for (int localCell = 0; localCell <= span; localCell += regionSize) {
            int pixel = localCell * PIXELS_PER_CELL;
            g.drawLine(pixel, 0, pixel, imageSize - 1);
            g.drawLine(0, pixel, imageSize - 1, pixel);
        }
        g.dispose();

        try {
            ImageIO.write(image, "png", file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed writing PNG export to " + file, e);
        }
    }
}
