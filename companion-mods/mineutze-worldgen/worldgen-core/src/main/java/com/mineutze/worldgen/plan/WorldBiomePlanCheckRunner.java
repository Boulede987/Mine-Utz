package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphJson;
import com.mineutze.worldgen.biomegraph.BiomeId;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Real-scale check of {@link WorldBiomePlan} against the real catalog (2026-09-20): how long a plan
 * takes, how big it is, how fast lookups and {@code nearest} are, that the map repeats after each tile
 * vector, and - the point of running it twice, in separate processes, days apart - that the SAME world
 * seed gives the SAME map. It prints a hash of the raster to compare between runs, and, when the study
 * saved a pattern for this world seed, compares the solver's pattern with it cell by cell.
 */
public final class WorldBiomePlanCheckRunner {

    private static final long WORLD_SEED = 1094L;
    private static final Path SAVED_STUDY_PATTERN = Path.of("build", "reliability", "patterns", "pattern-" + WORLD_SEED + ".csv");
    private static final int LOOKUPS = 2_000_000;

    private WorldBiomePlanCheckRunner() {
    }

    public static void main(String[] args) throws NoSuchAlgorithmException {
        BiomeGraphData catalog = BiomeGraphJson.loadResource(RawRuleTable.class, "/biomegraph/biome-graph.json");
        int threads = Runtime.getRuntime().availableProcessors();

        long start = System.nanoTime();
        WorldBiomePlan plan = WorldBiomePlan.create(catalog, WORLD_SEED, threads, message -> System.out.println("CHECK   progress: " + message));
        System.out.println("CHECK plan for world seed " + WORLD_SEED + " created in " + seconds(start) + " s on " + threads + " threads: warp strength " + plan.warpStrength()
            + ", pattern attempt " + plan.patternAttempts() + ", " + plan.strengthsTried() + " strengths tried, tile " + String.format(Locale.ROOT, "%.0f", plan.tileSideBlocks()) + " blocks across");

        PlanSnapshot snapshot = plan.toSnapshot();
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(snapshot.raster());
        System.out.println("CHECK raster " + snapshot.rasterSize() + "x" + snapshot.rasterSize() + " = " + snapshot.raster().length + " bytes; SHA-256 " + HexFormat.of().formatHex(digest));
        System.out.println("CHECK catalog fingerprint " + snapshot.catalogFingerprint());

        start = System.nanoTime();
        WorldBiomePlan restored = WorldBiomePlan.fromSnapshot(snapshot);
        System.out.println("CHECK restored from the snapshot in " + seconds(start) + " s");

        Random random = new Random(1);
        start = System.nanoTime();
        int mismatches = 0;
        int different = 0;
        for (int lookup = 0; lookup < LOOKUPS; lookup++) {
            double x = (random.nextDouble() - 0.5) * 2_000_000;
            double z = (random.nextDouble() - 0.5) * 2_000_000;
            BiomeId here = plan.biomeAt(x, z);
            if (!here.equals(restored.biomeAt(x, z))) {
                mismatches++;
            }
            if (!here.equals(plan.biomeAt(x + 3 * plan.tileVectorAX() - 2 * plan.tileVectorBX(), z + 3 * plan.tileVectorAY() - 2 * plan.tileVectorBY()))) {
                different++;
            }
        }
        double lookupSeconds = seconds(start);
        System.out.println("CHECK " + LOOKUPS + " random lookups (x3 counting the two comparisons) in " + lookupSeconds + " s = "
            + String.format(Locale.ROOT, "%.0f", 3.0 * LOOKUPS / lookupSeconds) + " lookups per second; snapshot mismatches " + mismatches + "; tile-repeat mismatches " + different);

        start = System.nanoTime();
        int located = 0;
        for (BiomeId biome : plan.biomes()) {
            if (plan.nearest(biome, 12_345, -6_789).isPresent()) {
                located++;
            }
        }
        double nearestSeconds = seconds(start);
        System.out.println("CHECK nearest() found " + located + " of " + plan.biomes().size() + " biomes, " + String.format(Locale.ROOT, "%.0f", 1000 * nearestSeconds / plan.biomes().size()) + " ms each");

        comparePatternWithTheStudy(catalog, threads);
    }

    /** Solves the pattern again from the world seed and compares it with the one the reliability study saved earlier (a different process, a different day). */
    private static void comparePatternWithTheStudy(BiomeGraphData catalog, int threads) {
        if (!Files.exists(SAVED_STUDY_PATTERN)) {
            System.out.println("CHECK no saved study pattern at " + SAVED_STUDY_PATTERN.toAbsolutePath() + " - skipping the cross-process pattern comparison");
            return;
        }
        RawRuleTable table = RawRuleTable.from(catalog);
        PlanSettings settings = PlanSettings.DEFAULT;
        ToroidalGrid grid = new ToroidalGrid(settings.gridSize(), settings.gridSize());
        ClusterSizeModel model = new ClusterSizeModel(table, settings.atomWidthBlocks(), new ShapeRule(settings.perimeterRatio(), Integer.MAX_VALUE, Integer.MAX_VALUE, 2));
        Map<ToroidalPos, BiomeId> solved = ToroidalSizedPatternGenerator.generate(table, grid, model, WORLD_SEED, threads).pattern();
        Map<ToroidalPos, BiomeId> saved = PatternCsv.read(SAVED_STUDY_PATTERN, grid);
        int differing = 0;
        for (Map.Entry<ToroidalPos, BiomeId> entry : saved.entrySet()) {
            if (!entry.getValue().equals(solved.get(entry.getKey()))) {
                differing++;
            }
        }
        System.out.println("CHECK pattern solved now versus the pattern the study saved for world seed " + WORLD_SEED + ": " + differing + " of " + saved.size() + " cells differ"
            + (differing == 0 ? " (identical across processes and days)" : " (NOT identical - the solver is not reproducible)"));
    }

    private static double seconds(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000_000.0;
    }
}
