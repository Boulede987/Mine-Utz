package com.mineutze.worldgen.export.hex;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.ModBiomeGraph;
import com.mineutze.worldgen.regionsolve.EdgeDisplacementRasterizer;
import com.mineutze.worldgen.regionsolve.MacroCellPos;
import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mineutze.worldgen.regionsolve.topology.FaceSizeFeasibility;
import com.mineutze.worldgen.regionsolve.topology.HexMeshBuilder;
import com.mineutze.worldgen.regionsolve.topology.NodeEdge;
import com.mineutze.worldgen.regionsolve.topology.TriangleAdjacency;
import com.mineutze.worldgen.regionsolve.topology.TriangleIdentitySizeAssignment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Throwaway diagnostic: counts genuine single-cell islands (a cell whose all 4 orthogonal neighbors are a different biome) in the real rasterized output, to rule out a PNG-viewer resize artifact vs. a real rasterizer bug. */
public final class HexRasterDiagnostics {

    private static final double SIZE_BLOCKS = 100;
    private static final int MESH_BOX_MAX = 2000;
    private static final int REAL_MIN = 400;
    private static final int REAL_MAX = 1600;
    private static final long WORLD_SEED = 2026L;
    private static final MacroGridConfig GRID_CONFIG = new MacroGridConfig(4, 512);

    public static void main(String[] args) {
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(SIZE_BLOCKS, 0, 0, MESH_BOX_MAX, MESH_BOX_MAX);
        TriangleAdjacency.Adjacency adjacency = TriangleAdjacency.of(mesh.faces());
        Set<Integer> freeFaceIndices = new HashSet<>();
        for (int faceIndex = 0; faceIndex < mesh.faces().size(); faceIndex++) {
            if (!adjacency.hullTouchingFaceIndices().contains(faceIndex)) {
                freeFaceIndices.add(faceIndex);
            }
        }

        BiomeGraph graph = ModBiomeGraph.DEFAULT;
        Optional<Map<Integer, TriangleIdentitySizeAssignment.Assignment>> solved =
            TriangleIdentitySizeAssignment.solve(adjacency, freeFaceIndices, Map.of(), graph, WORLD_SEED);
        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments = solved.orElseThrow();
        Map<NodeEdge, Double> biasByEdge = FaceSizeFeasibility.biasBlocksByEdge(mesh, adjacency, assignments);

        List<MacroCellPos> cells = allCellsIn(GRID_CONFIG, REAL_MIN, REAL_MIN, REAL_MAX, REAL_MAX);
        boolean disableBias = args.length > 0 && args[0].equals("nobias");
        Map<MacroCellPos, BiomeId> result = EdgeDisplacementRasterizer.rasterize(cells, assignments, mesh, disableBias ? Map.of() : biasByEdge, Map.of(), GRID_CONFIG, WORLD_SEED);
        System.out.println("bias " + (disableBias ? "DISABLED (wiggle-only)" : "enabled"));

        int islandCount = 0;
        List<MacroCellPos> examples = new ArrayList<>();
        for (MacroCellPos cell : cells) {
            BiomeId biome = result.get(cell);
            boolean allNeighborsDiffer = true;
            for (MacroCellPos neighbor : cell.orthogonalNeighbors()) {
                BiomeId neighborBiome = result.get(neighbor);
                if (neighborBiome == null || neighborBiome.equals(biome)) {
                    allNeighborsDiffer = false;
                    break;
                }
            }
            if (allNeighborsDiffer) {
                islandCount++;
                if (examples.size() < 10) {
                    examples.add(cell);
                }
            }
        }

        System.out.println("Total cells checked: " + cells.size());
        System.out.println("Single-cell islands (all 4 orthogonal neighbors differ): " + islandCount);
        System.out.println("Examples: " + examples);
        for (MacroCellPos example : examples) {
            System.out.println(example + " = " + result.get(example) + ", neighbors: "
                + example.orthogonalNeighbors().stream().map(n -> n + "=" + result.get(n)).toList());
        }
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
}
