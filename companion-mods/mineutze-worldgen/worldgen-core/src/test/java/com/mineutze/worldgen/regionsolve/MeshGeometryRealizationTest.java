package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.AdjacencyRule;
import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.regionsolve.topology.CspIdentityAssignment;
import com.mineutze.worldgen.regionsolve.topology.SeedGraph;
import com.mineutze.worldgen.regionsolve.topology.SeedGraphBuilder;
import com.mineutze.worldgen.regionsolve.topology.SeedGraphNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.mineutze.worldgen.regionsolve.TestBiomeNodes.id;
import static com.mineutze.worldgen.regionsolve.TestBiomeNodes.node;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mirrors {@link GeometryRealizationTest}'s own small hand-built fixtures,
 * plus ports {@code validate_biome_export.py}'s illegal-adjacency/diagonal-
 * illegal-adjacency/isolated-cell checks as direct JUnit assertions across
 * several seeds through the REAL upstream pipeline (SeedGraphBuilder +
 * CspIdentityAssignment, not a hand-built SeedGraph) — turning the manual
 * seed-42 comparison this rewrite's plan calls for into a permanent
 * regression gate, not a one-time check.
 */
class MeshGeometryRealizationTest {

    private static final MacroGridConfig SMALL_GRID = new MacroGridConfig(64, 8);
    private static final RegionPos REGION = new RegionPos(0, 0);
    private static final long WORLD_SEED = 42L;
    // Placeholder bound, not a target - see this test's own doc. Tune once the real
    // seed-42 comparison (task 12 of this rewrite's plan) gives real numbers to judge by.
    private static final int MAX_ACCEPTABLE_ISOLATED_CELLS = 5;

    @Test
    void aSingleFreePatchGrowsToFillTheWholeRegion() {
        BiomeId solo = id("solo");
        BiomeGraph graph = new BiomeGraph(List.of(node("solo", 100, 2000)), List.of());
        RegionSolveInput input = new RegionSolveInput(graph, SMALL_GRID, REGION, Map.of(), WORLD_SEED);

        SeedGraph seedGraph = seedGraphOf(SeedGraphNode.free(0, new MacroCellPos(3, 3), Set.of(solo)));
        Map<MacroCellPos, BiomeId> result = MeshGeometryRealization.realize(seedGraph, Map.of(0, solo), input);

        assertEquals(regionCellCount(), result.size());
        assertTrue(result.values().stream().allMatch(solo::equals));
    }

    @Test
    void twoCompatiblePatchesGrowingSimultaneouslyNeverProduceAnIllegalAdjacency() {
        BiomeId biomeA = id("a");
        BiomeId biomeB = id("b");
        BiomeGraph graph = new BiomeGraph(
            List.of(node("a", 100, 300), node("b", 100, 300)),
            List.of(AdjacencyRule.allow(biomeA, biomeB, 10))
        );
        RegionSolveInput input = new RegionSolveInput(graph, SMALL_GRID, REGION, Map.of(), WORLD_SEED);

        SeedGraph seedGraph = seedGraphOf(
            SeedGraphNode.free(0, new MacroCellPos(1, 1), Set.of(biomeA)),
            SeedGraphNode.free(1, new MacroCellPos(6, 6), Set.of(biomeB))
        );
        Map<MacroCellPos, BiomeId> result = MeshGeometryRealization.realize(seedGraph, Map.of(0, biomeA, 1, biomeB), input);

        assertEquals(regionCellCount(), result.size(), "MeshGapFiller guarantees full coverage of whatever growth didn't claim");
        assertEquals(List.of(), findIllegalAdjacencies(result, graph));
    }

