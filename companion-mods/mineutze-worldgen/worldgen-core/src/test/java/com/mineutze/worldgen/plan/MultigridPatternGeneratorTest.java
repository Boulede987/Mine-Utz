package com.mineutze.worldgen.plan;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.mineutze.worldgen.plan.ToroidalTestTables.A;
import static com.mineutze.worldgen.plan.ToroidalTestTables.B;
import static com.mineutze.worldgen.plan.ToroidalTestTables.C;
import static com.mineutze.worldgen.plan.ToroidalTestTables.fullyLegalTableWithWidths;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MultigridPatternGeneratorTest {

    private static final long SEED = 4321L;

    private static PlanSettings settingsOf(int coarseGridSize, double coarseAtomWidth, int fineGridSize, double fineAtomWidth) {
        return new PlanSettings(
            coarseGridSize, coarseAtomWidth, 1.3, new double[] {20, 0}, 208, 0.10, 3_000_000, 60, ToroidalSizedPatternGenerator.DEFAULT_SCHEDULE,
            new PlanSettings.FineResolution(fineGridSize, fineAtomWidth, 3_000_000L, 20, ToroidalSizedPatternGenerator.DEFAULT_SCHEDULE, Map.of())
        );
    }

    @Test
    void aFineGridThatIsNotAWholeMultipleOfTheCoarseGridThrows() {
        RawRuleTable table = fullyLegalTableWithWidths(300, 700, A, B, C);
        PlanSettings settings = settingsOf(8, 300, 15, 160);

        assertThrows(IllegalArgumentException.class, () -> MultigridPatternGenerator.generate(table, settings, SEED, 2));
    }

    @Test
    void aSmallCoarseThenFineSolveProducesAFullyValidFinePattern() {
        RawRuleTable table = fullyLegalTableWithWidths(300, 700, A, B, C);
        PlanSettings settings = settingsOf(8, 300, 16, 150);
        ToroidalGrid fineGrid = new ToroidalGrid(16, 16);
        ClusterSizeModel fineModel = MultigridPatternGenerator.fineModelOf(table, settings);

        MultigridPatternGenerator.Generated generated = MultigridPatternGenerator.generate(table, settings, SEED, 2);

        assertEquals(fineGrid.cellCount(), generated.pattern().size());
        assertDoesNotThrow(() -> ToroidalSizedLocalSearch.verify(table, fineGrid, fineModel, generated.pattern()));
        assertEquals(0, ToroidalSizedLocalSearch.cost(table, fineGrid, fineModel, generated.pattern()));
    }
}
