package com.mineutze.worldgen.coherence;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphJson;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The end-to-end proof that "every world seed succeeds" (2026-09-19): runs the real
 * {@link ToroidalSizedPatternGenerator} on many DIFFERENT world seeds against the real catalog and
 * records, per world seed, whether it produced a pattern, which attempt of its sequence solved it,
 * and how long it took. Each line is appended to a CSV the moment it finishes, so a long run can
 * be inspected - or survive an interruption - without waiting for the end. A failed world seed is
 * recorded with the generator's full failure message rather than aborting the study.
 */
public final class ToroidalWorldSeedReliabilityRunner {

    private static final double ATOM_WIDTH_BLOCKS = 300;
    private static final int OFF = Integer.MAX_VALUE;
    private static final int GRID_SIZE = 40;
    private static final double PERIMETER_RATIO = 1.3;
    private static final int WORLD_SEED_COUNT = 100;
    private static final long[] EXTREME_WORLD_SEEDS = {0L, -1L, Long.MAX_VALUE, Long.MIN_VALUE};
    private static final long FIRST_ORDINARY_WORLD_SEED = 1_000L;
    private static final Path CSV = Path.of("build", "reliability", "world-seed-reliability.csv");

    private ToroidalWorldSeedReliabilityRunner() {
    }

    public static void main(String[] args) {
        BiomeGraphData data = BiomeGraphJson.loadResource(RawRuleTable.class, "/biomegraph/biome-graph.json");
        RawRuleTable table = RawRuleTable.from(data);
        ToroidalGrid grid = new ToroidalGrid(GRID_SIZE, GRID_SIZE);
        ClusterSizeModel model = new ClusterSizeModel(table, ATOM_WIDTH_BLOCKS, new ShapeRule(PERIMETER_RATIO, OFF, OFF, 2));
        int threadCount = Runtime.getRuntime().availableProcessors();
        startCsv();

        List<Long> worldSeeds = new ArrayList<>();
        for (long extreme : EXTREME_WORLD_SEEDS) {
            worldSeeds.add(extreme);
        }
        for (int index = 0; index < WORLD_SEED_COUNT; index++) {
            worldSeeds.add(FIRST_ORDINARY_WORLD_SEED + index);
        }

        int succeeded = 0;
        List<Integer> attemptsUsed = new ArrayList<>();
        List<Double> secondsUsed = new ArrayList<>();
        List<Long> failedWorldSeeds = new ArrayList<>();
        for (long worldSeed : worldSeeds) {
            long startNanos = System.nanoTime();
            try {
                ToroidalSizedPatternGenerator.Generated generated = ToroidalSizedPatternGenerator.generate(table, grid, model, worldSeed, threadCount);
                double seconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;
                succeeded++;
                attemptsUsed.add(generated.seedIndex() + 1);
                secondsUsed.add(seconds);
                record(worldSeed, "solved", generated.seedIndex() + 1, seconds, "");
            } catch (IllegalStateException failure) {
                double seconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;
                failedWorldSeeds.add(worldSeed);
                record(worldSeed, "FAILED", -1, seconds, failure.getMessage().replace('\n', ' '));
            }
        }

        System.out.println("WORLDS " + succeeded + " of " + worldSeeds.size() + " world seeds produced a verified pattern (" + GRID_SIZE + "x" + GRID_SIZE + ", perimeter ratio " + PERIMETER_RATIO + ", "
            + threadCount + " threads, " + String.format("%,d", ToroidalSizedPatternGenerator.DEFAULT_STEPS_PER_SEED) + " steps per attempt, up to " + ToroidalSizedPatternGenerator.DEFAULT_MAX_SEEDS + " attempts)");
        if (!failedWorldSeeds.isEmpty()) {
            System.out.println("WORLDS FAILED world seeds: " + failedWorldSeeds + " - see " + CSV.toAbsolutePath());
        }
        if (!attemptsUsed.isEmpty()) {
            Collections.sort(attemptsUsed);
            Collections.sort(secondsUsed);
            System.out.println("WORLDS attempts needed: median " + attemptsUsed.get(attemptsUsed.size() / 2) + ", p90 " + attemptsUsed.get((int) Math.floor(attemptsUsed.size() * 0.9))
                + ", max " + attemptsUsed.get(attemptsUsed.size() - 1));
            System.out.println("WORLDS seconds per world: median " + String.format("%.1f", secondsUsed.get(secondsUsed.size() / 2)) + ", p90 "
                + String.format("%.1f", secondsUsed.get((int) Math.floor(secondsUsed.size() * 0.9))) + ", max " + String.format("%.1f", secondsUsed.get(secondsUsed.size() - 1)));
        }
        System.out.println("WORLDS per-world data: " + CSV.toAbsolutePath());
    }

    private static void startCsv() {
        try {
            Files.createDirectories(CSV.toAbsolutePath().getParent());
            Files.writeString(CSV, "worldSeed,outcome,attemptsUsed,seconds,failureMessage\n");
        } catch (IOException failure) {
            throw new UncheckedIOException("ToroidalWorldSeedReliabilityRunner: could not create " + CSV, failure);
        }
    }

    private static void record(long worldSeed, String outcome, int attemptsUsed, double seconds, String failureMessage) {
        String line = worldSeed + "," + outcome + "," + attemptsUsed + "," + String.format("%.1f", seconds) + "," + failureMessage.replace(',', ';');
        System.out.println("WORLD " + line);
        try {
            Files.writeString(CSV, line + "\n", StandardOpenOption.APPEND);
        } catch (IOException failure) {
            throw new UncheckedIOException("ToroidalWorldSeedReliabilityRunner: could not append to " + CSV, failure);
        }
    }
}
