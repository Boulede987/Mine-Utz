package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static com.mineutze.worldgen.plan.ProofFixtures.SMALL_COARSE;
import static com.mineutze.worldgen.plan.ProofFixtures.countingModelOf;
import static com.mineutze.worldgen.plan.ProofFixtures.impossibleBorder;
import static com.mineutze.worldgen.plan.ProofFixtures.lawsOf;
import static com.mineutze.worldgen.plan.ToroidalTestTables.A;
import static com.mineutze.worldgen.plan.ToroidalTestTables.B;
import static com.mineutze.worldgen.plan.ToroidalTestTables.C;
import static com.mineutze.worldgen.plan.ToroidalTestTables.D;
import static com.mineutze.worldgen.plan.ToroidalTestTables.fullyLegalCatalog;
import static com.mineutze.worldgen.plan.ToroidalTestTables.noLegalTriangleCatalog;
import static com.mineutze.worldgen.plan.ToroidalTestTables.withRequirement;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CountingModelTest {

    private static CountingStatus checkAllLaws(BiomeGraphData catalog) {
        return countingModelOf(catalog, SMALL_COARSE).check(new LinkedHashSet<>(lawsOf(catalog, SMALL_COARSE)));
    }

    @Test
    void aGraphWithNoLegalCornerIsAContradiction() {
        assertEquals(CountingStatus.CONTRADICTION, checkAllLaws(noLegalTriangleCatalog(500, 800)));
    }

    @Test
    void droppingTheIllegalPairsRemovesTheContradiction() {
        BiomeGraphData catalog = noLegalTriangleCatalog(500, 800);
        Set<GraphLaw> withoutIllegalPairs = new LinkedHashSet<>(lawsOf(catalog, SMALL_COARSE).stream().filter(law -> !(law instanceof IllegalPairLaw)).toList());

        assertEquals(CountingStatus.SATISFIABLE, countingModelOf(catalog, SMALL_COARSE).check(withoutIllegalPairs));
    }

    /** Soundness: a catalog the real solver solves on this grid must never be called a contradiction. */
    @Test
    void aCatalogTheSolverSolvesIsSatisfiable() {
        assertEquals(CountingStatus.SATISFIABLE, checkAllLaws(fullyLegalCatalog(500, 700, A, B, C)));
    }

    @Test
    void aSolvableCatalogWithARequirementIsSatisfiable() {
        assertEquals(CountingStatus.SATISFIABLE, checkAllLaws(withRequirement(fullyLegalCatalog(500, 700, A, B, C, D), A, B, 2)));
    }

    /** Review finding 2026-10-31: one biome covering the whole torus has no border and no corner - not a contradiction. */
    @Test
    void aSingleBiomeCoveringTheWholeTorusIsSatisfiable() {
        assertEquals(CountingStatus.SATISFIABLE, checkAllLaws(fullyLegalCatalog(300, 4000, A)));
    }

    /** A requirement no cluster can meet: more shared edges than any cluster of A can have as perimeter. */
    @Test
    void aRequirementBeyondEveryPossiblePerimeterIsAContradiction() {
        BiomeGraphData catalog = fullyLegalCatalog(500, 700, A, B, C);
        Set<GraphLaw> laws = new LinkedHashSet<>(lawsOf(catalog, SMALL_COARSE));
        laws.add(impossibleBorder(A, B));

        assertEquals(CountingStatus.CONTRADICTION, countingModelOf(catalog, SMALL_COARSE).check(laws));
    }
}
