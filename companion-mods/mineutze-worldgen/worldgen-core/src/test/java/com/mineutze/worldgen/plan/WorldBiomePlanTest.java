package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import static com.mineutze.worldgen.plan.ToroidalTestTables.A;
import static com.mineutze.worldgen.plan.ToroidalTestTables.B;
import static com.mineutze.worldgen.plan.ToroidalTestTables.C;
import static com.mineutze.worldgen.plan.ToroidalTestTables.fullyLegalCatalog;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldBiomePlanTest {

    private static final BiomeGraphData CATALOG = fullyLegalCatalog(500, 700, A, B, C);
    private static final PlanSettings SMALL = new PlanSettings(8, 300, 1.3, new double[] {20, 0}, 208, 0.10, 3_000_000, 60, ToroidalSizedPatternGenerator.DEFAULT_SCHEDULE);

    private static WorldBiomePlan plan(long worldSeed) {
        return WorldBiomePlan.create(CATALOG, worldSeed, 2, message -> { }, SMALL);
    }

    private static boolean sameSnapshot(PlanSnapshot first, PlanSnapshot second) {
        return first.schemaVersion() == second.schemaVersion() && first.worldSeed() == second.worldSeed() && first.catalogFingerprint().equals(second.catalogFingerprint())
            && first.gridWidth() == second.gridWidth() && first.gridHeight() == second.gridHeight() && first.atomWidthBlocks() == second.atomWidthBlocks()
            && first.warpStrength() == second.warpStrength() && first.patternAttempts() == second.patternAttempts() && first.strengthsTried() == second.strengthsTried()
            && first.palette().equals(second.palette()) && first.rasterSize() == second.rasterSize() && Arrays.equals(first.raster(), second.raster());
    }

    private static double[] randomPoint(Random random, double range) {
        return new double[] {(random.nextDouble() - 0.5) * range, (random.nextDouble() - 0.5) * range};
    }

    @Test
    void everyCatalogBiomeAppearsSomewhereInTheMap() {
        WorldBiomePlan plan = plan(1L);
        Set<Integer> seen = new HashSet<>();
        for (byte index : plan.toSnapshot().raster()) {
            seen.add(index & 0xFF);
        }

        assertEquals(Set.of(0, 1, 2), seen);
        assertEquals(List.of(A, B, C), plan.biomes());
    }

    @Test
    void sameWorldSeedGivesTheSameSnapshotWhateverTheThreadCount() {
        PlanSnapshot oneThread = WorldBiomePlan.create(CATALOG, 77L, 1, message -> { }, SMALL).toSnapshot();
        PlanSnapshot manyThreads = WorldBiomePlan.create(CATALOG, 77L, 6, message -> { }, SMALL).toSnapshot();

        assertTrue(sameSnapshot(oneThread, manyThreads));
    }

    @Test
    void differentWorldSeedsGiveDifferentMaps() {
        assertFalse(Arrays.equals(plan(1L).toSnapshot().raster(), plan(2L).toSnapshot().raster()));
    }

    @Test
    void aSnapshotRestoresAPlanThatAnswersEveryLookupTheSame() {
        WorldBiomePlan original = plan(5L);
        WorldBiomePlan restored = WorldBiomePlan.fromSnapshot(original.toSnapshot());
        Random random = new Random(1);

        for (int point = 0; point < 500; point++) {
            double[] where = randomPoint(random, 40_000);
            assertEquals(original.biomeAt(where[0], where[1]), restored.biomeAt(where[0], where[1]));
        }
        assertTrue(sameSnapshot(original.toSnapshot(), restored.toSnapshot()));
    }

    /** The world repeats every tile vector - also for negative and very large coordinates, where a careless wrap would break. */
    @Test
    void theMapRepeatsExactlyAfterEveryTileVectorEvenFarFromTheOrigin() {
        WorldBiomePlan plan = plan(6L);
        Random random = new Random(2);

        for (int point = 0; point < 400; point++) {
            double[] where = randomPoint(random, 20_000);
            BiomeId here = plan.biomeAt(where[0], where[1]);
            for (int stepsAlongA = -3; stepsAlongA <= 3; stepsAlongA += 3) {
                for (int stepsAlongB = -3; stepsAlongB <= 3; stepsAlongB += 3) {
                    double x = where[0] + stepsAlongA * plan.tileVectorAX() + stepsAlongB * plan.tileVectorBX();
                    double z = where[1] + stepsAlongA * plan.tileVectorAY() + stepsAlongB * plan.tileVectorBY();
                    assertEquals(here, plan.biomeAt(x, z), "point " + where[0] + "," + where[1]);
                }
            }
        }
        assertEquals(plan.biomeAt(100, 200), plan.biomeAt(100 + 5_000 * plan.tileVectorAX(), 200 + 5_000 * plan.tileVectorAY()));
    }

    @Test
    void aLookupAtTheCenterOfARasterSampleReturnsThatSamplesBiome() {
        WorldBiomePlan plan = plan(7L);
        PlanSnapshot snapshot = plan.toSnapshot();
        int size = snapshot.rasterSize();

        for (int j = 0; j < size; j += 17) {
            for (int i = 0; i < size; i += 13) {
                double u = (i + 0.5) / size;
                double v = (j + 0.5) / size;
                double x = u * plan.tileVectorAX() + v * plan.tileVectorBX();
                double z = u * plan.tileVectorAY() + v * plan.tileVectorBY();
                assertEquals(snapshot.palette().get(snapshot.raster()[j * size + i] & 0xFF), plan.biomeAt(x, z).toString(), "sample " + i + "," + j);
            }
        }
    }

    /** Brute force, written independently of the plan: the closest sample of the biome over every lattice translate in a neighborhood wide enough for the query points (this test tile is only ~2,000 blocks across, the queries up to ~6,000 away). */
    @Test
    void nearestFindsTheClosestPlaceWhereTheBiomeOccurs() {
        WorldBiomePlan plan = plan(8L);
        PlanSnapshot snapshot = plan.toSnapshot();
        int size = snapshot.rasterSize();
        Random random = new Random(3);

        for (int trial = 0; trial < 8; trial++) {
            double[] from = randomPoint(random, 12_000);
            for (BiomeId biome : plan.biomes()) {
                int paletteIndex = snapshot.palette().indexOf(biome.toString());
                double best = Double.MAX_VALUE;
                for (int j = 0; j < size; j++) {
                    for (int i = 0; i < size; i++) {
                        if ((snapshot.raster()[j * size + i] & 0xFF) != paletteIndex) {
                            continue;
                        }
                        double u = (i + 0.5) / size;
                        double v = (j + 0.5) / size;
                        for (int a = -7; a <= 7; a++) {
                            for (int b = -7; b <= 7; b++) {
                                double x = (u + a) * plan.tileVectorAX() + (v + b) * plan.tileVectorBX();
                                double z = (u + a) * plan.tileVectorAY() + (v + b) * plan.tileVectorBY();
                                best = Math.min(best, Math.hypot(x - from[0], z - from[1]));
                            }
                        }
                    }
                }

                Optional<BlockPosition> found = plan.nearest(biome, from[0], from[1]);

                assertTrue(found.isPresent());
                assertEquals(biome, plan.biomeAt(found.get().x() + 0.5, found.get().z() + 0.5), "the position must really hold the biome");
                assertEquals(best, Math.hypot(found.get().x() - from[0], found.get().z() - from[1]), 2.0, "biome " + biome + " from " + from[0] + "," + from[1]);
            }
        }
    }

    @Test
    void nearestOfABiomeThatIsNotInTheCatalogIsEmpty() {
        assertTrue(plan(9L).nearest(new BiomeId("test", "nowhere"), 0, 0).isEmpty());
    }

    @Test
    void theCatalogFingerprintIsStableAndChangesWithTheCatalog() {
        String first = plan(1L).toSnapshot().catalogFingerprint();
        String again = plan(2L).toSnapshot().catalogFingerprint();
        String other = WorldBiomePlan.create(fullyLegalCatalog(500, 800, A, B, C), 1L, 2, message -> { }, SMALL).toSnapshot().catalogFingerprint();

        assertEquals(first, again);
        assertNotEquals(first, other);
        assertEquals(64, first.length());
    }

    @Test
    void thePublicFingerprintIsWhatAPlanRecords() {
        assertEquals(WorldBiomePlan.fingerprintOf(CATALOG), plan(1L).toSnapshot().catalogFingerprint());
        assertNotEquals(WorldBiomePlan.fingerprintOf(CATALOG), WorldBiomePlan.fingerprintOf(fullyLegalCatalog(500, 800, A, B, C)));
    }

    @Test
    void progressIsReportedAtEachStage() {
        List<String> messages = new ArrayList<>();

        WorldBiomePlan.create(CATALOG, 3L, 2, messages::add, SMALL);

        assertTrue(messages.stream().anyMatch(message -> message.contains("solving")), messages.toString());
        assertTrue(messages.stream().anyMatch(message -> message.contains("attempt")), messages.toString());
        assertTrue(messages.stream().anyMatch(message -> message.contains("warp strength")), messages.toString());
    }

    @Test
    void fromSnapshotRejectsAFormatItDoesNotKnow() {
        PlanSnapshot good = plan(4L).toSnapshot();
        PlanSnapshot future = new PlanSnapshot(
            good.schemaVersion() + 1, good.worldSeed(), good.catalogFingerprint(), good.gridWidth(), good.gridHeight(), good.atomWidthBlocks(), good.warpStrength(),
            good.patternAttempts(), good.strengthsTried(), good.palette(), good.rasterSize(), good.raster()
        );

        assertThrows(IllegalArgumentException.class, () -> WorldBiomePlan.fromSnapshot(future));
    }

    @Test
    void fromSnapshotRejectsARasterOfTheWrongLength() {
        PlanSnapshot good = plan(4L).toSnapshot();
        PlanSnapshot truncated = new PlanSnapshot(
            good.schemaVersion(), good.worldSeed(), good.catalogFingerprint(), good.gridWidth(), good.gridHeight(), good.atomWidthBlocks(), good.warpStrength(),
            good.patternAttempts(), good.strengthsTried(), good.palette(), good.rasterSize(), Arrays.copyOf(good.raster(), good.raster().length - 1)
        );

        assertThrows(IllegalArgumentException.class, () -> WorldBiomePlan.fromSnapshot(truncated));
    }

    @Test
    void fromSnapshotRejectsARasterIndexOutsideThePalette() {
        PlanSnapshot good = plan(4L).toSnapshot();
        byte[] corrupted = good.raster().clone();
        corrupted[10] = (byte) 200;
        PlanSnapshot broken = new PlanSnapshot(
            good.schemaVersion(), good.worldSeed(), good.catalogFingerprint(), good.gridWidth(), good.gridHeight(), good.atomWidthBlocks(), good.warpStrength(),
            good.patternAttempts(), good.strengthsTried(), good.palette(), good.rasterSize(), corrupted
        );

        assertThrows(IllegalArgumentException.class, () -> WorldBiomePlan.fromSnapshot(broken));
    }

    /** A plan is read by many threads for the life of the game; what was handed to or taken from it must not be able to change it. */
    @Test
    void changingATakenOrGivenSnapshotNeverChangesThePlan() {
        WorldBiomePlan plan = plan(11L);
        BiomeId before = plan.biomeAt(500, 500);
        byte differentBiome = (byte) ((plan.biomes().indexOf(before) + 1) % 3);

        PlanSnapshot taken = plan.toSnapshot();
        Arrays.fill(taken.raster(), differentBiome);
        assertEquals(before, plan.biomeAt(500, 500), "the snapshot must be a copy");

        PlanSnapshot given = plan.toSnapshot();
        WorldBiomePlan restored = WorldBiomePlan.fromSnapshot(given);
        BiomeId restoredBefore = restored.biomeAt(500, 500);
        Arrays.fill(given.raster(), (byte) ((restored.biomes().indexOf(restoredBefore) + 1) % 3));
        assertEquals(restoredBefore, restored.biomeAt(500, 500), "the restored plan must not share the caller's array");
    }

    /**
     * A hand-made map with ONE sample of biome B, at tile coordinates (0.55, 0.55). From the tile origin
     * the tile's own copy of it is 0.78 sides away, but the copy one tile over along A is only about 0.51
     * sides away - the tile is a 60-degree rhombus, so the nearest copy is not always in the same tile.
     */
    @Test
    void nearestFindsACopyAcrossTheTileEdgeWhenThatIsCloser() {
        int size = 10;
        byte[] raster = new byte[size * size];
        raster[5 * size + 5] = 1;
        PlanSnapshot handMade = new PlanSnapshot(WorldBiomePlan.SCHEMA_VERSION, 1L, "hand-made", 8, 8, 300, 0, 1, 1, List.of(A.toString(), B.toString()), size, raster);
        WorldBiomePlan plan = WorldBiomePlan.fromSnapshot(handMade);

        double expected = Double.MAX_VALUE;
        for (int a = -2; a <= 2; a++) {
            for (int b = -2; b <= 2; b++) {
                double x = (0.55 + a) * plan.tileVectorAX() + (0.55 + b) * plan.tileVectorBX();
                double z = (0.55 + a) * plan.tileVectorAY() + (0.55 + b) * plan.tileVectorBY();
                expected = Math.min(expected, Math.hypot(x, z));
            }
        }
        double sameTileCopy = Math.hypot(0.55 * plan.tileVectorAX() + 0.55 * plan.tileVectorBX(), 0.55 * plan.tileVectorAY() + 0.55 * plan.tileVectorBY());
        assertTrue(expected < 0.7 * sameTileCopy, "the test only means something if another tile's copy is much closer");

        BlockPosition found = plan.nearest(B, 0, 0).orElseThrow();

        assertEquals(expected, Math.hypot(found.x(), found.z()), 1.5);
    }

    @Test
    void theSnapshotReportsHowTheMapWasMade() {
        PlanSnapshot snapshot = plan(10L).toSnapshot();

        assertEquals(WorldBiomePlan.SCHEMA_VERSION, snapshot.schemaVersion());
        assertEquals(10L, snapshot.worldSeed());
        assertEquals(8, snapshot.gridWidth());
        assertTrue(snapshot.patternAttempts() >= 1);
        assertTrue(snapshot.strengthsTried() >= 1);
        assertArrayEquals(new double[] {snapshot.warpStrength()}, new double[] {snapshot.warpStrength()}, 0.0);
    }
}