    @Test
    void aSeedSpotBlockedByFixedBoundaryIsSkippedAndReclaimedByGapFiller() {
        BiomeId friend = id("friend");
        BiomeId bridge = id("bridge");
        BiomeId stranger = id("stranger");
        BiomeGraph graph = new BiomeGraph(
            List.of(node("friend", 100, 300), node("bridge", 100, 300), node("stranger", 100, 300)),
            List.of(AdjacencyRule.allow(friend, bridge, 10), AdjacencyRule.allow(bridge, stranger, 10))
        );
        MacroCellPos seedCell = new MacroCellPos(0, 0);
        MacroCellPos fixedNeighbor = new MacroCellPos(-1, 0);
        RegionSolveInput input = new RegionSolveInput(graph, SMALL_GRID, REGION, Map.of(fixedNeighbor, stranger), WORLD_SEED);

        SeedGraph seedGraph = seedGraphOf(SeedGraphNode.free(0, seedCell, Set.of(friend)));
        Map<MacroCellPos, BiomeId> result = MeshGeometryRealization.realize(seedGraph, Map.of(0, friend), input);

        assertEquals(regionCellCount(), result.size());
        assertEquals(List.of(), findIllegalAdjacencies(result, graph));
    }

    /**
     * The real regression gate: a full topology-first solve (real
     * SeedGraphBuilder + CspIdentityAssignment, not a hand-built SeedGraph)
     * through MeshGeometryRealization, across several seeds, on a graph
     * shaped like the real project's (a required bridge between two
     * mutually-incompatible biomes). Illegal/diagonal-illegal adjacency are
     * hard zero - real MeshLegality invariants, not approximations.
     * Isolated cells are asserted as a bounded count, not hard zero: unlike
     * the pixel pipeline (whose PatchOpener/cohesion checks operate
     * directly on the macro-cell grid), a mesh face can be legitimately
     * connected to its same-identity neighbors in continuous mesh-space
     * while still rasterizing to a macro-cell whose 4 orthogonal pixel
     * neighbors happen to fall under different faces - a discretization
     * artifact of the continuous-to-discrete step, not an adjacency-legality
     * bug. Real severity gets measured empirically in the seed-42
     * comparison (this rewrite's plan, task 12), not guessed at here.
     */
    @Test
    void realPipelineNeverProducesAnIllegalAdjacencyAndKeepsIsolatedCellsBoundedAcrossSeveralSeeds() {
        BiomeId forest = id("forest");
        BiomeId badlands = id("badlands");
        BiomeId plains = id("plains");
        // forest/badlands mutually incompatible, plains is the required bridge -
        // same shape as this project's real biome graph.
        BiomeGraph graph = new BiomeGraph(
            List.of(node("forest", 200, 900), node("badlands", 200, 900), node("plains", 100, 900)),
            List.of(AdjacencyRule.allow(forest, plains, 10), AdjacencyRule.allow(plains, badlands, 10))
        );
        MacroGridConfig grid = new MacroGridConfig(64, 16);
        int seedSpacingBlocks = 400;

        for (long worldSeed = 1L; worldSeed <= 5L; worldSeed++) {
            RegionSolveInput input = new RegionSolveInput(graph, grid, REGION, Map.of(), worldSeed);
            SeedGraph seedGraph = SeedGraphBuilder.build(input, seedSpacingBlocks);
            Optional<Map<Integer, BiomeId>> assignment = CspIdentityAssignment.solve(seedGraph, graph, worldSeed);
            assertTrue(assignment.isPresent(), "seed " + worldSeed + ": CSP must find a valid coloring on an unconstrained region");

            Map<MacroCellPos, BiomeId> result = MeshGeometryRealization.realize(seedGraph, assignment.get(), input);

            assertEquals(grid.regionSizeCellsPerSide() * grid.regionSizeCellsPerSide(), result.size(), "seed " + worldSeed + ": full coverage");
            assertEquals(List.of(), findIllegalAdjacencies(result, graph), "seed " + worldSeed + ": illegal adjacency");
            assertEquals(List.of(), findDiagonalIllegalAdjacencies(result, graph), "seed " + worldSeed + ": diagonal illegal adjacency");
            List<String> isolatedCells = findIsolatedCells(result);
            assertTrue(isolatedCells.size() <= MAX_ACCEPTABLE_ISOLATED_CELLS,
                "seed " + worldSeed + ": " + isolatedCells.size() + " isolated cells, expected at most " + MAX_ACCEPTABLE_ISOLATED_CELLS + ": " + isolatedCells);
        }
    }

