package com.mineutze.worldgen.export.hex;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;
import com.mineutze.worldgen.biomegraph.ModBiomeGraph;
import com.mineutze.worldgen.regionsolve.EdgeDisplacementRasterizer;
import com.mineutze.worldgen.regionsolve.MacroCellPos;
import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mineutze.worldgen.regionsolve.topology.HexMeshBuilder;
import com.mineutze.worldgen.regionsolve.topology.Point2D;
import com.mineutze.worldgen.regionsolve.topology.TriangleAdjacency;
import com.mineutze.worldgen.regionsolve.topology.TriangleIdentitySizeAssignment;
import com.mineutze.worldgen.regionsolve.topology.WeightedVertexPlacement;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Live-path gate visual: the actual rasterized biome output of the real pipeline (hex
 * mesh -> real CSP against {@link ModBiomeGraph#OVERWORLD} -> {@link
 * WeightedVertexPlacement} -> {@link EdgeDisplacementRasterizer}), colored per biome —
 * the real end result a player would see, at the real 48-biome catalog and the real
 * hex size this pipeline actually ships with.
 */
public final class HexBiomeRasterVisual {

    // Matches TriangleFieldGeometryRealization.HEX_SIZE_BLOCKS - kept in sync manually
    // since worldgen-core's export/ tools are deliberately standalone from the live
    // orchestrator, not a dependency on it.
    private static final double SIZE_BLOCKS = 325;
    private static final int MESH_BOX_MAX = 8000;
    private static final int REAL_MIN = 1600;
    private static final int REAL_MAX = 6400;
    private static final long WORLD_SEED = 2026L;
    private static final MacroGridConfig GRID_CONFIG = new MacroGridConfig(4, 512);

    private HexBiomeRasterVisual() {
    }

    public static void main(String[] args) {
        Path outputDir = Path.of(args.length > 0 ? args[0] : "hex-mesh-export");

        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(SIZE_BLOCKS, 0, 0, MESH_BOX_MAX, MESH_BOX_MAX);
        TriangleAdjacency.Adjacency adjacency = TriangleAdjacency.of(mesh.faces());
        Set<Integer> freeFaceIndices = new HashSet<>();
        for (int faceIndex = 0; faceIndex < mesh.faces().size(); faceIndex++) {
            if (!adjacency.hullTouchingFaceIndices().contains(faceIndex)) {
                freeFaceIndices.add(faceIndex);
            }
        }

        BiomeGraph graph = ModBiomeGraph.OVERWORLD;
        Optional<Map<Integer, TriangleIdentitySizeAssignment.Assignment>> solved =
            TriangleIdentitySizeAssignment.solve(adjacency, freeFaceIndices, Map.of(), graph, WORLD_SEED);
        if (solved.isEmpty()) {
            throw new IllegalStateException("no legal CSP assignment found for this mesh - shouldn't happen at this scale against ModBiomeGraph.OVERWORLD");
        }
        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments = solved.get();
        double minEdgeLengthBlocks = 3 * GRID_CONFIG.macroCellSizeBlocks(); // matches TriangleFieldGeometryRealization.MIN_EDGE_LENGTH_MACRO_CELLS
        Map<Integer, Point2D> displacedVertices = WeightedVertexPlacement.displacedVertexPositions(mesh, adjacency, assignments, SIZE_BLOCKS, minEdgeLengthBlocks);

        List<MacroCellPos> cells = allCellsIn(GRID_CONFIG, REAL_MIN, REAL_MIN, REAL_MAX, REAL_MAX);
        Map<MacroCellPos, BiomeId> result = EdgeDisplacementRasterizer.rasterize(cells, assignments, mesh, adjacency, displacedVertices, Map.of(), Map.of(), GRID_CONFIG, WORLD_SEED);

        Map<BiomeId, Integer> palette = evenlySpacedPalette(graph);
        Path rasterFile = outputDir.resolve("hex_biome_raster.png");
        Path legendFile = outputDir.resolve("hex_biome_legend.png");
        writePng(renderRaster(cells, result, palette), rasterFile);
        writePng(renderLegend(graph, palette), legendFile);
        System.out.println("Wrote " + rasterFile.toAbsolutePath());
        System.out.println("Wrote " + legendFile.toAbsolutePath());
    }

