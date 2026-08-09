package com.mineutze.worldgen.regionsolve.mesh;

import com.mineutze.worldgen.biomegraph.AdjacencyRule;
import com.mineutze.worldgen.biomegraph.BiomeFeatureToggles;
import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;
import com.mineutze.worldgen.biomegraph.BiomeShapeHints;
import com.mineutze.worldgen.biomegraph.BiomeSizeRange;
import com.mineutze.worldgen.biomegraph.GrowthCohesionProfile;
import com.mineutze.worldgen.regionsolve.MacroCellPos;
import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mineutze.worldgen.regionsolve.RegionPos;
import com.mineutze.worldgen.regionsolve.RegionSolveInput;
import com.mineutze.worldgen.regionsolve.topology.Point2D;
import com.mineutze.worldgen.regionsolve.topology.SeedGraph;
import com.mineutze.worldgen.regionsolve.topology.SeedGraphBuilder;
import com.mineutze.worldgen.regionsolve.topology.SeedGraphNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FineMeshPointScatterTest {

    private static final String NAMESPACE = "test";
    // Below MacroGridConfig's own warp threshold (region grid domain warp - see that
    // class's doc) so this file's fixtures see an exact, unwarped square - none of them
    // are testing grid/warp semantics, just fine-mesh scatter mechanics.
    private static final MacroGridConfig SMALL_REGION = new MacroGridConfig(64, 7);
    private static final int WIDE_COARSE_SEED_SPACING_BLOCKS = 1000; // exactly one coarse seed per region
    private static final int FINE_SPACING_BLOCKS = 128; // spacingCells = 2 against SMALL_REGION

    @Test
    void sameInputProducesTheSameScatterEveryTime() {
        RegionSolveInput input = inputWith(Map.of());
        SeedGraph seedGraph = SeedGraphBuilder.build(input, WIDE_COARSE_SEED_SPACING_BLOCKS);
        Map<Integer, BiomeId> assignments = assignAllFreeNodes(seedGraph, id("plains"));

        List<ScatteredPoint> first = FineMeshPointScatter.scatter(input, seedGraph, assignments, FINE_SPACING_BLOCKS);
        List<ScatteredPoint> second = FineMeshPointScatter.scatter(input, seedGraph, assignments, FINE_SPACING_BLOCKS);

        assertEquals(first, second);
    }

    @Test
    void everyScatteredPositionIsUnique() {
        RegionSolveInput input = inputWith(Map.of(new MacroCellPos(-1, 0), id("badlands")));
        SeedGraph seedGraph = SeedGraphBuilder.build(input, WIDE_COARSE_SEED_SPACING_BLOCKS);
        Map<Integer, BiomeId> assignments = assignAllFreeNodes(seedGraph, id("plains"));

        List<ScatteredPoint> scatter = FineMeshPointScatter.scatter(input, seedGraph, assignments, FINE_SPACING_BLOCKS);

        Set<Point2D> positions = new HashSet<>();
        scatter.forEach(point -> positions.add(point.position()));
        assertEquals(scatter.size(), positions.size(), "every scattered point must be at a distinct position");
    }

    @Test
    void germPointsKeepTheCoarseGraphsIdentitiesUncorrupted() {
        BiomeId badlands = id("badlands");
        BiomeId plains = id("plains");
        MacroCellPos fixedCell = new MacroCellPos(-1, 0);
        RegionSolveInput input = inputWith(Map.of(fixedCell, badlands));
        SeedGraph seedGraph = SeedGraphBuilder.build(input, WIDE_COARSE_SEED_SPACING_BLOCKS);
        Map<Integer, BiomeId> assignments = assignAllFreeNodes(seedGraph, plains);

        List<ScatteredPoint> scatter = FineMeshPointScatter.scatter(input, seedGraph, assignments, FINE_SPACING_BLOCKS);

        ScatteredPoint fixedPoint = scatter.stream()
            .filter(point -> point.position().equals(new Point2D(fixedCell.x(), fixedCell.z())))
            .findFirst().orElseThrow(() -> new AssertionError("fixed boundary cell never made it into the scatter"));
        assertEquals(java.util.Optional.of(badlands), fixedPoint.germColor());

        SeedGraphNode freeNode = seedGraph.nodes().stream().filter(node -> !node.fixed()).findFirst().orElseThrow();
        Point2D freeSeedPosition = new Point2D(freeNode.position().x(), freeNode.position().z());
        ScatteredPoint freePoint = scatter.stream()
            .filter(point -> point.position().equals(freeSeedPosition))
            .findFirst().orElseThrow(() -> new AssertionError("coarse free seed never made it into the scatter"));
        assertEquals(java.util.Optional.of(plains), freePoint.germColor());
    }

    @Test
    void boundaryRingMakesTheScatterStrictlyWiderThanEveryMacroCellInTheRegion() {
        RegionSolveInput input = inputWith(Map.of());
        SeedGraph seedGraph = SeedGraphBuilder.build(input, WIDE_COARSE_SEED_SPACING_BLOCKS);
        Map<Integer, BiomeId> assignments = assignAllFreeNodes(seedGraph, id("plains"));

        List<ScatteredPoint> scatter = FineMeshPointScatter.scatter(input, seedGraph, assignments, FINE_SPACING_BLOCKS);

        double minScatterX = scatter.stream().mapToDouble(point -> point.position().x()).min().orElseThrow();
        double maxScatterX = scatter.stream().mapToDouble(point -> point.position().x()).max().orElseThrow();
        double minScatterZ = scatter.stream().mapToDouble(point -> point.position().z()).min().orElseThrow();
        double maxScatterZ = scatter.stream().mapToDouble(point -> point.position().z()).max().orElseThrow();

        int regionSide = SMALL_REGION.regionSizeCellsPerSide();
        for (MacroCellPos cell : SMALL_REGION.cellsInRegion(new RegionPos(0, 0), 42L)) {
            assertTrue(cell.x() > minScatterX && cell.x() < maxScatterX, "cell " + cell + " not strictly inside scatter x-bounds");
            assertTrue(cell.z() > minScatterZ && cell.z() < maxScatterZ, "cell " + cell + " not strictly inside scatter z-bounds");
        }
        assertTrue(regionSide > 0);
    }

    private static Map<Integer, BiomeId> assignAllFreeNodes(SeedGraph seedGraph, BiomeId biome) {
        Map<Integer, BiomeId> assignments = new HashMap<>();
        for (SeedGraphNode node : seedGraph.nodes()) {
            if (!node.fixed()) {
                assignments.put(node.id(), biome);
            }
        }
        return assignments;
    }

    private static RegionSolveInput inputWith(Map<MacroCellPos, BiomeId> fixedBoundaryCells) {
        BiomeGraph graph = graphOf(List.of(node(id("plains")), node(id("badlands"))), List.of(
            AdjacencyRule.allow(id("plains"), id("badlands"), 10)
        ));
        return new RegionSolveInput(graph, SMALL_REGION, new RegionPos(0, 0), fixedBoundaryCells, 42L);
    }

    private static BiomeGraph graphOf(List<BiomeNode> nodes, List<AdjacencyRule> rules) {
        return new BiomeGraph(nodes, rules);
    }

    private static BiomeId id(String path) {
        return new BiomeId(NAMESPACE, path);
    }

    private static BiomeNode node(BiomeId id) {
        return new BiomeNode(
            id,
            new BiomeSizeRange(100, 200),
            new BiomeShapeHints(0.5, 0.5, 0.5),
            new BiomeFeatureToggles(true, true),
            GrowthCohesionProfile.UNRESTRICTED
        );
    }
}