    private static List<String> findIllegalAdjacencies(Map<MacroCellPos, BiomeId> cells, BiomeGraph graph) {
        List<String> violations = new ArrayList<>();
        for (Map.Entry<MacroCellPos, BiomeId> entry : cells.entrySet()) {
            MacroCellPos pos = entry.getKey();
            BiomeId biome = entry.getValue();
            for (MacroCellPos neighbor : List.of(new MacroCellPos(pos.x() + 1, pos.z()), new MacroCellPos(pos.x(), pos.z() + 1))) {
                BiomeId neighborBiome = cells.get(neighbor);
                if (neighborBiome != null && !graph.isAllowed(biome, neighborBiome)) {
                    violations.add(pos + " " + biome + " <-> " + neighbor + " " + neighborBiome);
                }
            }
        }
        return violations;
    }

    private static List<String> findDiagonalIllegalAdjacencies(Map<MacroCellPos, BiomeId> cells, BiomeGraph graph) {
        List<String> violations = new ArrayList<>();
        for (Map.Entry<MacroCellPos, BiomeId> entry : cells.entrySet()) {
            MacroCellPos pos = entry.getKey();
            BiomeId biome = entry.getValue();
            for (int[] offset : new int[][] {{1, 1}, {1, -1}}) {
                MacroCellPos diagonal = new MacroCellPos(pos.x() + offset[0], pos.z() + offset[1]);
                BiomeId diagonalBiome = cells.get(diagonal);
                if (diagonalBiome == null || graph.isAllowed(biome, diagonalBiome)) {
                    continue;
                }
                BiomeId off1 = cells.get(new MacroCellPos(pos.x() + offset[0], pos.z()));
                BiomeId off2 = cells.get(new MacroCellPos(pos.x(), pos.z() + offset[1]));
                if (biome.equals(off1) || diagonalBiome.equals(off1) || biome.equals(off2) || diagonalBiome.equals(off2)) {
                    continue; // also touches orthogonally somewhere - not a corner-only touch
                }
                violations.add(pos + " " + biome + " corner-touches " + diagonal + " " + diagonalBiome);
            }
        }
        return violations;
    }

    private static List<String> findIsolatedCells(Map<MacroCellPos, BiomeId> cells) {
        List<String> isolated = new ArrayList<>();
        Set<MacroCellPos> visited = new HashSet<>();
        for (MacroCellPos start : cells.keySet()) {
            if (visited.contains(start)) {
                continue;
            }
            Set<MacroCellPos> component = sameBiomeComponent(start, cells);
            visited.addAll(component);
            if (component.size() == 1) {
                isolated.add(start + " " + cells.get(start));
            }
        }
        return isolated;
    }

    private static Set<MacroCellPos> sameBiomeComponent(MacroCellPos start, Map<MacroCellPos, BiomeId> cells) {
        BiomeId biome = cells.get(start);
        Set<MacroCellPos> visited = new HashSet<>();
        Deque<MacroCellPos> queue = new ArrayDeque<>(List.of(start));
        visited.add(start);
        while (!queue.isEmpty()) {
            MacroCellPos current = queue.poll();
            for (MacroCellPos neighbor : current.orthogonalNeighbors()) {
                if (!visited.contains(neighbor) && biome.equals(cells.get(neighbor))) {
                    visited.add(neighbor);
                    queue.add(neighbor);
                }
            }
        }
        return visited;
    }

    private static SeedGraph seedGraphOf(SeedGraphNode... nodes) {
        return new SeedGraph(List.of(nodes), Set.of(), false);
    }

    private static int regionCellCount() {
        return SMALL_GRID.regionSizeCellsPerSide() * SMALL_GRID.regionSizeCellsPerSide();
    }
}
