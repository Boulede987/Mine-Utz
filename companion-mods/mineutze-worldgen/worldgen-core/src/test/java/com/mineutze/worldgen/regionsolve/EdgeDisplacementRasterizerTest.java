package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.ModBiomeGraph;
import com.mineutze.worldgen.regionsolve.topology.HexMeshBuilder;
import com.mineutze.worldgen.regionsolve.topology.Point2D;
import com.mineutze.worldgen.regionsolve.topology.TriangleAdjacency;
import com.mineutze.worldgen.regionsolve.topology.TriangleIdentitySizeAssignment;
import com.mineutze.worldgen.regionsolve.topology.WeightedVertexPlacement;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EdgeDisplacementRasterizerTest {

    private static final double SIZE = 100;
    private static final int MESH_BOX_MAX = 1200;
    private static final int REAL_MIN = 400;
    private static final int REAL_MAX = 800;
    private static final MacroGridConfig GRID_CONFIG = new MacroGridConfig(4, 512);

    /**
     * The whole point of the hex-mesh pivot, checked end to end against the real
     * production graph: a real CSP solve, fed through {@link WeightedVertexPlacement} and
     * this rasterizer, produces full coverage AND zero illegal Moore-adjacencies with NO
     * live legality check anywhere in this class - legality was already fully decided at
     * CSP time. Only the mesh's own well-interior cells are checked (see class-level
     * comment on the margin) so a hull-touching face's incompleteness is never in scope.
     */
    @Test
    void producesFullCoverageAndZeroIllegalAdjacenciesOnARealMesh() {
        long worldSeed = 99L;
        Map<MacroCellPos, BiomeId> result = solveAndRasterize(worldSeed);
        List<MacroCellPos> cells = allCellsIn(GRID_CONFIG, REAL_MIN, REAL_MIN, REAL_MAX, REAL_MAX);

        assertEquals(cells.size(), result.size());
        for (MacroCellPos cell : cells) {
            assertNotNull(result.get(cell), "cell " + cell + " missing from the rasterized result");
        }

        for (MacroCellPos cell : cells) {
            BiomeId biome = result.get(cell);
            for (MacroCellPos neighbor : cell.mooreNeighbors()) {
                BiomeId neighborBiome = result.get(neighbor);
                if (neighborBiome == null || biome.equals(neighborBiome)) {
                    continue; // outside the tested interior box, or same patch's own interior
                }
                assertTrue(
                    ModBiomeGraph.DEFAULT.isAllowed(biome, neighborBiome),
                    "illegal adjacency between " + cell + " (" + biome + ") and " + neighbor + " (" + neighborBiome + ")"
                );
            }
        }
    }

    @Test
    void sameWorldSeedProducesTheSameRaster() {
        Map<MacroCellPos, BiomeId> first = solveAndRasterize(7L);
        Map<MacroCellPos, BiomeId> second = solveAndRasterize(7L);
        assertEquals(first, second);
    }

    /** The safety net itself: a cell no face's polygon (and no fixedBoundaryCells entry) could ever cover must fail loud, not silently disappear. */
    @Test
    void aCellNoFaceCouldEverCoverFailsLoud() {
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(SIZE, 0, 0, 500, 500);
        TriangleAdjacency.Adjacency adjacency = TriangleAdjacency.of(mesh.faces());
        Set<Integer> freeFaceIndices = nonHullTouchingFaceIndices(mesh, adjacency);

        long worldSeed = 3L;
        Optional<Map<Integer, TriangleIdentitySizeAssignment.Assignment>> solved =
            TriangleIdentitySizeAssignment.solve(adjacency, freeFaceIndices, Map.of(), ModBiomeGraph.DEFAULT, worldSeed);
        assertTrue(solved.isPresent());
        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments = solved.get();
        Map<Integer, Point2D> displacedVertices = WeightedVertexPlacement.displacedVertexPositions(mesh, adjacency, assignments, SIZE, 3 * GRID_CONFIG.macroCellSizeBlocks());

        List<MacroCellPos> cellsWithAnImpossibleOne = new ArrayList<>(allCellsIn(GRID_CONFIG, 150, 150, 350, 350));
        cellsWithAnImpossibleOne.add(new MacroCellPos(1_000_000, 1_000_000)); // nowhere near this mesh

        assertThrows(
            IllegalStateException.class,
            () -> EdgeDisplacementRasterizer.rasterize(cellsWithAnImpossibleOne, assignments, mesh, adjacency, displacedVertices, Map.of(), Map.of(), GRID_CONFIG, worldSeed)
        );
    }

    private static Map<MacroCellPos, BiomeId> solveAndRasterize(long worldSeed) {
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(SIZE, 0, 0, MESH_BOX_MAX, MESH_BOX_MAX);
        TriangleAdjacency.Adjacency adjacency = TriangleAdjacency.of(mesh.faces());
        Set<Integer> freeFaceIndices = nonHullTouchingFaceIndices(mesh, adjacency);

        BiomeGraph graph = ModBiomeGraph.DEFAULT;
        Optional<Map<Integer, TriangleIdentitySizeAssignment.Assignment>> solved =
            TriangleIdentitySizeAssignment.solve(adjacency, freeFaceIndices, Map.of(), graph, worldSeed);
        assertTrue(solved.isPresent(), "expected a real hex mesh at this scale to be solvable against ModBiomeGraph.DEFAULT");
        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments = solved.get();

        Map<Integer, Point2D> displacedVertices = WeightedVertexPlacement.displacedVertexPositions(mesh, adjacency, assignments, SIZE, 3 * GRID_CONFIG.macroCellSizeBlocks());
        List<MacroCellPos> cells = allCellsIn(GRID_CONFIG, REAL_MIN, REAL_MIN, REAL_MAX, REAL_MAX);
        return EdgeDisplacementRasterizer.rasterize(cells, assignments, mesh, adjacency, displacedVertices, Map.of(), Map.of(), GRID_CONFIG, worldSeed);
    }

    private static Set<Integer> nonHullTouchingFaceIndices(HexMeshBuilder.HexMesh mesh, TriangleAdjacency.Adjacency adjacency) {
        Set<Integer> freeFaceIndices = new HashSet<>();
        for (int faceIndex = 0; faceIndex < mesh.faces().size(); faceIndex++) {
            if (!adjacency.hullTouchingFaceIndices().contains(faceIndex)) {
                freeFaceIndices.add(faceIndex);
            }
        }
        return freeFaceIndices;
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
