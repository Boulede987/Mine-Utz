package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeId;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.mineutze.worldgen.plan.ToroidalTestTables.A;
import static com.mineutze.worldgen.plan.ToroidalTestTables.B;
import static com.mineutze.worldgen.plan.ToroidalTestTables.C;
import static com.mineutze.worldgen.plan.ToroidalTestTables.fullyLegalTableRequiring;
import static com.mineutze.worldgen.plan.ToroidalTestTables.fullyLegalTableWithWidths;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ClusterFactsTest {

    private static final ToroidalGrid GRID = new ToroidalGrid(8, 8);

    private static ClusterSizeModel model(RawRuleTable table) {
        return new ClusterSizeModel(table, 300, new ShapeRule(ShapeRule.NO_PERIMETER_LIMIT, Integer.MAX_VALUE, Integer.MAX_VALUE, 2));
    }

    /** Left half A, right half B: two bands wrapping the torus vertically. */
    private static Map<ToroidalPos, BiomeId> twoBands() {
        Map<ToroidalPos, BiomeId> bands = new LinkedHashMap<>();
        for (ToroidalPos pos : GRID.allPositionsInFillOrder()) {
            bands.put(pos, pos.q() < 4 ? A : B);
        }
        return bands;
    }

    private static ToroidalSizedLocalSearch.ClusterFacts factsOf(List<ToroidalSizedLocalSearch.ClusterFacts> facts, BiomeId biome) {
        return facts.stream().filter(fact -> fact.biome().equals(biome)).findFirst().orElseThrow();
    }

    @Test
    void reportsOneFactPerCluster() {
        RawRuleTable table = fullyLegalTableWithWidths(300, 3000, A, B, C);

        List<ToroidalSizedLocalSearch.ClusterFacts> facts = ToroidalSizedLocalSearch.clusterFacts(table, GRID, model(table), twoBands());

        assertEquals(2, facts.size());
        assertEquals(32, factsOf(facts, A).cellCount());
    }

    @Test
    void countsEdgesToEachOtherBiome() {
        RawRuleTable table = fullyLegalTableWithWidths(300, 3000, A, B, C);

        ToroidalSizedLocalSearch.ClusterFacts band = factsOf(ToroidalSizedLocalSearch.clusterFacts(table, GRID, model(table), twoBands()), A);

        assertEquals(Set.of(B), band.edgesToBiome().keySet());
        assertEquals(band.perimeter(), band.edgesToBiome().get(B));
    }

    @Test
    void reportsHowFarAClusterIsShortOfItsRequiredBorder() {
        RawRuleTable table = fullyLegalTableRequiring(300, 3000, A, C, 2, A, B, C);

        ToroidalSizedLocalSearch.ClusterFacts band = factsOf(ToroidalSizedLocalSearch.clusterFacts(table, GRID, model(table), twoBands()), A);

        assertEquals(2, band.borderShortfall());
    }

    /** The solver's own violation total is the sum over these facts - one source of truth. */
    @Test
    void violationsSumToTheBreakdownsClusterViolation() {
        RawRuleTable table = fullyLegalTableRequiring(300, 600, A, C, 2, A, B, C);
        ClusterSizeModel model = model(table);

        int summed = ToroidalSizedLocalSearch.clusterFacts(table, GRID, model, twoBands()).stream().mapToInt(ToroidalSizedLocalSearch.ClusterFacts::violation).sum();

        assertEquals(ToroidalSizedLocalSearch.breakdown(table, GRID, model, twoBands()).clusterViolation(), summed);
    }
}
