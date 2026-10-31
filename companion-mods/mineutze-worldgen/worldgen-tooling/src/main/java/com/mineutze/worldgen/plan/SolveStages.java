package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;

import java.util.Optional;
import java.util.function.Supplier;

/** Grids and models of each solve stage - same construction as production, so rejections match world creation. */
record SolveStages(
    RawRuleTable table, ToroidalGrid coarseGrid, ClusterSizeModel coarseModel, Optional<ToroidalGrid> fineGrid, Optional<ClusterSizeModel> fineModel,
    ToroidalGrid renderGrid, HexTorusGeometry renderGeometry
) {

    /** @throws CatalogRejected with the generator's own message when it would refuse this graph */
    static SolveStages of(BiomeGraphData catalog, PlanSettings settings) {
        ToroidalGrid coarseGrid = new ToroidalGrid(settings.gridSize(), settings.gridSize());
        RawRuleTable table = readCatalog(() -> RawRuleTable.from(catalog));
        ClusterSizeModel coarseModel = readCatalog(() -> MultigridPatternGenerator.coarseModelOf(table, settings));
        requireRoomForEveryBiome(table, coarseGrid, coarseModel);
        PlanSettings.FineResolution fine = settings.fineResolution();
        if (fine == null) {
            HexTorusGeometry geometry = new HexTorusGeometry(settings.gridSize(), settings.gridSize(), settings.atomWidthBlocks());
            return new SolveStages(table, coarseGrid, coarseModel, Optional.empty(), Optional.empty(), coarseGrid, geometry);
        }
        ToroidalGrid fineGrid = new ToroidalGrid(fine.gridSize(), fine.gridSize());
        ClusterSizeModel fineModel = readCatalog(() -> MultigridPatternGenerator.fineModelOf(table, settings));
        requireRoomForEveryBiome(table, fineGrid, fineModel);
        HexTorusGeometry geometry = new HexTorusGeometry(fine.gridSize(), fine.gridSize(), fine.atomWidthBlocks());
        return new SolveStages(table, coarseGrid, coarseModel, Optional.of(fineGrid), Optional.of(fineModel), fineGrid, geometry);
    }

    /**
     * Catalog readers (and the size models built from it) signal a bad graph with IllegalArgumentException - the same
     * contract GraphValidation relies on. Cause kept, so a bug-IAE still shows its stack. Dedicated reader exception:
     * deferred (worldgen-core change), see increment 3 review.
     */
    private static <T> T readCatalog(Supplier<T> reader) {
        try {
            return reader.get();
        } catch (IllegalArgumentException badGraph) {
            throw new CatalogRejected(badGraph);
        }
    }

    private static void requireRoomForEveryBiome(RawRuleTable table, ToroidalGrid grid, ClusterSizeModel model) {
        long minimumCellsNeeded = ToroidalSizedPatternGenerator.minimumCellsOfAllBiomes(table, model);
        if (minimumCellsNeeded > grid.cellCount()) {
            throw new CatalogRejected(
                "each biome needs at least one cluster of its minimum size, which add up to " + minimumCellsNeeded + " cells, but the " + grid.width() + "x"
                    + grid.height() + " torus has " + grid.cellCount()
            );
        }
    }
}
