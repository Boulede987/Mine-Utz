package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One fresh, valid biome pattern per world seed, solved coarse-then-fine (2026-09-27, see
 * {@code DECISIONS.md}): the coarse stage is exactly {@link ToroidalSizedPatternGenerator}'s own
 * proven single-resolution solve, at {@code settings}' base {@code gridSize}/{@code
 * atomWidthBlocks} - not a new mechanism, today's production solve, unmodified. Its solution is
 * upsampled into a seed for {@code settings.fineResolution()}'s finer grid (each fine cell inherits
 * its coarse parent's biome), then refined there instead of from a random Voronoi partition.
 * Refinement retries with a different internal random seed (the same structural, coarse-derived
 * seed each time) up to {@code fineResolution.maxRefinementAttempts()} times, taking the first that
 * solves - the same "try several independent attempts, take whichever solves first" mechanism the
 * coarse stage's own {@link ParallelSeedSearch} already uses, applied one level deeper.
 *
 * <p>Prototyped and measured as {@code MultigridSeedPrototypeRunner} before this class existed: see
 * that class's own doc and the three 2026-09-27 {@code DECISIONS.md} entries for the full history,
 * including a prediction that was wrong in an informative way (seeded cost isn't near zero) and a
 * real local-trap finding that motivated the retry mechanism.
 */
final class MultigridPatternGenerator {

    record Generated(Map<ToroidalPos, BiomeId> pattern, int coarseAttempts, int refinementAttempts) {
    }

    private MultigridPatternGenerator() {
    }

    static Generated generate(RawRuleTable table, PlanSettings settings, long worldSeed, int threads) {
        PlanSettings.FineResolution fine = settings.fineResolution();
        if (fine == null) {
            throw new IllegalArgumentException("MultigridPatternGenerator: settings has no fineResolution configured");
        }
        ToroidalGrid coarseGrid = new ToroidalGrid(settings.gridSize(), settings.gridSize());
        ClusterSizeModel coarseModel = coarseModelOf(table, settings);
        ToroidalSizedPatternGenerator.Generated coarseSolved = ToroidalSizedPatternGenerator.generate(
            table, coarseGrid, coarseModel, worldSeed, settings.patternStepsPerAttempt(), settings.maxPatternAttempts(), settings.patternSchedule(), threads
        );

        ToroidalGrid fineGrid = new ToroidalGrid(fine.gridSize(), fine.gridSize());
        ClusterSizeModel fineModel = fineModelOf(table, settings);
        int aggregationFactor = aggregationFactorOf(coarseGrid, fineGrid);
        Map<ToroidalPos, BiomeId> seed = upsampleToFineSeed(fineGrid, coarseGrid, coarseSolved.pattern(), aggregationFactor);

        RefinementResult refined = refineWithRetries(table, fineGrid, fineModel, worldSeed, seed, fine);
        return new Generated(refined.pattern(), coarseSolved.seedIndex() + 1, refined.attemptsUsed());
    }

    private static ClusterSizeModel coarseModelOf(RawRuleTable table, PlanSettings settings) {
        return new ClusterSizeModel(table, settings.atomWidthBlocks(), new ShapeRule(settings.perimeterRatio(), Integer.MAX_VALUE, Integer.MAX_VALUE, 2));
    }

    /** The fine-resolution model, with a per-biome perimeter floor for every entry in {@code settings.fineResolution().shapeFloorRatios()}. Also reused by {@link WorldBiomePlan#requireCatalogAcceptable}. */
    static ClusterSizeModel fineModelOf(RawRuleTable table, PlanSettings settings) {
        PlanSettings.FineResolution fine = settings.fineResolution();
        ShapeRule defaultShape = new ShapeRule(settings.perimeterRatio(), Integer.MAX_VALUE, Integer.MAX_VALUE, 2);
        Map<BiomeId, ShapeRule> overrides = new LinkedHashMap<>();
        for (Map.Entry<BiomeId, Double> entry : fine.shapeFloorRatios().entrySet()) {
            overrides.put(entry.getKey(), new ShapeRule(ShapeRule.NO_PERIMETER_LIMIT, entry.getValue(), Integer.MAX_VALUE, Integer.MAX_VALUE, 2));
        }
        return new ClusterSizeModel(table, fine.atomWidthBlocks(), defaultShape, overrides);
    }

    private static int aggregationFactorOf(ToroidalGrid coarseGrid, ToroidalGrid fineGrid) {
        if (fineGrid.width() % coarseGrid.width() != 0 || fineGrid.height() % coarseGrid.height() != 0) {
            throw new IllegalArgumentException(
                "MultigridPatternGenerator: fine grid " + fineGrid.width() + "x" + fineGrid.height() + " must be a whole multiple of the coarse grid " + coarseGrid.width() + "x"
                    + coarseGrid.height()
            );
        }
        int factorQ = fineGrid.width() / coarseGrid.width();
        int factorR = fineGrid.height() / coarseGrid.height();
        if (factorQ != factorR) {
            throw new IllegalArgumentException("MultigridPatternGenerator: fine/coarse aggregation factor must be the same on both axes, got " + factorQ + " and " + factorR);
        }
        return factorQ;
    }

    private static Map<ToroidalPos, BiomeId> upsampleToFineSeed(ToroidalGrid fineGrid, ToroidalGrid coarseGrid, Map<ToroidalPos, BiomeId> coarseSolution, int aggregationFactor) {
        Map<ToroidalPos, BiomeId> seed = new LinkedHashMap<>();
        for (ToroidalPos finePos : fineGrid.allPositionsInFillOrder()) {
            assignUpsampledCell(seed, coarseGrid, coarseSolution, finePos, aggregationFactor);
        }
        return seed;
    }

    private static void assignUpsampledCell(Map<ToroidalPos, BiomeId> seed, ToroidalGrid coarseGrid, Map<ToroidalPos, BiomeId> coarseSolution, ToroidalPos finePos, int aggregationFactor) {
        ToroidalPos coarsePos = coarseGrid.normalize(finePos.q() / aggregationFactor, finePos.r() / aggregationFactor);
        BiomeId biome = coarseSolution.get(coarsePos);
        if (biome == null) {
            throw new IllegalStateException("MultigridPatternGenerator: coarse solution has no biome for " + coarsePos + " (fine cell " + finePos + ")");
        }
        seed.put(finePos, biome);
    }

    private record RefinementResult(Map<ToroidalPos, BiomeId> pattern, int attemptsUsed) {
    }

    /** Tries a different internal random seed (the same structural, coarse-derived seed each time) up to {@code fine.maxRefinementAttempts()} times, taking the first that solves. */
    private static RefinementResult refineWithRetries(RawRuleTable table, ToroidalGrid fineGrid, ClusterSizeModel fineModel, long worldSeed, Map<ToroidalPos, BiomeId> seed, PlanSettings.FineResolution fine) {
        int bestCost = Integer.MAX_VALUE;
        for (int attempt = 0; attempt < fine.maxRefinementAttempts(); attempt++) {
            ToroidalSizedLocalSearch.Result result = ToroidalSizedLocalSearch.searchFromSeed(
                table, fineGrid, fineModel, worldSeed + attempt, seed, fine.refinementStepsPerAttempt(), fine.refinementSchedule(), () -> false,
                ToroidalSizedLocalSearch.DEFAULT_RELABEL_PROBABILITY
            );
            if (result.assignment() != null) {
                return new RefinementResult(result.assignment(), attempt + 1);
            }
            bestCost = Math.min(bestCost, result.bestCost());
        }
        throw new IllegalStateException(
            "MultigridPatternGenerator: fine refinement did not solve for world seed " + worldSeed + " after " + fine.maxRefinementAttempts() + " attempts of "
                + fine.refinementStepsPerAttempt() + " steps each on a " + fineGrid.width() + "x" + fineGrid.height() + " grid. Closest cost " + bestCost + "."
        );
    }
}
