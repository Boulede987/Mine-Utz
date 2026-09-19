package com.mineutze.worldgen.coherence;

import com.mineutze.worldgen.biomegraph.BiomeId;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Draws a toroidal pattern as a PNG so a person can judge its shapes by eye (2026-09-19): every
 * cell a pointy-top hexagon coloured by its biome, a dark line wherever two neighboring cells hold
 * different biomes so each cluster's outline is visible. Axial coordinates are drawn as they are,
 * so the torus shows up as the skewed parallelogram it really is. Colors come from a biome's
 * position in the order given, so two images of the same catalog use the same colors.
 */
final class ToroidalPatternPngExporter {

    static final int HEX_RADIUS_PIXELS = 12;

    private static final int MARGIN_PIXELS = 4;
    private static final double HEX_WIDTH_PIXELS = Math.sqrt(3) * HEX_RADIUS_PIXELS;
    private static final double ROW_HEIGHT_PIXELS = 1.5 * HEX_RADIUS_PIXELS;
    private static final int[][] NEIGHBOR_DELTAS = {{1, 0}, {1, -1}, {0, -1}, {-1, 0}, {-1, 1}, {0, 1}};
    private static final double GOLDEN_RATIO_CONJUGATE = 0.61803398875;

    private ToroidalPatternPngExporter() {
    }

    static void export(ToroidalGrid grid, Map<ToroidalPos, BiomeId> assignment, List<BiomeId> colorOrder, Path file) {
        for (BiomeId biome : assignment.values()) {
            if (!colorOrder.contains(biome)) {
                throw new IllegalArgumentException("ToroidalPatternPngExporter: " + biome + " is in the assignment but not in the color order");
            }
        }
        int width = 2 * MARGIN_PIXELS + (int) Math.ceil(HEX_WIDTH_PIXELS * (grid.width() + grid.height() / 2.0 + 1));
        int height = 2 * MARGIN_PIXELS + (int) Math.ceil(ROW_HEIGHT_PIXELS * grid.height() + HEX_RADIUS_PIXELS);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            for (ToroidalPos pos : grid.allPositionsInFillOrder()) {
                graphics.setColor(colorOf(colorOrder.indexOf(assignment.get(pos))));
                graphics.fillPolygon(hexagonAround(centerXOf(pos), centerYOf(pos)));
            }
            graphics.setColor(new Color(20, 20, 20));
            graphics.setStroke(new BasicStroke(2f));
            for (ToroidalPos pos : grid.allPositionsInFillOrder()) {
                drawOutlineEdges(graphics, grid, assignment, pos);
            }
        } finally {
            graphics.dispose();
        }
        write(image, file);
    }

    /** Pixel column of the middle of {@code pos}'s hexagon. */
    static int centerXOf(ToroidalPos pos) {
        return (int) Math.round(MARGIN_PIXELS + HEX_WIDTH_PIXELS * (pos.q() + pos.r() / 2.0 + 1));
    }

    /** Pixel row of the middle of {@code pos}'s hexagon. */
    static int centerYOf(ToroidalPos pos) {
        return (int) Math.round(MARGIN_PIXELS + HEX_RADIUS_PIXELS + ROW_HEIGHT_PIXELS * pos.r());
    }

    private static Polygon hexagonAround(int centerX, int centerY) {
        Polygon hexagon = new Polygon();
        for (int corner = 0; corner < 6; corner++) {
            double angle = Math.toRadians(30 + 60 * corner);
            hexagon.addPoint((int) Math.round(centerX + HEX_RADIUS_PIXELS * Math.cos(angle)), (int) Math.round(centerY + HEX_RADIUS_PIXELS * Math.sin(angle)));
        }
        return hexagon;
    }

    /** Draws, on {@code pos}'s hexagon, the edge facing each neighbor holding a different biome. Neighbor directions are in ring order, edge k facing angle 60k degrees clockwise from east once y points down. */
    private static void drawOutlineEdges(Graphics2D graphics, ToroidalGrid grid, Map<ToroidalPos, BiomeId> assignment, ToroidalPos pos) {
        int centerX = centerXOf(pos);
        int centerY = centerYOf(pos);
        for (int[] delta : NEIGHBOR_DELTAS) {
            ToroidalPos neighbor = grid.normalize(pos.q() + delta[0], pos.r() + delta[1]);
            if (assignment.get(neighbor).equals(assignment.get(pos))) {
                continue;
            }
            double facing = Math.atan2(ROW_HEIGHT_PIXELS * delta[1], HEX_WIDTH_PIXELS * (delta[0] + delta[1] / 2.0));
            double first = facing - Math.toRadians(30);
            double second = facing + Math.toRadians(30);
            graphics.drawLine(
                (int) Math.round(centerX + HEX_RADIUS_PIXELS * Math.cos(first)), (int) Math.round(centerY + HEX_RADIUS_PIXELS * Math.sin(first)),
                (int) Math.round(centerX + HEX_RADIUS_PIXELS * Math.cos(second)), (int) Math.round(centerY + HEX_RADIUS_PIXELS * Math.sin(second))
            );
        }
    }

    /** Spreads consecutive biome indices around the hue wheel by the golden ratio so neighbors in the order look different. */
    private static Color colorOf(int colorIndex) {
        float hue = (float) ((colorIndex * GOLDEN_RATIO_CONJUGATE) % 1.0);
        float brightness = colorIndex % 2 == 0 ? 0.95f : 0.75f;
        return Color.getHSBColor(hue, 0.55f, brightness);
    }

    private static void write(BufferedImage image, Path file) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (!ImageIO.write(image, "png", file.toFile())) {
                throw new IllegalStateException("ToroidalPatternPngExporter: no PNG writer available for " + file);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("ToroidalPatternPngExporter: could not write " + file, failure);
        }
    }
}
