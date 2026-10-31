package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.mineutze.worldgen.plan.ProofFixtures.E;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConflictExplainerTest {

    private static final Duration TIME_LIMIT = Duration.ofSeconds(30);

    private static final BiomeGraphData FIVE_LEGAL = fullyLegalCatalog(500, 700, A, B, C, D, E);

    private static List<LawConflict> explain(BiomeGraphData catalog) {
        return explain(catalog, lawsOf(catalog, SMALL_COARSE));
    }

    private static List<LawConflict> explain(BiomeGraphData catalog, List<GraphLaw> laws) {
        return new ConflictExplainer(countingModelOf(catalog, SMALL_COARSE), TIME_LIMIT).explain(laws).conflicts();
    }

    /** Two border requirements no patch can meet, on different biomes: two conflicts sharing no law. */
    private static List<GraphLaw> twoImpossibleBorders() {
        List<GraphLaw> laws = new ArrayList<>(lawsOf(FIVE_LEGAL, SMALL_COARSE));
        laws.add(impossibleBorder(A, B));
        laws.add(impossibleBorder(D, E));
        return laws;
    }

    /** {@code catalog} with every edge touching {@code isolated} removed. */
    private static BiomeGraphData catalogWithout(BiomeGraphData catalog, BiomeId isolated) {
        return new BiomeGraphData(
            catalog.nodes(), catalog.edges().stream().filter(edge -> !edge.aKey().equals(isolated.toString()) && !edge.bKey().equals(isolated.toString())).toList()
        );
    }

    @Test
    void aSatisfiableGraphIsCompletelyExplainedByNoConflict() {
        ConflictExplainer.Explanation explanation = new ConflictExplainer(countingModelOf(FIVE_LEGAL, SMALL_COARSE), TIME_LIMIT).explain(lawsOf(FIVE_LEGAL, SMALL_COARSE));

        assertTrue(explanation.complete());
    }

    @Test
    void runningOutOfTimeIsReportedAsIncomplete() {
        BiomeGraphData catalog = noLegalTriangleCatalog(500, 800);

        ConflictExplainer.Explanation explanation = new ConflictExplainer(countingModelOf(catalog, SMALL_COARSE), Duration.ZERO).explain(lawsOf(catalog, SMALL_COARSE));

        assertFalse(explanation.complete());
    }

    /** D touches nothing: its conflict is made of D's own laws only, found by the per-biome pass. */
    @Test
    void anIsolatedBiomesConflictConcernsOnlyThatBiome() {
        List<LawConflict> conflicts = explain(catalogWithout(FIVE_LEGAL, D));

        assertEquals(1, conflicts.size(), conflicts.toString());
        assertTrue(conflicts.get(0).laws().stream().allMatch(law -> law.describe().contains(D.toString())), conflicts.toString());
    }

    @Test
    void anIsolatedBiomesPairsAreShownAsOneLine() {
        List<LawConflict> conflicts = explain(catalogWithout(FIVE_LEGAL, D));

        List<String> lines = conflicts.get(0).describeLines();
        assertTrue(lines.stream().anyMatch(line -> line.startsWith(D + " is not a legal neighbour of")), lines.toString());
    }

    @Test
    void aSatisfiableGraphHasNoConflict() {
        assertEquals(List.of(), explain(fullyLegalCatalog(500, 700, A, B, C)));
    }

    @Test
    void namesTheMinimalConflictOfAGraphWithNoLegalCorner() {
        List<LawConflict> conflicts = explain(noLegalTriangleCatalog(500, 800));

        assertEquals(1, conflicts.size());
        assertEquals(Set.of(new IllegalPairLaw(A, C), new IllegalPairLaw(B, C)), new HashSet<>(conflicts.get(0).laws()));
    }

    @Test
    void everyConflictIsAContradictionOnItsOwn() {
        CountingModel model = countingModelOf(FIVE_LEGAL, SMALL_COARSE);

        for (LawConflict conflict : explain(FIVE_LEGAL, twoImpossibleBorders())) {
            assertEquals(CountingStatus.CONTRADICTION, model.check(new LinkedHashSet<>(conflict.laws())), conflict.toString());
        }
    }

    /** Minimal: dropping any one law of a conflict removes the contradiction. */
    @Test
    void everyConflictIsMinimal() {
        CountingModel model = countingModelOf(FIVE_LEGAL, SMALL_COARSE);

        for (LawConflict conflict : explain(FIVE_LEGAL, twoImpossibleBorders())) {
            for (GraphLaw dropped : conflict.laws()) {
                Set<GraphLaw> rest = new LinkedHashSet<>(conflict.laws());
                rest.remove(dropped);
                assertEquals(CountingStatus.SATISFIABLE, model.check(rest), "still contradicting without " + dropped.describe());
            }
        }
    }

    @Test
    void reportsEveryIndependentConflictAtOnce() {
        List<LawConflict> conflicts = explain(FIVE_LEGAL, twoImpossibleBorders());

        assertTrue(conflicts.size() >= 2, conflicts.toString());
    }

    @Test
    void independentConflictsShareNoLaw() {
        List<LawConflict> conflicts = explain(FIVE_LEGAL, twoImpossibleBorders());

        Set<GraphLaw> seen = new HashSet<>();
        for (LawConflict conflict : conflicts) {
            for (GraphLaw law : conflict.laws()) {
                assertTrue(seen.add(law), "law in two conflicts: " + law.describe());
            }
        }
    }

    @Test
    void whatRemainsAfterRemovingEveryConflictIsSatisfiable() {
        List<GraphLaw> rest = twoImpossibleBorders();
        explain(FIVE_LEGAL, twoImpossibleBorders()).forEach(conflict -> rest.removeAll(conflict.laws()));

        assertEquals(CountingStatus.SATISFIABLE, countingModelOf(FIVE_LEGAL, SMALL_COARSE).check(new LinkedHashSet<>(rest)));
    }
}
