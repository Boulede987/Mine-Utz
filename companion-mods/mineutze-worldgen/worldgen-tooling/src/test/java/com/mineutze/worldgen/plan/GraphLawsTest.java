package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.mineutze.worldgen.plan.ProofFixtures.SMALL_COARSE;
import static com.mineutze.worldgen.plan.ProofFixtures.lawsOf;
import static com.mineutze.worldgen.plan.ToroidalTestTables.A;
import static com.mineutze.worldgen.plan.ToroidalTestTables.B;
import static com.mineutze.worldgen.plan.ToroidalTestTables.C;
import static com.mineutze.worldgen.plan.ToroidalTestTables.fullyLegalCatalog;
import static com.mineutze.worldgen.plan.ToroidalTestTables.noLegalTriangleCatalog;
import static com.mineutze.worldgen.plan.ToroidalTestTables.withRequirement;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphLawsTest {

    @Test
    void everyBiomeHasASizeRangeLaw() {
        List<GraphLaw> laws = lawsOf(fullyLegalCatalog(500, 700, A, B, C), SMALL_COARSE);

        assertEquals(3, laws.stream().filter(law -> law instanceof SizeRangeLaw).count());
    }

    @Test
    void everyIllegalUnorderedPairIsOneLaw() {
        List<GraphLaw> laws = lawsOf(noLegalTriangleCatalog(500, 800), SMALL_COARSE);

        assertEquals(2, laws.stream().filter(law -> law instanceof IllegalPairLaw).count());
    }

    @Test
    void aFullyLegalCatalogHasNoIllegalPairLaw() {
        List<GraphLaw> laws = lawsOf(fullyLegalCatalog(500, 700, A, B, C), SMALL_COARSE);

        assertTrue(laws.stream().noneMatch(law -> law instanceof IllegalPairLaw));
    }

    @Test
    void aBiomeWithARequirementHasARequiredBorderLaw() {
        BiomeGraphData catalog = withRequirement(fullyLegalCatalog(500, 700, A, B, C), A, B, 2);

        List<GraphLaw> laws = lawsOf(catalog, SMALL_COARSE);

        assertEquals(1, laws.stream().filter(law -> law instanceof RequiredBorderLaw required && required.biome().equals(A)).count());
    }

    @Test
    void lawsDescribeThemselvesWithTheBiomesTheyConcern() {
        List<GraphLaw> laws = lawsOf(noLegalTriangleCatalog(500, 800), SMALL_COARSE);

        GraphLaw illegal = laws.stream().filter(law -> law instanceof IllegalPairLaw).findFirst().orElseThrow();
        assertTrue(illegal.describe().contains(C.toString()), illegal.describe());
    }
}
