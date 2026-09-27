package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphJson;
import com.mineutze.worldgen.biomegraph.BiomeId;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The end-to-end proof that "every world seed succeeds" (2026-09-20, multigrid-aware 2026-09-27):
 * for many DIFFERENT world seeds, against the real catalog, it (1) solves the cell pattern - via
 * {@link ToroidalSizedPatternGenerator} directly, or via {@link MultigridPatternGenerator} when
 * {@link PlanSettings#fineResolution()} is configured, the SAME branch {@link WorldBiomePlan#create}
 * itself takes, so this keeps proving what production actually does - (2) chooses how hard to bend the borders with the
 * {@link RealizationSelector} - the strongest strength whose rendered map passes
 * {@link BiomeMapVerifier} at 4-block resolution - and records per world seed whether each step
 * worked, how many attempts and seconds it took, and which strength was chosen. Each line is
 * appended to a CSV the moment it finishes, so a long run can be inspected - or survive an
 * interruption - without waiting for the end, and every solved pattern is saved so it can be
 * reloaded and looked at later. A failure of either step is recorded with its full message rather
 * than aborting the study.
 */
public final class ToroidalWorldSeedReliabilityRunner {

    /** Same numbers {@link WorldBiomePlan#create} solves a real world with - proving reliability against anything else would prove nothing about production. */
    private static final PlanSettings SETTINGS = PlanSettings.DEFAULT;
    private static final int OFF = Integer.MAX_VALUE;
    private static final int WORLD_SEED_COUNT = 100;
    private static final long[] EXTREME_WORLD_SEEDS = {0L, -1L, Long.MAX_VALUE, Long.MIN_VALUE};
    private static final long FIRST_ORDINARY_WORLD_SEED = 1_000L;
    private static final Path OUTPUT_DIRECTORY = Path.of("build", "reliability");
    private static final Path CSV = OUTPUT_DIRECTORY.resolve("world-seed-reliability.csv");
    private static final Path PATTERN_DIRECTORY = OUTPUT_DIRECTORY.resolve("patterns");

    private ToroidalWorldSeedReliabilityRunner() {
    }

    /** Optional arguments: a catalog file to test instead of the shipped one, and how many ordinary world seeds to try instead of {@value #WORLD_SEED_COUNT}. */
    public static void main(String[] args) {
        BiomeGraphData data = args.length > 0 ? BiomeGraphJson.load(Path.of(args[0])) : BiomeGraphJson.loadResource(RawRuleTable.class, "/biomegraph/biome-graph.json");
        int ordinarySeedCount = args.length > 1 ? Integer.parseInt(args[1]) : WORLD_SEED_COUNT;
        RawRuleTable table = RawRuleTable.from(data);
        PlanSettings.FineResolution fine = SETTINGS.fineResolution();
        int effectiveGridSize = fine != null ? fine.gridSize() : SETTINGS.gridSize();
        double effectiveAtomWidthBlocks = fine != null ? fine.atomWidthBlocks() : SETTINGS.atomWidthBlocks();
        ToroidalGrid grid = new ToroidalGrid(effectiveGridSize, effectiveGridSize);
        HexTorusGeometry geometry = new HexTorusGeometry(effectiveGridSize, effectiveGridSize, effectiveAtomWidthBlocks);
        ClusterSizeModel directModel = fine == null ? new ClusterSizeModel(table, SETTINGS.atomWidthBlocks(), new ShapeRule(SETTINGS.perimeterRatio(), OFF, OFF, 2)) : null;
        int threadCount = Runtime.getRuntime().availableProcessors();
        startCsv();

        List<Long> worldSeeds = new ArrayList<>();
        for (long extreme : EXTREME_WORLD_SEEDS) {
            worldSeeds.add(extreme);
        }
        for (int index = 0; index < ordinarySeedCount; index++) {
            worldSeeds.add(FIRST_ORDINARY_WORLD_SEED + index);
        }

        Tally tally = new Tally();
        for (long worldSeed : worldSeeds) {
            processWorld(worldSeed, table, grid, geometry, directModel, threadCount, tally);
        }
        report(tally, worldSeeds.size(), threadCount, effectiveGridSize);
    }

    private record PatternOutcome(Map<ToroidalPos, BiomeId> pattern, int patternAttempts) {
    }

    /** {@code directModel} is null when {@link PlanSettings#fineResolution()} is configured - the pattern is then solved via {@link MultigridPatternGenerator} instead. */
    private static PatternOutcome solvePattern(RawRuleTable table, ToroidalGrid grid, ClusterSizeModel directModel, long worldSeed, int threadCount) {
        if (directModel != null) {
            ToroidalSizedPatternGenerator.Generated generated = ToroidalSizedPatternGenerator.generate(table, grid, directModel, worldSeed, threadCount);
            return new PatternOutcome(generated.pattern(), generated.seedIndex() + 1);
        }
        MultigridPatternGenerator.Generated generated = MultigridPatternGenerator.generate(table, SETTINGS, worldSeed, threadCount);
        return new PatternOutcome(generated.pattern(), generated.coarseAttempts());
    }

    /** What the study has seen so far. */
    private static final class Tally {
        private int patternsSolved;
        private int realized;
        private final List<Integer> attemptsUsed = new ArrayList<>();
        private final List<Double> patternSeconds = new ArrayList<>();
        private final List<Double> realizationSeconds = new ArrayList<>();
        private final Map<Double, Integer> worldsPerStrength = new TreeMap<>(Collections.reverseOrder());
        private double worstAbsoluteDrift;
        private final List<Long> patternFailures = new ArrayList<>();
        private final List<Long> realizationFailures = new ArrayList<>();
    }

    private static void processWorld(
        long worldSeed, RawRuleTable table, ToroidalGrid grid, HexTorusGeometry geometry, ClusterSizeModel directModel, int threadCount, Tally tally
    ) {
        long patternStart = System.nanoTime();
        PatternOutcome outcome;
        try {
            outcome = solvePattern(table, grid, directModel, worldSeed, threadCount);
        } catch (IllegalStateException failure) {
            tally.patternFailures.add(worldSeed);
            record(worldSeed, "PATTERN FAILED", -1, secondsSince(patternStart), "", -1, "", failure.getMessage());
            return;
        }
        double patternSeconds = secondsSince(patternStart);
        tally.patternsSolved++;
        tally.attemptsUsed.add(outcome.patternAttempts());
        tally.patternSeconds.add(patternSeconds);
        PatternCsv.write(PATTERN_DIRECTORY.resolve("pattern-" + worldSeed + ".csv"), outcome.pattern());

        long realizationStart = System.nanoTime();
        try {
            RealizationSelector.Selection selection = RealizationSelector.select(table, grid, outcome.pattern(), geometry, worldSeed, SETTINGS);
            double realizationSeconds = secondsSince(realizationStart);
            tally.realized++;
            tally.realizationSeconds.add(realizationSeconds);
            tally.worldsPerStrength.merge(selection.strength(), 1, Integer::sum);
            tally.worstAbsoluteDrift = Math.max(tally.worstAbsoluteDrift, Arrays.stream(selection.report().driftRatios()).map(Math::abs).max().orElse(0));
            record(worldSeed, "solved", outcome.patternAttempts(), patternSeconds, String.valueOf(selection.strength()), selection.candidatesTried(), String.format(Locale.ROOT, "%.1f", realizationSeconds), "");
        } catch (IllegalStateException failure) {
            tally.realizationFailures.add(worldSeed);
            record(worldSeed, "REALIZATION FAILED", outcome.patternAttempts(), patternSeconds, "", -1, String.format(Locale.ROOT, "%.1f", secondsSince(realizationStart)), failure.getMessage());
        }
    }

    private static void report(Tally tally, int worldCount, int threadCount, int effectiveGridSize) {
        System.out.println("WORLDS " + tally.patternsSolved + " of " + worldCount + " world seeds produced a verified PATTERN, and " + tally.realized + " of " + worldCount
            + " a verified REALIZED MAP (" + effectiveGridSize + "x" + effectiveGridSize + ", perimeter ratio " + SETTINGS.perimeterRatio() + ", " + threadCount + " threads; up to " + ToroidalSizedPatternGenerator.DEFAULT_MAX_SEEDS
            + " pattern attempts of " + String.format(Locale.ROOT, "%,d", ToroidalSizedPatternGenerator.DEFAULT_STEPS_PER_SEED) + " steps; map checked at " + SETTINGS.rasterSamplesPerSide() + "x" + SETTINGS.rasterSamplesPerSide()
            + " samples, size tolerance " + (int) (100 * SETTINGS.sizeToleranceOfMinimum()) + "% of the minimum)");
        if (!tally.patternFailures.isEmpty()) {
            System.out.println("WORLDS PATTERN FAILED for world seeds " + tally.patternFailures);
        }
        if (!tally.realizationFailures.isEmpty()) {
            System.out.println("WORLDS REALIZATION FAILED for world seeds " + tally.realizationFailures);
        }
        if (!tally.attemptsUsed.isEmpty()) {
            Collections.sort(tally.attemptsUsed);
            Collections.sort(tally.patternSeconds);
            System.out.println("WORLDS pattern attempts needed: median " + percentile(tally.attemptsUsed, 0.5) + ", p90 " + percentile(tally.attemptsUsed, 0.9) + ", max " + percentile(tally.attemptsUsed, 1.0));
            System.out.println("WORLDS pattern seconds per world: median " + String.format(Locale.ROOT, "%.1f", percentile(tally.patternSeconds, 0.5)) + ", p90 " + String.format(Locale.ROOT, "%.1f", percentile(tally.patternSeconds, 0.9))
                + ", max " + String.format(Locale.ROOT, "%.1f", percentile(tally.patternSeconds, 1.0)));
        }
        if (!tally.realizationSeconds.isEmpty()) {
            Collections.sort(tally.realizationSeconds);
            System.out.println("WORLDS realization seconds per world: median " + String.format(Locale.ROOT, "%.1f", percentile(tally.realizationSeconds, 0.5)) + ", p90 "
                + String.format(Locale.ROOT, "%.1f", percentile(tally.realizationSeconds, 0.9)) + ", max " + String.format(Locale.ROOT, "%.1f", percentile(tally.realizationSeconds, 1.0)));
            System.out.println("WORLDS warp strength chosen (strongest that verified), worlds per strength: " + tally.worldsPerStrength);
            System.out.println("WORLDS worst area drift of any region in any chosen map: " + String.format(Locale.ROOT, "%.1f%%", 100 * tally.worstAbsoluteDrift));
        }
        System.out.println("WORLDS per-world data: " + CSV.toAbsolutePath() + " ; patterns: " + PATTERN_DIRECTORY.toAbsolutePath());
    }

    private static <T> T percentile(List<T> sorted, double quantile) {
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.floor(sorted.size() * quantile)));
    }

    private static double secondsSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000_000.0;
    }

    private static void startCsv() {
        try {
            Files.createDirectories(OUTPUT_DIRECTORY);
            Files.writeString(CSV, "worldSeed,outcome,patternAttempts,patternSeconds,warpStrength,realizationStrengthsTried,realizationSeconds,failureMessage\n");
        } catch (IOException failure) {
            throw new UncheckedIOException("ToroidalWorldSeedReliabilityRunner: could not create " + CSV, failure);
        }
    }

    private static void record(long worldSeed, String outcome, int patternAttempts, double patternSeconds, String warpStrength, int strengthsTried, String realizationSeconds, String failureMessage) {
        String line = worldSeed + "," + outcome + "," + patternAttempts + "," + String.format(Locale.ROOT, "%.1f", patternSeconds) + "," + warpStrength + "," + strengthsTried + "," + realizationSeconds + ","
            + failureMessage.replace(',', ';').replace('\n', ' ');
        System.out.println("WORLD " + line);
        try {
            Files.writeString(CSV, line + "\n", StandardOpenOption.APPEND);
        } catch (IOException failure) {
            throw new UncheckedIOException("ToroidalWorldSeedReliabilityRunner: could not append to " + CSV, failure);
        }
    }
}
