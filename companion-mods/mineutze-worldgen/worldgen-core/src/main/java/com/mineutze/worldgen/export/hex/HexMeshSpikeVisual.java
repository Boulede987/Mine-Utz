package com.mineutze.worldgen.export.hex;

import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.regionsolve.topology.EdgeDisplacement;
import com.mineutze.worldgen.regionsolve.topology.FaceSizeFeasibility;
import com.mineutze.worldgen.regionsolve.topology.HexFace;
import com.mineutze.worldgen.regionsolve.topology.HexMeshBuilder;
import com.mineutze.worldgen.regionsolve.topology.NodeEdge;
import com.mineutze.worldgen.regionsolve.topology.Point2D;
import com.mineutze.worldgen.regionsolve.topology.TriangleAdjacency;
import com.mineutze.worldgen.regionsolve.topology.TriangleIdentitySizeAssignment;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 0 gate spike for doc/decisions/2026-08-29-hex-mesh-edge-displacement.md:
 * a throwaway visual comparing the raw hex mesh against its edge-displaced
 * version, side by side — the decision doc's own flagged open risk ("needs a
 * visual check, not just a legality argument"), not something a unit test can
 * confirm. Widths fed to {@link FaceSizeFeasibility} here are SYNTHETIC (a
 * deterministic wobble around each face's own raw area, not a real CSP run)
 * purely to make bias-driven bulging visible alongside the organic wiggle.
 */
public final class HexMeshSpikeVisual {

    private static final double SIZE_BLOCKS = 150;
    private static final int BOX_MAX = 900;
    private static final long WORLD_SEED = 2026L;
    private static final double WIGGLE_AMPLITUDE_BLOCKS = 0.10 * SIZE_BLOCKS;
    private static final BiomeId DEMO_BIOME = new BiomeId("demo", "patch");

    private HexMeshSpikeVisual() {
    }

    public static void main(String[] args) {
        Path outputFile = Path.of(args.length > 0 ? args[0] : "hex-mesh-export/hex_mesh_comparison.png");
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(SIZE_BLOCKS, 0, 0, BOX_MAX, BOX_MAX);
        TriangleAdjacency.Adjacency adjacency = TriangleAdjacency.of(mesh.faces());
        Map<Integer, TriangleIdentitySizeAssignment.Assignment> syntheticAssignments = syntheticAssignments(mesh, adjacency);
        Map<NodeEdge, Double> biasByEdge = FaceSizeFeasibility.biasBlocksByEdge(mesh, adjacency, syntheticAssignments);

        BufferedImage image = render(mesh, adjacency, biasByEdge);
        writePng(image, outputFile);
        System.out.println("Wrote " + outputFile.toAbsolutePath());
    }

    /** A deterministic per-face wobble around its own raw-area-equivalent width, NOT a real CSP assignment - purely so {@link FaceSizeFeasibility} has something to react to for this visual. */
    private static Map<Integer, TriangleIdentitySizeAssignment.Assignment> syntheticAssignments(
        HexMeshBuilder.HexMesh mesh, TriangleAdjacency.Adjacency adjacency
    ) {
        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments = new HashMap<>();
        double naturalWidth = SIZE_BLOCKS; // regular hexagon side length == its own circumradius
        for (int faceIndex = 0; faceIndex < mesh.faces().size(); faceIndex++) {
            if (adjacency.hullTouchingFaceIndices().contains(faceIndex)) {
                continue;
            }
            double wobble = 0.35 * Math.sin(faceIndex * 1.7);
            int widthBlocks = (int) Math.round(naturalWidth * (1.0 + wobble));
            assignments.put(faceIndex, new TriangleIdentitySizeAssignment.Assignment(DEMO_BIOME, Math.max(1, widthBlocks)));
        }
        return assignments;
    }

    private static BufferedImage render(HexMeshBuilder.HexMesh mesh, TriangleAdjacency.Adjacency adjacency, Map<NodeEdge, Double> biasByEdge) {
        int margin = 40;
        int panelWidth = BOX_MAX + margin * 2;
        int panelHeight = BOX_MAX + margin * 2 + 40;
        int gap = 30;
        BufferedImage image = new BufferedImage(panelWidth * 2 + gap, panelHeight, BufferedImage.TYPE_INT_RGB);

        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
        g.drawString("raw hex mesh", margin, 30);
        g.drawString("edge-displaced (wiggle + size bias)", panelWidth + gap + margin, 30);

        drawRawMesh(g, mesh, margin, 40);
        drawDisplacedMesh(g, mesh, biasByEdge, panelWidth + gap + margin, 40);

        g.dispose();
        return image;
    }

    private static void drawRawMesh(Graphics2D g, HexMeshBuilder.HexMesh mesh, int offsetX, int offsetY) {
        g.setStroke(new BasicStroke(2f));
        g.setColor(new Color(0x2255AA));
        for (HexFace face : mesh.faces()) {
            List<Integer> vertexIndices = face.vertexIndices();
            int cornerCount = vertexIndices.size();
            for (int i = 0; i < cornerCount; i++) {
                Point2D p1 = mesh.vertices().get(vertexIndices.get(i));
                Point2D p2 = mesh.vertices().get(vertexIndices.get((i + 1) % cornerCount));
                g.drawLine(offsetX + (int) p1.x(), offsetY + (int) p1.z(), offsetX + (int) p2.x(), offsetY + (int) p2.z());
            }
        }
    }

    private static void drawDisplacedMesh(Graphics2D g, HexMeshBuilder.HexMesh mesh, Map<NodeEdge, Double> biasByEdge, int offsetX, int offsetY) {
        g.setStroke(new BasicStroke(2f));
        g.setColor(new Color(0xCC4422));
        Set<Long> drawnEdges = new HashSet<>();
        for (HexFace face : mesh.faces()) {
            List<Integer> vertexIndices = face.vertexIndices();
            int cornerCount = vertexIndices.size();
            for (int i = 0; i < cornerCount; i++) {
                int aIndex = vertexIndices.get(i);
                int bIndex = vertexIndices.get((i + 1) % cornerCount);
                long edgeKey = edgeKey(aIndex, bIndex);
                if (!drawnEdges.add(edgeKey)) {
                    continue;
                }
                Point2D a = mesh.vertices().get(aIndex);
                Point2D b = mesh.vertices().get(bIndex);
                double bias = biasByEdge.getOrDefault(NodeEdge.of(aIndex, bIndex), 0.0);
                List<Point2D> polyline = EdgeDisplacement.displacedPolyline(a, b, WORLD_SEED, WIGGLE_AMPLITUDE_BLOCKS, bias);
                for (int p = 0; p < polyline.size() - 1; p++) {
                    Point2D p1 = polyline.get(p);
                    Point2D p2 = polyline.get(p + 1);
                    g.drawLine(offsetX + (int) p1.x(), offsetY + (int) p1.z(), offsetX + (int) p2.x(), offsetY + (int) p2.z());
                }
            }
        }
    }

    private static long edgeKey(int a, int b) {
        int low = Math.min(a, b);
        int high = Math.max(a, b);
        return ((long) low << 32) | (high & 0xFFFF_FFFFL);
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
