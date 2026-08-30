package com.mineutze.worldgen.export.hex;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;
import com.mineutze.worldgen.biomegraph.ModBiomeGraph;
import com.mineutze.worldgen.regionsolve.EdgeDisplacementRasterizer;
import com.mineutze.worldgen.regionsolve.MacroCellPos;
import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mineutze.worldgen.regionsolve.topology.FaceSizeFeasibility;
import com.mineutze.worldgen.regionsolve.topology.HexMeshBuilder;
import com.mineutze.worldgen.regionsolve.topology.NodeEdge;
import com.mineutze.worldgen.regionsolve.topology.TriangleAdjacency;
import com.mineutze.worldgen.regionsolve.topology.TriangleIdentitySizeAssignment;

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

/** One-off comparison spike: smaller hex size relative to biome target widths, bias mechanism disabled (wiggle-only displacement) - NOT wired into the real pipeline, just to inform whether this direction should replace the current tuning. */
public final class HexRasterSizeExperiment {

    private static final long WORLD_SEED = 2026L;
    private static final MacroGridConfig GRID_CONFIG = new MacroGridConfig(4, 512);

    public static void main(String[] args) {
        double sizeBlocks = args.length > 0 ? Double.parseDouble(args[0]) : 40;
        boolean useBias = args.length > 1 && args[1].equals("bias");
        Path outputFile = Path.of(args.length > 2 ? args[2] : "hex-mesh-export/hex_size_experiment.png");
        int meshBoxMax = args.length > 3 ? Integer.parseInt(args[3]) : 2000;
        int realMin = args.length > 5 ? Integer.parseInt(args[4]) : 400;
        int realMax = args.length > 5 ? Integer.parseInt(args[5]) : 1600;
        boolean useOverworld = args.length > 6 && args[6].equals("overworld");

        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(sizeBlocks, 0, 0, meshBoxMax, meshBoxMax);
        TriangleAdjacency.Adjacency adjacency = TriangleAdjacency.of(mesh.faces());
        Set<Integer> freeFaceIndices = new HashSet<>();
        for (int faceIndex = 0; faceIndex < mesh.faces().size(); faceIndex++) {
            if (!adjacency.hullTouchingFaceIndices().contains(faceIndex)) {
                freeFaceIndices.add(faceIndex);
            }
        }

        BiomeGraph graph = useOverworld ? ModBiomeGraph.OVERWORLD : ModBiomeGraph.DEFAULT;
        Optional<Map<Integer, TriangleIdentitySizeAssignment.Assignment>> solved =
            TriangleIdentitySizeAssignment.solve(adjacency, freeFaceIndices, Map.of(), graph, WORLD_SEED);
        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments = solved.orElseThrow();
        Map<NodeEdge, Double> biasByEdge = useBias ? FaceSizeFeasibility.biasBlocksByEdge(mesh, adjacency, assignments) : Map.of();

        List<MacroCellPos> cells = allCellsIn(GRID_CONFIG, realMin, realMin, realMax, realMax);
        Map<MacroCellPos, BiomeId> result = EdgeDisplacementRasterizer.rasterize(cells, assignments, mesh, biasByEdge, Map.of(), Map.of(), GRID_CONFIG, WORLD_SEED);

        Map<BiomeId, Integer> palette = evenlySpacedPalette(graph);
        Path legendFile = legendPathFor(outputFile);
        writePng(renderRaster(cells, result, palette), outputFile);
        writePng(renderLegend(graph, palette), legendFile);
        System.out.println("size=" + sizeBlocks + " bias=" + useBias + " -> " + outputFile.toAbsolutePath());
        System.out.println("legend -> " + legendFile.toAbsolutePath());
    }

    private static Path legendPathFor(Path rasterFile) {
        String fileName = rasterFile.getFileName().toString();
        String legendName = fileName.contains(".")
            ? fileName.substring(0, fileName.lastIndexOf('.')) + "_legend" + fileName.substring(fileName.lastIndexOf('.'))
            : fileName + "_legend";
        return rasterFile.resolveSibling(legendName);
    }

    /** An actual color swatch per biome, own image - matches {@code TriangleFieldPngExporter}'s/{@code HexBiomeRasterVisual}'s own established convention (a legend baked into the raster ran labels off the image once there were more than a handful of biomes). */
    private static BufferedImage renderLegend(BiomeGraph graph, Map<BiomeId, Integer> palette) {
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

    private static Map<BiomeId, Integer> evenlySpacedPalette(BiomeGraph graph) {
        List<BiomeId> sortedIds = graph.nodes().stream().map(BiomeNode::id).sorted().toList();
        Map<BiomeId, Integer> palette = new LinkedHashMap<>();
        for (int i = 0; i < sortedIds.size(); i++) {
            float hue = (float) i / sortedIds.size();
            palette.put(sortedIds.get(i), Color.HSBtoRGB(hue, 0.75f, 0.9f) & 0xFFFFFF);
        }
        return palette;
    }

    private static BufferedImage renderRaster(List<MacroCellPos> cells, Map<MacroCellPos, BiomeId> result, Map<BiomeId, Integer> palette) {
        int minCellX = cells.stream().mapToInt(MacroCellPos::x).min().orElseThrow();
        int minCellZ = cells.stream().mapToInt(MacroCellPos::z).min().orElseThrow();
        int maxCellX = cells.stream().mapToInt(MacroCellPos::x).max().orElseThrow();
        int maxCellZ = cells.stream().mapToInt(MacroCellPos::z).max().orElseThrow();
        BufferedImage image = new BufferedImage(maxCellX - minCellX + 1, maxCellZ - minCellZ + 1, BufferedImage.TYPE_INT_RGB);
        for (MacroCellPos cell : cells) {
            image.setRGB(cell.x() - minCellX, cell.z() - minCellZ, palette.get(result.get(cell)));
        }
        return image;
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

    private static void writePng(BufferedImage image, Path file) {
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
