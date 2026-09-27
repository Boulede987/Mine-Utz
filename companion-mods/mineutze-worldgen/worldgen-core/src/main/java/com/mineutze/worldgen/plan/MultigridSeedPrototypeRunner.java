package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphJson;
import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Tests the multigrid hypothesis (2026-09-27, see {@code DECISIONS.md}): solve at production's own
 * proven scale (40x40 cells, 300-block atoms), then upsample that solution into a seed for the fine
 * 120x120/100-block grid instead of starting fine resolution from a random Voronoi partition.
 * {@code gridSize}/{@code atomWidthBlocks} were chosen so a coarse cell aggregates an exact 3x3 block
 * of fine cells - the coarse solve IS today's production solve, unmodified, not a new mechanism.
 *
 * <p>Prediction under test: since adjacency legality doesn't depend on resolution and a coarse
 * cluster's real-world area equals its upsampled fine cluster's area exactly, the SEEDED fine
 * assignment (before any refinement) should already have close to zero cost - only cluster SHAPE
 * (blocky at the coarse-cell seams) needs fixing, a local problem, not the grid-wide diffusive
 * propagation problem that plateaued simple tuning (see {@code AtomWidthResolutionProbeRunner}).
 */
public final class MultigridSeedPrototypeRunner {

    private static final long WORLD_SEED = 1094L;
    private static final int COARSE_GRID_SIZE = 40;
    private static final double COARSE_ATOM_WIDTH_BLOCKS = 300.0;
    private static final int FINE_GRID_SIZE = 120;
    private static final double FINE_ATOM_WIDTH_BLOCKS = 100.0;
    private static final int AGGREGATION_FACTOR = FINE_GRID_SIZE / COARSE_GRID_SIZE;
    private static final long REFINEMENT_STEPS = 20_000_000L;
    private static final ToroidalSizedLocalSearch.Schedule REFINEMENT_SCHEDULE = new ToroidalSizedLocalSearch.Schedule(0.40, 0.20, 9_000_000);

    private MultigridSeedPrototypeRunner() {
    }

    public static void main(String[] args) {
        BiomeGraphData catalog = BiomeGraphJson.loadResource(RawRuleTable.class, "/biomegraph/biome-graph.json");
        RawRuleTable table = RawRuleTable.from(catalog);
        int threads = Runtime.getRuntime().availableProcessors();

        Map<ToroidalPos, BiomeId> coarseSolution = solveCoarse(table, threads);
        Map<ToroidalPos, BiomeId> seed = seedFineFromCoarse(table, coarseSolution);
        refineFine(table, seed);
    }

    private static Map<ToroidalPos, BiomeId> solveCoarse(RawRuleTable table, int threads) {
        System.out.println("MULTIGRID coarse solve: " + COARSE_GRID_SIZE + "x" + COARSE_GRID_SIZE + " at " + COARSE_ATOM_WIDTH_BLOCKS + " blocks (production settings, unmodified)");
        long start = System.nanoTime();
        ToroidalGrid coarseGrid = new ToroidalGrid(COARSE_GRID_SIZE, COARSE_GRID_SIZE);
        ClusterSizeModel coarseModel = new ClusterSizeModel(table, COARSE_ATOM_WIDTH_BLOCKS, new ShapeRule(PlanSettings.DEFAULT.perimeterRatio(), Integer.MAX_VALUE, Integer.MAX_VALUE, 2));
        ToroidalSizedPatternGenerator.Generated generated = ToroidalSizedPatternGenerator.generate(table, coarseGrid, coarseModel, WORLD_SEED, threads);
        System.out.println("MULTIGRID coarse solved at attempt " + (generated.seedIndex() + 1) + " in " + String.format(Locale.ROOT, "%.1f", secondsSince(start)) + "s");
        return generated.pattern();
    }

