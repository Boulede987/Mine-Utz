package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static com.mineutze.worldgen.plan.ToroidalTestTables.A;
import static com.mineutze.worldgen.plan.ToroidalTestTables.B;
import static com.mineutze.worldgen.plan.ToroidalTestTables.C;
import static com.mineutze.worldgen.plan.ToroidalTestTables.threeColoring;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaneWindowPngExporterTest {

    private static final ToroidalGrid GRID = new ToroidalGrid(6, 6);
    private static final HexTorusGeometry GEOMETRY = new HexTorusGeometry(6, 6, 300);
    private static final List<BiomeId> ORDER = List.of(A, B, C);

    private static RealizedBiomeField field() {
        return new RealizedBiomeField(GRID, threeColoring(GRID, A, B, C), GEOMETRY, DivergenceFreeWarp.none(GEOMETRY), ORDER);
    }

    private static int redPixels(BufferedImage image) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (image.getRGB(x, y) == PlaneWindowPngExporter.TILE_EDGE_RGB) {
                    count++;
                }
            }
        }
        return count;
    }

    @Test
    void theImageCoversTheRequestedWindowAtTheRequestedScale(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("window.png");

        PlaneWindowPngExporter.export(field(), GEOMETRY, file, -500, -300, 2_000, 1_000, 4, false);

        BufferedImage image = ImageIO.read(file.toFile());
        assertEquals(500, image.getWidth());
        assertEquals(250, image.getHeight());
    }

    @Test
    void aPixelHasTheColorOfTheBiomeAtItsWorldPosition(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("window.png");
        PlaneWindowPngExporter.export(field(), GEOMETRY, file, 0, 0, 2_000, 1_000, 4, false);
        BufferedImage image = ImageIO.read(file.toFile());
        ToroidalPos cell = new ToroidalPos(3, 2);
        double centerX = GEOMETRY.centerX(cell);
        double centerY = GEOMETRY.centerY(cell);

        int expected = ToroidalPatternPngExporter.colorOf(field().biomeIndexAt(centerX, centerY)).getRGB();

        assertEquals(expected, image.getRGB((int) (centerX / 4), (int) (centerY / 4)));
    }

    @Test
    void tileEdgesAreDrawnOnlyWhenAsked(@TempDir Path directory) throws IOException {
        Path without = directory.resolve("without.png");
        Path with = directory.resolve("with.png");

        PlaneWindowPngExporter.export(field(), GEOMETRY, without, -1_000, -500, 4_000, 2_500, 8, false);
        PlaneWindowPngExporter.export(field(), GEOMETRY, with, -1_000, -500, 4_000, 2_500, 8, true);

        assertEquals(0, redPixels(ImageIO.read(without.toFile())));
        assertTrue(redPixels(ImageIO.read(with.toFile())) > 100, "expected the tile edge lines to be drawn");
    }

    @Test
    void rejectsANonPositiveWindowOrScale(@TempDir Path directory) {
        Path file = directory.resolve("window.png");

        assertThrows(IllegalArgumentException.class, () -> PlaneWindowPngExporter.export(field(), GEOMETRY, file, 0, 0, 0, 100, 4, false));
        assertThrows(IllegalArgumentException.class, () -> PlaneWindowPngExporter.export(field(), GEOMETRY, file, 0, 0, 100, 100, 0, false));
    }
}