    private static List<MacroCellPos> allCellsIn(MacroGridConfig gridConfig, int minX, int minZ, int maxX, int maxZ) {
        int cellSize = gridConfig.macroCellSizeBlocks();
        int minCellX = Math.floorDiv(minX, cellSize);
        int maxCellX = Math.floorDiv(maxX - 1, cellSize);
        int minCellZ = Math.floorDiv(minZ, cellSize);
        int maxCellZ = Math.floorDiv(maxZ - 1, cellSize);
        List<MacroCellPos> cells = new ArrayList<>();
        for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
            for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                cells.add(new MacroCellPos(cellX, cellZ));
            }
        }
        return cells;
    }

    /**
     * Evenly-spaced hues at this small, known biome count - maximizes visual separation,
     * same reasoning {@code TriangleFieldPngExporter} already uses for the old pipeline's
     * own export. Package-visible (not private) so {@link HexRegionGridVisual} can reuse
     * the exact same rendering pipeline for a multi-region view instead of duplicating it.
     */
    static Map<BiomeId, Integer> evenlySpacedPalette(BiomeGraph graph) {
        List<BiomeId> sortedIds = graph.nodes().stream().map(BiomeNode::id).sorted().toList();
        Map<BiomeId, Integer> palette = new LinkedHashMap<>();
        for (int i = 0; i < sortedIds.size(); i++) {
            float hue = (float) i / sortedIds.size();
            palette.put(sortedIds.get(i), Color.HSBtoRGB(hue, 0.75f, 0.9f) & 0xFFFFFF);
        }
        return palette;
    }

    static BufferedImage renderRaster(List<MacroCellPos> cells, Map<MacroCellPos, BiomeId> result, Map<BiomeId, Integer> palette) {
        int minCellX = cells.stream().mapToInt(MacroCellPos::x).min().orElseThrow();
        int minCellZ = cells.stream().mapToInt(MacroCellPos::z).min().orElseThrow();
        int maxCellX = cells.stream().mapToInt(MacroCellPos::x).max().orElseThrow();
        int maxCellZ = cells.stream().mapToInt(MacroCellPos::z).max().orElseThrow();
        int rasterWidth = maxCellX - minCellX + 1;
        int rasterHeight = maxCellZ - minCellZ + 1;

        BufferedImage image = new BufferedImage(rasterWidth, rasterHeight, BufferedImage.TYPE_INT_RGB);
        for (MacroCellPos cell : cells) {
            image.setRGB(cell.x() - minCellX, cell.z() - minCellZ, palette.get(result.get(cell)));
        }
        return image;
    }

    /** An actual color swatch per biome, own image - matches {@code TriangleFieldPngExporter}'s own established convention (a stacked/inline legend baked into the raster ran the label off the image once there were more than a handful of biomes). */
    static BufferedImage renderLegend(BiomeGraph graph, Map<BiomeId, Integer> palette) {
        List<BiomeId> sortedIds = graph.nodes().stream().map(BiomeNode::id).sorted().toList();

        int swatchSize = 24;
        int rowHeight = 32;
        int padding = 10;
        Font font = new Font(Font.SANS_SERIF, Font.PLAIN, 16);

        BufferedImage measuring = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        FontMetrics metrics = measuring.createGraphics().getFontMetrics(font);
        int maxLabelWidth = sortedIds.stream().mapToInt(id -> metrics.stringWidth(id.toString())).max().orElse(0);

        int width = padding * 3 + swatchSize + maxLabelWidth;
        int height = padding * 2 + sortedIds.size() * rowHeight;

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setFont(font);

        int y = padding;
        for (BiomeId id : sortedIds) {
            g.setColor(new Color(palette.get(id)));
            g.fillRect(padding, y, swatchSize, swatchSize);
            g.setColor(Color.BLACK);
            g.drawRect(padding, y, swatchSize, swatchSize);
            g.drawString(id.toString(), padding * 2 + swatchSize, y + swatchSize - metrics.getDescent());
            y += rowHeight;
        }
        g.dispose();
        return image;
    }

    static void writePng(BufferedImage image, Path file) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            ImageIO.write(image, "png", file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
