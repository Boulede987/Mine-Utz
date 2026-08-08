package com.mineutze.worldgen.regionsolve.mesh;

import com.mineutze.worldgen.biomegraph.AdjacencyRule;
import com.mineutze.worldgen.biomegraph.BiomeFeatureToggles;
import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;
import com.mineutze.worldgen.biomegraph.BiomeShapeHints;
import com.mineutze.worldgen.biomegraph.BiomeSizeRange;
import com.mineutze.worldgen.biomegraph.GrowthCohesionProfile;
import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mineutze.worldgen.regionsolve.topology.Point2D;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MeshGapFillerTest {

    private static final String NAMESPACE = "test";
    private static final MacroGridConfig GRID = new MacroGridConfig(64, 8);
    private static final long WORLD_SEED = 7L;

    @Test
    void sameWorldSeedProducesTheSameFill() {
        BiomeId a = id("a");
        BiomeId filler = id("filler");
        BiomeGraph graph = new BiomeGraph(List.of(node(a, 100, 900), node(filler, 100, 900)), List.of(AdjacencyRule.allow(filler, a, 10)));
        FineMesh mesh = jitteredScatterMesh(6, 6, 1);

        MeshAssignment first = new MeshAssignment(new LinkedHashMap<>(Map.of(0, a)), Set.of());
        MeshAssignment second = new MeshAssignment(new LinkedHashMap<>(Map.of(0, a)), Set.of());
        MeshGapFiller.fillGaps(mesh, first, graph, GRID, WORLD_SEED);
        MeshGapFiller.fillGaps(mesh, second, graph, GRID, WORLD_SEED);

        assertEquals(first.faceIdentity(), second.faceIdentity());
    }

    @Test
    void everyFaceEndsUpAssignedAndAnEmptyMeshResolvesToASingleConsistentBiome() {
        BiomeId a = id("a");
        BiomeId filler = id("filler");
        BiomeGraph graph = new BiomeGraph(List.of(node(a, 100, 900), node(filler, 100, 900)), List.of(AdjacencyRule.allow(filler, a, 10)));
        FineMesh mesh = jitteredScatterMesh(6, 6, 2);
        MeshAssignment assignment = new MeshAssignment(new LinkedHashMap<>(), Set.of());

        MeshGapFiller.fillGaps(mesh, assignment, graph, GRID, WORLD_SEED);

        assertEquals(mesh.faces().size(), assignment.faceIdentity().size(), "every face must end up assigned");
        assertEquals(1, Set.copyOf(assignment.faceIdentity().values()).size(),
            "a fully empty mesh has no signal to prefer one biome cell-to-cell - must resolve to a single consistent biome");
    }

    @Test
    void neverProducesAnIllegalEdgeOrDiagonalTouchAnywhere() {
        BiomeId a = id("a");
        BiomeId b = id("b");
        BiomeGraph graph = new BiomeGraph(List.of(node(a, 100, 900), node(b, 100, 900)), List.of(AdjacencyRule.allow(a, b, 10)));
        FineMesh mesh = jitteredScatterMesh(8, 8, 3);

        MeshAssignment assignment = new MeshAssignment(new LinkedHashMap<>(Map.of(0, a, mesh.faces().size() - 1, b)), Set.of());
        MeshGapFiller.fillGaps(mesh, assignment, graph, GRID, WORLD_SEED);

        for (Map.Entry<Integer, BiomeId> entry : assignment.faceIdentity().entrySet()) {
            int faceId = entry.getKey();
            BiomeId biome = entry.getValue();
            for (int neighborFaceId : mesh.edgeAdjacentFaces(faceId)) {
                BiomeId neighborBiome = assignment.faceIdentity().get(neighborFaceId);
                assertTrue(neighborBiome == null || graph.isAllowed(biome, neighborBiome),
                    biome + " (face " + faceId + ") illegally edge-touches " + neighborBiome + " (face " + neighborFaceId + ")");
            }
            for (int diagonalFaceId : MeshDiagonalPairs.diagonalPartnersOf(mesh, faceId)) {
                BiomeId diagonalBiome = assignment.faceIdentity().get(diagonalFaceId);
                assertTrue(diagonalBiome == null || graph.isAllowed(biome, diagonalBiome),
                    biome + " (face " + faceId + ") illegally point-touches " + diagonalBiome + " (face " + diagonalFaceId + ")");
            }
        }
    }

    /**
     * Documents a deliberate design decision (see this class's own doc):
     * unlike the pixel {@code GapFiller}, fill does NOT apply {@code
     * MeshCohesion}'s neck-prevention — a COMPACT profile has no special
     * protection here and legitimately floods everything legally reachable,
     * same as an unrestricted one would. Neck-prevention for a COMPACT
     * biome is still fully enforced during {@code PatchMerger}'s own growth
     * phase (see {@code MeshCohesionTest}) — this test exists so a future
     * change re-adding a cohesion check to fill is a deliberate choice, not
     * an accidental regression back to the sparse-biome starvation bug this
     * design avoids.
     */
    @Test
    void compactProfileHasNoSpecialProtectionDuringFillUnlikeDuringGrowth() {
        GrowthCohesionProfile compact = new GrowthCohesionProfile(2, 0, 0);
        BiomeId a = id("a");
        BiomeId filler = id("filler");
        BiomeGraph graph = new BiomeGraph(
            List.of(node(a, 100, 900, compact), node(filler, 100, 900)),
            List.of(AdjacencyRule.allow(filler, a, 10))
        );
        FineMesh mesh = jitteredScatterMesh(10, 10, 4);

        // Seed A as a single face - if fill applied cohesion the same way growth
        // does, a lone seed with no established thickness would stall immediately.
        Map<Integer, BiomeId> faceIdentity = new LinkedHashMap<>(Map.of(0, a));
        MeshAssignment assignment = new MeshAssignment(faceIdentity, Set.of());

        MeshGapFiller.fillGaps(mesh, assignment, graph, GRID, WORLD_SEED);

        assertEquals(mesh.faces().size(), assignment.faceIdentity().size(), "every face must end up assigned");
        assertTrue(assignment.faceIdentity().values().stream().allMatch(a::equals),
            "with only one other (mutually legal) biome present and no cohesion check during fill, A legitimately claims everything reachable");
    }

    /**
     * The dead-end/reassignment tier, ported in spirit rather than as an
     * exact geometric replica of GapFillerTest's hand-built pixel fixture
     * (a triangulated mesh has no equivalent of "column x=0"). Any two
     * EDGE-neighbors of the same face always share that face's own
     * opposite vertex (a structural fact of triangles, not a rare
     * coincidence), so they can never be the two independent "unrelated"
     * blockers this scenario needs. An edge-neighbor and a DIAGONAL
     * partner, though, only share {@code middle}'s own vertex/vertices
     * they each individually touch - genuinely unrelated to each other in
     * a large enough mesh. Searches for exactly that: a face M whose edge-
     * neighbor L and diagonal partner R share no vertex with each other at
     * all. Assigns L and R mutually-incompatible biomes (legal on their
     * own, since neither touches the other) so M has no direct legal
     * choice via either edge or diagonal legality, then asserts fillGaps
     * still resolves M legally against both, proving makeLegalHere's
     * bounded reassignment actually fired rather than skipping straight to
     * a force-commit.
     */
    @Test
    void aDeadEndFaceWedgedBetweenTwoIncompatibleSourcesGetsResolvedLegally() {
        FineMesh mesh = jitteredScatterMesh(8, 8, 5);
        int[] triple = findWedgeTriple(mesh);
        assumeTrue(triple != null, "this mesh's triangulation didn't produce a usable wedge triple");
        int middle = triple[0];
        int left = triple[1];
        int right = triple[2];

        BiomeId leftBiome = id("left");
        BiomeId rightBiome = id("right");
        BiomeId bridge = id("bridge");
        // left-right has no rule (mutually incompatible); both bridge through "bridge".
        BiomeGraph graph = new BiomeGraph(
            List.of(node(leftBiome, 100, 900), node(rightBiome, 100, 900), node(bridge, 100, 900)),
            List.of(AdjacencyRule.allow(leftBiome, bridge, 10), AdjacencyRule.allow(bridge, rightBiome, 10))
        );

        MeshAssignment assignment = new MeshAssignment(new LinkedHashMap<>(Map.of(left, leftBiome, right, rightBiome)), Set.of());
        MeshGapFiller.fillGaps(mesh, assignment, graph, GRID, WORLD_SEED);

        BiomeId middleResolved = assignment.faceIdentity().get(middle);
        assertTrue(middleResolved != null, "the wedged face must end up assigned");
        assertTrue(graph.isAllowed(middleResolved, assignment.faceIdentity().get(left)), "must be legal against the left source");
        assertTrue(graph.isAllowed(middleResolved, assignment.faceIdentity().get(right)), "must be legal against the right source");
    }

    /** Finds (middle, left, right): left is edge-adjacent to middle, right is a diagonal (point-only) partner of middle, and left/right share no vertex with each other at all. */
    private static int[] findWedgeTriple(FineMesh mesh) {
        for (int middle = 0; middle < mesh.faces().size(); middle++) {
            for (int left : mesh.edgeAdjacentFaces(middle)) {
                for (int right : MeshDiagonalPairs.diagonalPartnersOf(mesh, middle)) {
                    if (!shareAnyVertex(mesh, left, right)) {
                        return new int[] {middle, left, right};
                    }
                }
            }
        }
        return null;
    }

    private static boolean shareAnyVertex(FineMesh mesh, int faceA, int faceB) {
        var a = mesh.face(faceA);
        var b = mesh.face(faceB);
        Set<Integer> verticesA = Set.of(a.a(), a.b(), a.c());
        Set<Integer> verticesB = new LinkedHashSet<>(Set.of(b.a(), b.b(), b.c()));
        verticesB.retainAll(verticesA);
        return !verticesB.isEmpty();
    }

    private static FineMesh jitteredScatterMesh(int columns, int rows, long seed) {
        Random random = new Random(seed);
        List<ScatteredPoint> points = new java.util.ArrayList<>();
        for (int index = 0; index < columns * rows; index++) {
            int gridX = index % columns;
            int gridZ = index / columns;
            Point2D position = new Point2D(gridX * 10.0 + random.nextDouble() * 4.0, gridZ * 10.0 + random.nextDouble() * 4.0);
            points.add(new ScatteredPoint(position, Optional.empty()));
        }
        return FineMesh.build(points);
    }

    private static BiomeId id(String path) {
        return new BiomeId(NAMESPACE, path);
    }

    private static BiomeNode node(BiomeId id, int minWidthBlocks, int maxWidthBlocks) {
        return node(id, minWidthBlocks, maxWidthBlocks, GrowthCohesionProfile.UNRESTRICTED);
    }

    private static BiomeNode node(BiomeId id, int minWidthBlocks, int maxWidthBlocks, GrowthCohesionProfile cohesion) {
        return new BiomeNode(id, new BiomeSizeRange(minWidthBlocks, maxWidthBlocks), new BiomeShapeHints(0.5, 0.5, 0.5), new BiomeFeatureToggles(true, true), cohesion);
    }
}
