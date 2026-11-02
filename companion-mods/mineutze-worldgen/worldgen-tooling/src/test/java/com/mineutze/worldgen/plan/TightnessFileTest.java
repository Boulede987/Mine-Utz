package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.mineutze.worldgen.plan.ToroidalTestTables.A;
import static com.mineutze.worldgen.plan.ToroidalTestTables.B;
import static com.mineutze.worldgen.plan.ToroidalTestTables.C;
import static com.mineutze.worldgen.plan.ToroidalTestTables.fullyLegalCatalog;
import static com.mineutze.worldgen.plan.ToroidalTestTables.noLegalTriangleCatalog;
import static com.mineutze.worldgen.plan.ToroidalTestTables.withRequirement;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TightnessFileTest {

    private static final BiomeGraphData CATALOG = withRequirement(fullyLegalCatalog(500, 800, A, B, C), A, B, 2);

    private static TightnessRanking everyKind() {
        ResolvedBorderRequirement requirement = RawRuleTable.from(CATALOG).requirementOf(A).orElseThrow();
        return new TightnessRanking(List.of(
            new RankedObstacle(new LawObstacle(new IllegalPairLaw(A, C)), 9),
            new RankedObstacle(new LawObstacle(new SizeRangeLaw(B, 3, 8)), 7),
            new RankedObstacle(new LawObstacle(new RequiredBorderLaw(A, requirement)), 5),
            new RankedObstacle(new MissingBiome(C), 3),
            new RankedObstacle(new ShapeLimit(B), 1)
        ), 12);
    }

    @Test
    void readsBackWhatWasWrittenForTheSameGraph(@TempDir Path folder) {
        Path file = folder.resolve("tightness.json");

        TightnessFile.write(file, everyKind(), CATALOG);

        assertEquals(everyKind(), TightnessFile.read(file, CATALOG));
    }

    /** A ranking from another graph would loosen laws that may no longer exist: refuse, ask for a new Solve check. */
    @Test
    void refusesARankingWrittenForADifferentGraph(@TempDir Path folder) {
        Path file = folder.resolve("tightness.json");
        TightnessFile.write(file, everyKind(), CATALOG);

        RankingUnavailable refusal = assertThrows(RankingUnavailable.class, () -> TightnessFile.read(file, noLegalTriangleCatalog(500, 800)));

        assertTrue(refusal.getMessage().contains("Solve check"), refusal.getMessage());
    }

    @Test
    void aMissingFileAsksForASolveCheck(@TempDir Path folder) {
        RankingUnavailable refusal = assertThrows(RankingUnavailable.class, () -> TightnessFile.read(folder.resolve("absent.json"), CATALOG));

        assertTrue(refusal.getMessage().contains("Solve check"), refusal.getMessage());
    }

    @Test
    void aCorruptFileFailsLoudNamingTheFile(@TempDir Path folder) throws Exception {
        Path file = folder.resolve("tightness.json");
        java.nio.file.Files.writeString(file, "{\"fingerprint\": ");

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> TightnessFile.read(file, CATALOG));

        assertTrue(failure.getMessage().contains("tightness.json"), failure.getMessage());
    }

    @Test
    void createsMissingFolders(@TempDir Path folder) {
        Path file = folder.resolve("nested").resolve("tightness.json");

        TightnessFile.write(file, everyKind(), CATALOG);

        assertEquals(12, TightnessFile.read(file, CATALOG).failedAttempts());
    }
}
