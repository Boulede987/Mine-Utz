package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Decides WHAT to place before deciding WHERE — the structural fix for the
 * old algorithm's real flaw: it placed a fixed grid of seeds first, then
 * hoped whatever biomes/sizes got chosen afterward happened to fit. Total
 * demand was never actually checked against available supply, just tuned
 * empirically to fail less often.
 *
 * Repeatedly draws a random (biome, target size) pair and adds it to the
 * plan as long as there's room left for it; keeps trying smaller draws
 * even after a big one doesn't fit, until nothing could possibly fit
 * anymore, or the packing target is reached. The result is sorted
 * largest-first: placing big patches while space is still open is what
 * actually gets a good fit (classic bin-packing heuristic) — the reverse
 * order boxes big patches out.
 *
 * Deliberately stops short of filling the region to the brim: budgeting
 * demands up to full capacity left {@link GrowthEngine} nothing but
 * thin leftover slivers between already-placed patches by the time the
 * last few demands grew — geometrically, the gap between tightly-packed
 * blobs IS thin and winding, so a size-targeted flood-fill through it
 * traces a corridor, not a patch. Capping demand at
 * {@link #TARGET_PACKING_FRACTION} of capacity leaves real open space
 * for growth to stay round, and hands the rest to {@link GapFiller}'s
 * neighbor-majority sweep instead — which has no size target to chase
 * through a gap, so it blobs outward from existing edges organically.
 * Placeholder fraction, same as the grid/blend-width constants in
 * doc/ARCHITECTURE.md — tune from how patch shapes actually look
 * in-game (or via the export CLI), not upfront math.
 */
final class BudgetPlanner {

    private static final long SALT = 0xB0DE_57A5L;
    private static final int MAX_DRAWS = 10_000;
    private static final double TARGET_PACKING_FRACTION = 0.72;

    private BudgetPlanner() {
    }

    static List<PatchDemand> plan(BiomeGraph graph, MacroGridConfig grid, RegionPos region, long worldSeed) {
        int fullCapacity = grid.regionSizeCellsPerSide() * grid.regionSizeCellsPerSide();
        int capacity = (int) (fullCapacity * TARGET_PACKING_FRACTION);
        List<BiomeId> nodeIds = graph.nodes().stream().map(BiomeNode::id).sorted().toList();
        int smallestMinCells = nodeIds.stream()
            .mapToInt(id -> grid.cellCountForWidthBlocks(graph.node(id).sizeRange().minWidthBlocks()))
            .min().orElseThrow();

        Random random = new Random(seedFor(worldSeed, region));
        List<PatchDemand> demands = new ArrayList<>();
        int used = 0;
        int draws = 0;

        while (capacity - used >= smallestMinCells && draws < MAX_DRAWS) {
            draws++;
            BiomeId biomeId = nodeIds.get(random.nextInt(nodeIds.size()));
            BiomeNode node = graph.node(biomeId);
            int minCells = grid.cellCountForWidthBlocks(node.sizeRange().minWidthBlocks());
            int maxCells = grid.cellCountForWidthBlocks(node.sizeRange().maxWidthBlocks());
            int targetCells = minCells >= maxCells ? minCells : minCells + random.nextInt(maxCells - minCells + 1);

            if (used + targetCells > capacity) {
                continue;
            }
            demands.add(new PatchDemand(biomeId, minCells, targetCells));
            used += targetCells;
        }

        return demands.stream()
            .sorted(Comparator.comparingInt(PatchDemand::targetCells).reversed())
            .toList();
    }

    private static long seedFor(long worldSeed, RegionPos region) {
        long seed = worldSeed ^ SALT;
        seed = seed * 31 + region.x();
        seed = seed * 31 + region.z();
        return seed;
    }
}
