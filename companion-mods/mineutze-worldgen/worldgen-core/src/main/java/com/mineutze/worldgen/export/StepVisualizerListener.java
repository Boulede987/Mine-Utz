package com.mineutze.worldgen.export;

import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.regionsolve.MacroCellPos;
import com.mineutze.worldgen.regionsolve.MeshGeometryRealization;
import com.mineutze.worldgen.regionsolve.RegionSolveInput;
import com.mineutze.worldgen.regionsolve.mesh.FineMesh;
import com.mineutze.worldgen.regionsolve.mesh.MeshAssignment;
import com.mineutze.worldgen.regionsolve.mesh.ScatteredPoint;
import com.mineutze.worldgen.regionsolve.topology.IndexedTriangle;
import com.mineutze.worldgen.regionsolve.topology.NodeEdge;
import com.mineutze.worldgen.regionsolve.topology.Point2D;
import com.mineutze.worldgen.regionsolve.topology.SeedGraph;
import com.mineutze.worldgen.regionsolve.topology.SeedGraphNode;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * One numbered PNG per {@link MeshGeometryRealization.StageListener} callback,
 * written to its own region subfolder — lets the whole mesh pipeline (seed
 * graph, scatter, triangulation, growth, gap-fill, size enforcement,
 * displacement, rasterize, pixel repair, final) be watched step by step
 * instead of only ever seeing the finished result. Debug/teaching tool only,
 * never on the live path — {@link MeshGeometryRealization} defaults to a
 * no-op listener there.
 *
 * <p>One instance per region (constructed fresh per {@code
 * TopologyFirstRegionSolver.GeometryRealizer} call by {@link
 * StepVisualizerCli}) — the mesh-frame bounding box is cached from the first
 * mesh-level callback ({@link #afterScatter}) and reused for every later
 * mesh-level stage, so triangles stay at the same place/scale across images
 * instead of the frame subtly shifting stage to stage.
 */
final class StepVisualizerListener implements MeshGeometryRealization.StageListener {

    private static final int MESH_PIXELS_PER_CELL = 14;
    private static final int GRID_PIXELS_PER_CELL = 14;
    // Cached mesh frame is padded past the exact bounding box so small
    // EdgeDisplacement overshoot (the displacement stage) never falls
    // outside it and gets silently clipped - the frame must stay IDENTICAL
    // stage to stage, not recomputed per call, or triangles would visibly
    // jump between images.
    private static final double MESH_FRAME_PADDING_FRACTION = 0.08;
    private static final Color BACKGROUND = new Color(30, 30, 30);
    private static final Color UNCLAIMED_FILL = new Color(90, 90, 90);
    private static final Color TRIANGLE_OUTLINE = new Color(0, 0, 0, 90);
    private static final Color EDGE_COLOR = new Color(200, 200, 200);
    private static final Color FIXED_NODE_OUTLINE = Color.WHITE;
    private static final Color FREE_NODE_OUTLINE = Color.BLACK;

    private final Path regionDir;
    private final BiomeLegend legend;
    private int stageIndex = 0;
    private Viewport meshFrame;

    StepVisualizerListener(Path regionDir, BiomeLegend legend) {
        this.regionDir = regionDir;
        this.legend = legend;
        try {
            Files.createDirectories(regionDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create step-visualizer output directory " + regionDir, e);
        }
    }

    @Override
    public void afterSeedGraph(SeedGraph seedGraph, Map<Integer, BiomeId> assignedIdentities, RegionSolveInput input) {
        Viewport frame = viewportFor(seedGraph.nodes().stream().map(node -> toPoint(node.position())).toList());
        BufferedImage image = blankImage(frame);
        Graphics2D g = image.createGraphics();

        g.setColor(EDGE_COLOR);
        for (NodeEdge edge : seedGraph.edges()) {
            Point2D a = toPoint(seedGraph.node(edge.a()).position());
            Point2D b = toPoint(seedGraph.node(edge.b()).position());
            g.drawLine(frame.px(a), frame.pz(a), frame.px(b), frame.pz(b));
        }

        int radius = Math.max(3, MESH_PIXELS_PER_CELL / 3);
        for (SeedGraphNode node : seedGraph.nodes()) {
            Point2D position = toPoint(node.position());
            BiomeId biome = node.fixed() ? node.fixedBiome() : assignedIdentities.get(node.id());
            g.setColor(biome == null ? UNCLAIMED_FILL : new Color(legend.rgbOf(biome)));
            fillCircle(g, frame, position, radius);
            g.setColor(node.fixed() ? FIXED_NODE_OUTLINE : FREE_NODE_OUTLINE);
            drawCircle(g, frame, position, radius);
        }
        g.dispose();
        write(image, "seed-graph");
    }

    @Override
    public void afterScatter(List<ScatteredPoint> scatter, RegionSolveInput input) {
        Viewport frame = viewportFor(scatter.stream().map(ScatteredPoint::position).toList());
        this.meshFrame = frame;

        BufferedImage image = blankImage(frame);
        Graphics2D g = image.createGraphics();
        int radius = Math.max(2, MESH_PIXELS_PER_CELL / 4);
        for (ScatteredPoint point : scatter) {
            g.setColor(point.germColor().map(legend::rgbOf).map(Color::new).orElse(UNCLAIMED_FILL));
            fillCircle(g, frame, point.position(), radius);
        }
        g.dispose();
        write(image, "scatter");
    }

    @Override
    public void afterMeshBuild(FineMesh mesh, RegionSolveInput input) {
        BufferedImage image = blankImage(meshFrame);
        Graphics2D g = image.createGraphics();

        g.setColor(TRIANGLE_OUTLINE);
        for (IndexedTriangle face : mesh.faces()) {
            drawTriangle(g, meshFrame, mesh.position(face.a()), mesh.position(face.b()), mesh.position(face.c()));
        }

        int radius = Math.max(2, MESH_PIXELS_PER_CELL / 4);
        for (ScatteredPoint point : mesh.points()) {
            if (point.germColor().isEmpty()) {
                continue;
            }
            g.setColor(new Color(legend.rgbOf(point.germColor().get())));
            fillCircle(g, meshFrame, point.position(), radius);
            g.setColor(FREE_NODE_OUTLINE);
            drawCircle(g, meshFrame, point.position(), radius);
        }
        g.dispose();
        write(image, "mesh");
    }

    @Override
    public void afterMeshStage(String stageName, FineMesh mesh, MeshAssignment assignment, RegionSolveInput input) {
        write(renderMesh(mesh, assignment.faceIdentity(), null), stageName);
    }

    @Override
    public void afterDisplacement(FineMesh mesh, MeshAssignment assignment, Map<Integer, Point2D> displaced, RegionSolveInput input) {
        write(renderMesh(mesh, assignment.faceIdentity(), displaced), "displacement");
    }

    @Override
    public void afterPixelStage(String stageName, Map<MacroCellPos, BiomeId> cells, RegionSolveInput input) {
        List<MacroCellPos> regionCells = input.gridConfig().cellsInRegion(input.region(), input.worldSeed());
        int minX = regionCells.stream().mapToInt(MacroCellPos::x).min().orElse(0);
        int minZ = regionCells.stream().mapToInt(MacroCellPos::z).min().orElse(0);
        int maxX = regionCells.stream().mapToInt(MacroCellPos::x).max().orElse(0);
        int maxZ = regionCells.stream().mapToInt(MacroCellPos::z).max().orElse(0);
        int width = (maxX - minX + 1) * GRID_PIXELS_PER_CELL;
        int height = (maxZ - minZ + 1) * GRID_PIXELS_PER_CELL;

        BufferedImage image = new BufferedImage(Math.max(1, width), Math.max(1, height), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(UNCLAIMED_FILL);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        for (MacroCellPos cell : regionCells) {
            BiomeId biome = cells.get(cell);
            g.setColor(biome == null ? UNCLAIMED_FILL : new Color(legend.rgbOf(biome)));
            g.fillRect((cell.x() - minX) * GRID_PIXELS_PER_CELL, (cell.z() - minZ) * GRID_PIXELS_PER_CELL, GRID_PIXELS_PER_CELL, GRID_PIXELS_PER_CELL);
        }
        g.dispose();
        write(image, stageName);
    }

    private BufferedImage renderMesh(FineMesh mesh, Map<Integer, BiomeId> faceIdentity, Map<Integer, Point2D> displaced) {
        BufferedImage image = blankImage(meshFrame);
        Graphics2D g = image.createGraphics();
        for (int faceId = 0; faceId < mesh.faces().size(); faceId++) {
            IndexedTriangle face = mesh.face(faceId);
            Point2D a = positionOf(mesh, face.a(), displaced);
            Point2D b = positionOf(mesh, face.b(), displaced);
            Point2D c = positionOf(mesh, face.c(), displaced);
            BiomeId biome = faceIdentity.get(faceId);
            g.setColor(biome == null ? UNCLAIMED_FILL : new Color(legend.rgbOf(biome)));
            fillTriangle(g, meshFrame, a, b, c);
            g.setColor(TRIANGLE_OUTLINE);
            drawTriangle(g, meshFrame, a, b, c);
        }
        g.dispose();
        return image;
    }

    private static Point2D positionOf(FineMesh mesh, int vertexId, Map<Integer, Point2D> displaced) {
        return displaced == null ? mesh.position(vertexId) : displaced.getOrDefault(vertexId, mesh.position(vertexId));
    }

    private void write(BufferedImage image, String stageName) {
        Path file = regionDir.resolve(String.format("%02d-%s.png", stageIndex++, stageName));
        try {
            ImageIO.write(image, "png", file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed writing step-visualizer PNG " + file, e);
        }
    }

    private static Point2D toPoint(MacroCellPos pos) {
        return new Point2D(pos.x(), pos.z());
    }

    private static BufferedImage blankImage(Viewport frame) {
        BufferedImage image = new BufferedImage(frame.width(), frame.height(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(BACKGROUND);
        g.fillRect(0, 0, frame.width(), frame.height());
        g.dispose();
        return image;
    }

    private static void fillCircle(Graphics2D g, Viewport frame, Point2D at, int radius) {
        g.fillOval(frame.px(at) - radius, frame.pz(at) - radius, radius * 2, radius * 2);
    }

    private static void drawCircle(Graphics2D g, Viewport frame, Point2D at, int radius) {
        g.drawOval(frame.px(at) - radius, frame.pz(at) - radius, radius * 2, radius * 2);
    }

    private static void fillTriangle(Graphics2D g, Viewport frame, Point2D a, Point2D b, Point2D c) {
        g.fillPolygon(new int[]{frame.px(a), frame.px(b), frame.px(c)}, new int[]{frame.pz(a), frame.pz(b), frame.pz(c)}, 3);
    }

    private static void drawTriangle(Graphics2D g, Viewport frame, Point2D a, Point2D b, Point2D c) {
        g.drawPolygon(new int[]{frame.px(a), frame.px(b), frame.px(c)}, new int[]{frame.pz(a), frame.pz(b), frame.pz(c)}, 3);
    }

    private static Viewport viewportFor(List<Point2D> points) {
        double minX = points.stream().mapToDouble(Point2D::x).min().orElse(0);
        double maxX = points.stream().mapToDouble(Point2D::x).max().orElse(0);
        double minZ = points.stream().mapToDouble(Point2D::z).min().orElse(0);
        double maxZ = points.stream().mapToDouble(Point2D::z).max().orElse(0);
        double padX = Math.max(1, (maxX - minX) * MESH_FRAME_PADDING_FRACTION);
        double padZ = Math.max(1, (maxZ - minZ) * MESH_FRAME_PADDING_FRACTION);
        int width = (int) Math.ceil((maxX - minX + 2 * padX) * MESH_PIXELS_PER_CELL);
        int height = (int) Math.ceil((maxZ - minZ + 2 * padZ) * MESH_PIXELS_PER_CELL);
        return new Viewport(minX - padX, minZ - padZ, MESH_PIXELS_PER_CELL, Math.max(1, width), Math.max(1, height));
    }

    /** Maps macro-cell-space {@link Point2D} coordinates to image pixel coordinates for one cached region frame. */
    private record Viewport(double minX, double minZ, double scale, int width, int height) {
        int px(Point2D point) {
            return (int) Math.round((point.x() - minX) * scale);
        }

        int pz(Point2D point) {
            return (int) Math.round((point.z() - minZ) * scale);
        }
    }
}
