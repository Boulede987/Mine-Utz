package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.NodeData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static com.mineutze.worldgen.plan.ToroidalTestTables.A;
import static com.mineutze.worldgen.plan.ToroidalTestTables.B;
import static com.mineutze.worldgen.plan.ToroidalTestTables.C;
import static com.mineutze.worldgen.plan.ToroidalTestTables.fullyLegalCatalog;
import static com.mineutze.worldgen.plan.ToroidalTestTables.noLegalTriangleCatalog;
import static com.mineutze.worldgen.plan.ToroidalTestTables.withRequirement;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LooseningTest {

    private static NodeData nodeOf(BiomeGraphData catalog, com.mineutze.worldgen.biomegraph.BiomeId biome) {
        return catalog.nodes().stream().filter(node -> node.key().equals(biome.toString())).findFirst().orElseThrow();
    }

    @Test
    void anIllegalPairBecomesLegal() {
        BiomeGraphData catalog = noLegalTriangleCatalog(500, 800);

        LoosenedGraph loosened = Loosening.of(new LawObstacle(new IllegalPairLaw(A, C)), catalog).orElseThrow();

        assertTrue(RawRuleTable.from(loosened.catalog()).isLegal(A, C));
    }

    @Test
    void makingAPairLegalLeavesEveryOtherPairAsItWas() {
        BiomeGraphData catalog = noLegalTriangleCatalog(500, 800);

        LoosenedGraph loosened = Loosening.of(new LawObstacle(new IllegalPairLaw(A, C)), catalog).orElseThrow();

        assertTrue(!RawRuleTable.from(loosened.catalog()).isLegal(B, C));
        assertEquals(catalog.edges().size() + 1, loosened.catalog().edges().size());
    }

    /** User decision 2026-10-31: min -20%, max +25%. */
    @Test
    void aSizeRangeIsWidenedByTheAgreedFactors() {
        BiomeGraphData catalog = fullyLegalCatalog(500, 800, A, B, C);

        LoosenedGraph loosened = Loosening.of(new LawObstacle(new SizeRangeLaw(A, 3, 8)), catalog).orElseThrow();

        assertEquals(400, nodeOf(loosened.catalog(), A).minWidthBlocks());
        assertEquals(1000, nodeOf(loosened.catalog(), A).maxWidthBlocks());
        assertEquals(500, nodeOf(loosened.catalog(), B).minWidthBlocks());
    }

    @Test
    void wideningAMinimumOfOneBlockToZeroIsRefused() {
        BiomeGraphData catalog = fullyLegalCatalog(1, 800, A, B, C);

        assertThrows(IllegalArgumentException.class, () -> Loosening.of(new LawObstacle(new SizeRangeLaw(A, 1, 8)), catalog));
    }

    @Test
    void legalizingInAGraphWithNoEdgeStillWorks() {
        BiomeGraphData noEdges = new BiomeGraphData(fullyLegalCatalog(500, 800, A, B, C).nodes(), List.of());

        LoosenedGraph loosened = Loosening.of(new LawObstacle(new IllegalPairLaw(A, B)), noEdges).orElseThrow();

        assertTrue(RawRuleTable.from(loosened.catalog()).isLegal(A, B));
    }

    @Test
    void aRequiredBorderIsDropped() {
        BiomeGraphData catalog = withRequirement(fullyLegalCatalog(500, 800, A, B, C), A, B, 2);
        ResolvedBorderRequirement requirement = RawRuleTable.from(catalog).requirementOf(A).orElseThrow();

        LoosenedGraph loosened = Loosening.of(new LawObstacle(new RequiredBorderLaw(A, requirement)), catalog).orElseThrow();

        assertEquals("", nodeOf(loosened.catalog(), A).requirement());
    }

    @Test
    void shapeLimitsAndMissingBiomesCannotBeLoosenedInTheGraph() {
        BiomeGraphData catalog = fullyLegalCatalog(500, 800, A, B, C);

        assertEquals(Optional.empty(), Loosening.of(new ShapeLimit(A), catalog));
        assertEquals(Optional.empty(), Loosening.of(new MissingBiome(A), catalog));
    }

    @Test
    void theLabelSaysExactlyWhatChanged() {
        BiomeGraphData catalog = fullyLegalCatalog(500, 800, A, B, C);

        LoosenedGraph loosened = Loosening.of(new LawObstacle(new SizeRangeLaw(A, 3, 8)), catalog).orElseThrow();

        assertTrue(loosened.label().contains("500..800") && loosened.label().contains("400..1000"), loosened.label());
    }

    @Test
    void theOriginalCatalogIsNotChanged() {
        BiomeGraphData catalog = fullyLegalCatalog(500, 800, A, B, C);
        List<NodeData> before = List.copyOf(catalog.nodes());

        Loosening.of(new LawObstacle(new SizeRangeLaw(A, 3, 8)), catalog);

        assertEquals(before, catalog.nodes());
    }
}
