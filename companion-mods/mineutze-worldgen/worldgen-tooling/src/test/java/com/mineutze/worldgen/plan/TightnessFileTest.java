package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.MinimalJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.mineutze.worldgen.plan.ToroidalTestTables.A;
import static com.mineutze.worldgen.plan.ToroidalTestTables.B;
import static com.mineutze.worldgen.plan.ToroidalTestTables.C;
import static com.mineutze.worldgen.plan.ToroidalTestTables.D;
import static com.mineutze.worldgen.plan.ToroidalTestTables.fullyLegalCatalog;
import static com.mineutze.worldgen.plan.ToroidalTestTables.noLegalTriangleCatalog;
import static com.mineutze.worldgen.plan.ToroidalTestTables.withGroup;
import static com.mineutze.worldgen.plan.ToroidalTestTables.withRequirement;
import static com.mineutze.worldgen.plan.ToroidalTestTables.withRequirementText;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TightnessFileTest {

    private static final BiomeGraphData CATALOG = withRequirement(fullyLegalCatalog(500, 800, A, B, C), A, B, 2);

    private static final BiomeGraphData PAIRS = noLegalTriangleCatalog(500, 800);
    private static final BiomeGraphData PAIRS_WITH_B_C_LEGAL = GraphEdits.withLegalPair(PAIRS, B, C);

    private static TightnessRanking pairRanking() {
        return new TightnessRanking(List.of(
            new RankedObstacle(new LawObstacle(new IllegalPairLaw(A, C)), 5),
            new RankedObstacle(new LawObstacle(new IllegalPairLaw(B, C)), 4),
            new RankedObstacle(new ShapeLimit(A), 1)
        ), 6);
    }

    private static TightnessRanking requiredBorderRanking() {
        return rankingOfRequiredBorder(CATALOG);
    }

    private static TightnessRanking rankingOfRequiredBorder(BiomeGraphData catalog) {
        ResolvedBorderRequirement requirement = RawRuleTable.from(catalog).requirementOf(A).orElseThrow();
        return new TightnessRanking(List.of(new RankedObstacle(new LawObstacle(new RequiredBorderLaw(A, requirement)), 2)), 3);
    }

    private static List<String> describedObstaclesOf(TightnessRanking ranking) {
        return ranking.entries().stream().map(ranked -> ranked.obstacle().describe()).toList();
    }

    /** The size law exactly as the editor's Solve check states it for A (coarse model of the default settings). */
    private static TightnessRanking sizeRanking() {
        RawRuleTable table = RawRuleTable.from(CATALOG);
        GraphLaw sizeOfA = GraphLaws.of(table, MultigridPatternGenerator.coarseModelOf(table, PlanSettings.DEFAULT)).stream()
            .filter(law -> law instanceof SizeRangeLaw size && size.biome().equals(A)).findFirst().orElseThrow();
        return new TightnessRanking(List.of(new RankedObstacle(new LawObstacle(sizeOfA), 2)), 3);
    }

    private static List<String> describedObstacles(SavedRanking saved) {
        return saved.ranking().entries().stream().map(ranked -> ranked.obstacle().describe()).toList();
    }

    @SuppressWarnings("unchecked")
    private static String withoutRequirementText(String json) {
        Map<String, Object> ranking = new LinkedHashMap<>((Map<String, Object>) MinimalJson.parse(json));
        List<Object> obstacles = ((List<Object>) ranking.get("obstacles")).stream().map(entry -> {
            Map<String, Object> obstacle = new LinkedHashMap<>((Map<String, Object>) entry);
            Object removed = obstacle.remove("requirement");
            assertNotNull(removed, "fixture: expected a stored requirement in " + obstacle);
            return (Object) obstacle;
        }).toList();
        ranking.put("obstacles", obstacles);
        return MinimalJson.writeObject(ranking);
    }

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

    @Test
    void aRankingReadOnTheGraphItWasMeasuredOnRemovesNothing(@TempDir Path folder) {
        Path file = folder.resolve("tightness.json");
        TightnessFile.write(file, everyKind(), CATALOG);

        SavedRanking saved = TightnessFile.readSaved(file, CATALOG);

        assertTrue(saved.measuredOnThisGraph());
        assertEquals(0, saved.removedByEdits());
    }

    /** User edits the graph after the Solve check: the list stays, minus laws the edit already changed. */
    @Test
    void anEditedGraphKeepsTheLawsItStillStates(@TempDir Path folder) {
        Path file = folder.resolve("tightness.json");
        TightnessFile.write(file, pairRanking(), PAIRS);

        SavedRanking saved = TightnessFile.readSaved(file, PAIRS_WITH_B_C_LEGAL);

        assertFalse(saved.measuredOnThisGraph());
        assertEquals(List.of(new IllegalPairLaw(A, C).describe(), new ShapeLimit(A).describe()), describedObstacles(saved));
        assertEquals(1, saved.removedByEdits());
    }

    @Test
    void anEditedGraphDropsTheBiomesItRemoved(@TempDir Path folder) {
        Path file = folder.resolve("tightness.json");
        TightnessFile.write(file, new TightnessRanking(List.of(new RankedObstacle(new ShapeLimit(D), 2), new RankedObstacle(new MissingBiome(A), 1)), 3), fullyLegalCatalog(500, 800, A, B, C, D));

        SavedRanking saved = TightnessFile.readSaved(file, fullyLegalCatalog(500, 800, A, B, C));

        assertEquals(List.of(new MissingBiome(A).describe()), describedObstacles(saved));
    }

    @Test
    void anEditedGraphDropsAChangedRequiredBorder(@TempDir Path folder) {
        Path file = folder.resolve("tightness.json");
        TightnessFile.write(file, requiredBorderRanking(), CATALOG);

        SavedRanking saved = TightnessFile.readSaved(file, withRequirement(fullyLegalCatalog(500, 800, A, B, C), A, B, 3));

        assertEquals(List.of(), describedObstacles(saved));
    }

    @Test
    void anEditedGraphKeepsAnUnchangedRequiredBorder(@TempDir Path folder) {
        Path file = folder.resolve("tightness.json");
        TightnessFile.write(file, requiredBorderRanking(), CATALOG);

        SavedRanking saved = TightnessFile.readSaved(file, withRequirement(CATALOG, B, C, 1));

        assertEquals(describedObstaclesOf(requiredBorderRanking()), describedObstacles(saved));
    }

    /** Same requirement text, but the group it names changed members: another law now. */
    @Test
    void anEditedGraphDropsARequiredBorderWhoseGroupChanged(@TempDir Path folder) {
        Path file = folder.resolve("tightness.json");
        BiomeGraphData beforeEdit = withRequirementText(withGroup(fullyLegalCatalog(500, 800, A, B, C, D), "shore", B), A, "shore >= 2");
        TightnessFile.write(file, rankingOfRequiredBorder(beforeEdit), beforeEdit);
        BiomeGraphData afterEdit = withRequirementText(withGroup(fullyLegalCatalog(500, 800, A, B, C, D), "shore", B, C), A, "shore >= 2");

        SavedRanking saved = TightnessFile.readSaved(file, afterEdit);

        assertEquals(List.of(), describedObstacles(saved));
    }

    @Test
    void anEditedGraphDropsAChangedSizeRange(@TempDir Path folder) {
        Path file = folder.resolve("tightness.json");
        TightnessFile.write(file, sizeRanking(), CATALOG);

        SavedRanking saved = TightnessFile.readSaved(file, withRequirement(fullyLegalCatalog(400, 900, A, B, C), A, B, 2));

        assertEquals(List.of(), describedObstacles(saved));
    }

    @Test
    void anEditedGraphKeepsAnUnchangedSizeRange(@TempDir Path folder) {
        Path file = folder.resolve("tightness.json");
        TightnessFile.write(file, sizeRanking(), CATALOG);

        SavedRanking saved = TightnessFile.readSaved(file, withRequirement(CATALOG, B, C, 1));

        assertEquals(describedObstaclesOf(sizeRanking()), describedObstacles(saved));
    }

    /** No stored required-border key: cannot tell whether an edit changed that border. */
    @Test
    void aFileWithoutRequiredBorderTextAsksForASolveCheckOnceTheGraphChanged(@TempDir Path folder) throws Exception {
        Path file = folder.resolve("tightness.json");
        TightnessFile.write(file, requiredBorderRanking(), CATALOG);
        Files.writeString(file, withoutRequirementText(Files.readString(file)));

        RankingUnavailable refusal = assertThrows(RankingUnavailable.class, () -> TightnessFile.readSaved(file, withRequirement(CATALOG, B, C, 1)));

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

    /** Fingerprint and ranking come from one read: they cannot describe two different files. */
    @Test
    void theSavedFingerprintCoversTheBytesItWasReadFrom(@TempDir Path folder) throws Exception {
        Path file = folder.resolve("tightness.json");
        TightnessFile.write(file, everyKind(), CATALOG);

        SavedRanking saved = TightnessFile.readSaved(file, CATALOG);

        assertEquals(RankingFingerprint.of(Files.readString(file), WorldBiomePlan.fingerprintOf(CATALOG)), saved.fingerprint());
        assertEquals(everyKind(), saved.ranking());
    }

    @Test
    void anUnreadableFileFailsLoudNamingTheFile(@TempDir Path folder) {
        UncheckedIOException failure = assertThrows(UncheckedIOException.class, () -> TightnessFile.readSaved(folder, CATALOG));

        assertTrue(failure.getMessage().contains(folder.toAbsolutePath().toString()), failure.getMessage());
    }

    @Test
    void createsMissingFolders(@TempDir Path folder) {
        Path file = folder.resolve("nested").resolve("tightness.json");

        TightnessFile.write(file, everyKind(), CATALOG);

        assertEquals(12, TightnessFile.read(file, CATALOG).failedAttempts());
    }
}