    private static Map<ToroidalPos, BiomeId> seedFineFromCoarse(RawRuleTable table, Map<ToroidalPos, BiomeId> coarseSolution) {
        System.out.println();
        System.out.println("MULTIGRID fine grid: " + FINE_GRID_SIZE + "x" + FINE_GRID_SIZE + " at " + FINE_ATOM_WIDTH_BLOCKS + " blocks (aggregation factor " + AGGREGATION_FACTOR + ")");
        ToroidalGrid fineGrid = new ToroidalGrid(FINE_GRID_SIZE, FINE_GRID_SIZE);
        ToroidalGrid coarseGrid = new ToroidalGrid(COARSE_GRID_SIZE, COARSE_GRID_SIZE);
        Map<ToroidalPos, BiomeId> seed = upsampleToFineSeed(fineGrid, coarseGrid, coarseSolution);

        ClusterSizeModel fineModel = fineModel(table);
        int seededCost = ToroidalSizedLocalSearch.cost(table, fineGrid, fineModel, seed);
        System.out.println("MULTIGRID seeded (before any refinement) cost: " + seededCost);
        return seed;
    }

    private static Map<ToroidalPos, BiomeId> upsampleToFineSeed(ToroidalGrid fineGrid, ToroidalGrid coarseGrid, Map<ToroidalPos, BiomeId> coarseSolution) {
        Map<ToroidalPos, BiomeId> seed = new LinkedHashMap<>();
        for (ToroidalPos finePos : fineGrid.allPositionsInFillOrder()) {
            assignUpsampledCell(seed, coarseGrid, coarseSolution, finePos);
        }
        return seed;
    }

    private static void assignUpsampledCell(Map<ToroidalPos, BiomeId> seed, ToroidalGrid coarseGrid, Map<ToroidalPos, BiomeId> coarseSolution, ToroidalPos finePos) {
        ToroidalPos coarsePos = coarseGrid.normalize(finePos.q() / AGGREGATION_FACTOR, finePos.r() / AGGREGATION_FACTOR);
        BiomeId biome = coarseSolution.get(coarsePos);
        if (biome == null) {
            throw new IllegalStateException("MultigridSeedPrototypeRunner: coarse solution has no biome for " + coarsePos + " (fine cell " + finePos + ")");
        }
        seed.put(finePos, biome);
    }

    private static void refineFine(RawRuleTable table, Map<ToroidalPos, BiomeId> seed) {
        System.out.println();
        System.out.println("MULTIGRID refining: " + REFINEMENT_STEPS + " steps, schedule cycleSteps=" + REFINEMENT_SCHEDULE.cycleSteps());
        ToroidalGrid fineGrid = new ToroidalGrid(FINE_GRID_SIZE, FINE_GRID_SIZE);
        ClusterSizeModel fineModel = fineModel(table);
        long start = System.nanoTime();
        ToroidalSizedLocalSearch.Result refined = ToroidalSizedLocalSearch.searchFromSeed(
            table, fineGrid, fineModel, WORLD_SEED, seed, REFINEMENT_STEPS, REFINEMENT_SCHEDULE, () -> false, ToroidalSizedLocalSearch.DEFAULT_RELABEL_PROBABILITY
        );
        System.out.println(
            "MULTIGRID refined: " + (refined.assignment() != null ? "SOLVED" : "not solved") + ", best cost " + refined.bestCost() + " in "
                + String.format(Locale.ROOT, "%.1f", secondsSince(start)) + "s (" + refined.steps() + " steps)"
        );
        System.out.println();
        System.out.println(
            "RESULT versus random-Voronoi baselines at the same fine resolution (DECISIONS.md 2026-09-27): 5M steps->2159, 40M->442, 80M->102, 160M->45, 320M->41"
        );
    }

    private static ClusterSizeModel fineModel(RawRuleTable table) {
        return new ClusterSizeModel(table, FINE_ATOM_WIDTH_BLOCKS, new ShapeRule(PlanSettings.DEFAULT.perimeterRatio(), Integer.MAX_VALUE, Integer.MAX_VALUE, 2));
    }

    private static double secondsSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000_000.0;
    }
}
