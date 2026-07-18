package com.mineutze.worldgen.biomesource.patch;

import com.mineutze.worldgen.biomesource.BiomeConfig;
import com.mineutze.worldgen.biomesource.TerrainClass;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Seeded best-first patch-growth biome placement, resolved over a bounded rectangular
 * window of coarse cells. Germination uses a local-maximum rule (order-independent),
 * growth uses a global best-first priority queue with a deterministic per-(cell, patch)
 * tie-break, so the result never depends on which order cells are visited in --
 * required because real chunk generation visits cells in whatever order a player
 * explores, not a fixed scan. Determinism verified by {@link PatchGrowthEngineSelfTest}
 * (`./gradlew selfTest`).
 *
 * A patch's identity is its germination cell's own coordinates (the CoarseCell key in
 * patchesByCell), not an incrementing counter -- an earlier version (this class's
 * now-dropped Python prototype) used enumeration order for patch identity and it leaked
 * into the tie-break hash, breaking determinism across scan orders. Coordinates are
 * already order-independent, so this class doesn't need to guard against that mistake
 * at all.
 *
 * Bounded window: SurfacePatchGrid (the caller) picks a window around a queried cell
 * and this class resolves every cell inside it in one batch, memoized by the caller
 * afterwards. Cells near the window edge can, in principle, differ from what an
 * unbounded resolution would produce (their patch might have kept growing past the
 * window edge) -- an accepted tradeoff, not a bug; see DESIGN.md Sec 6's "bounded
 * lookahead" requirement. Window size needs to stay comfortably larger than the
 * largest configured biome's size_max to keep this a rare edge case in practice.
 */
public final class PatchGrowthEngine {

    private static final int[][] CARDINAL_NEIGHBOR_OFFSETS = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
    private static final double PREFERRED_NEIGHBOR_SCORE_BONUS   = 0.5;
    private static final double FORBIDDEN_NEIGHBOR_SCORE_PENALTY = 0.5;
    private static final double TIE_BREAK_SCORE_SCALE            = 1e-6;
    private static final int MINIMUM_SPACING_CELLS               = 1;

    // A germination point deep inside what will become a much larger neighboring
    // patch's territory can survive its own local-maximum check (nothing else was
    // within ITS spacing radius at germination time) and then get boxed in almost
    // immediately once the surrounding patch grows around it -- a small, isolated
    // "island" of one biome inside another. Real, reported symptom, not a hypothetical:
    // a component smaller than this fraction of its own biome's expected minimum size
    // is noise, not an intentionally small patch, and gets dissolved into whichever
    // neighboring biome borders it most (see dissolveSmallIslands). Only ever reassigns
    // into a biome that's terrain-class-eligible for every cell involved -- dissolving
    // into an ineligible biome would silently undo Milestone 3's hard terrain-class gate.
    private static final double SMALL_ISLAND_DISSOLVE_FRACTION = 0.3;

    // cellSeed's cellY parameter doubles as a hash "namespace" selector: 0 for
    // germination-candidate selection, 1 for patch-identity seeding -- keeps the two
    // hash uses from colliding.
    private static final int GERMINATION_CANDIDATE_HASH_NAMESPACE = 0;
    private static final int PATCH_IDENTITY_HASH_NAMESPACE        = 1;

    @FunctionalInterface
    public interface TerrainSampler {
        TerrainClass classify(int cellX, int cellZ);
    }

    public record CoarseCell(int cellX, int cellZ) {
    }

    private record GerminationCandidate(BiomeConfig biome, int spacingCells, double priority) {
    }

    private static final class GrowingPatch {
        final BiomeConfig biome;
        final double targetRadiusBlocks;
        final long identitySeed;
        int cellCount = 1;

        GrowingPatch(BiomeConfig biome, double targetRadiusBlocks, long identitySeed) {
            this.biome = biome;
            this.targetRadiusBlocks = targetRadiusBlocks;
            this.identitySeed = identitySeed;
        }
    }

    private record FrontierEntry(
        double negativeScore, long pushOrder, CoarseCell cell, CoarseCell patchOrigin
    ) implements Comparable<FrontierEntry> {
        @Override
        public int compareTo(FrontierEntry other) {
            int scoreComparison = Double.compare(negativeScore, other.negativeScore);
            return scoreComparison != 0 ? scoreComparison : Long.compare(pushOrder, other.pushOrder);
        }
    }

    private PatchGrowthEngine() {
    }

    public static Map<CoarseCell, ResourceKey<Biome>> resolveWindow(
        long worldSeed,
        List<BiomeConfig> biomes,
        double cellSizeBlocks,
        TerrainSampler terrainSampler,
        int minCellX, int minCellZ, int maxCellXExclusive, int maxCellZExclusive
    ) {
        WindowBounds bounds = new WindowBounds(minCellX, minCellZ, maxCellXExclusive, maxCellZExclusive);
        // One real Climate.Sampler call per window cell, cached here, so germination
        // eligibility and growth-time eligibility never re-sample the same cell twice --
        // real noise evaluation is not free, and every cell in this window is visited by
        // precompute regardless, so caching costs nothing extra.
        Map<CoarseCell, TerrainClass> terrainClassByCell = classifyWindow(terrainSampler, bounds);

        Map<CoarseCell, GerminationCandidate> candidatesByCell = precomputeGerminationCandidates(
            worldSeed, biomes, cellSizeBlocks, terrainClassByCell
        );
        List<CoarseCell> germinationPoints = selectGerminationPoints(candidatesByCell);

        Map<CoarseCell, GrowingPatch> patchesByCell = new HashMap<>();
        Map<CoarseCell, ResourceKey<Biome>> resolvedCells = new HashMap<>();
        for (CoarseCell origin : germinationPoints) {
            GerminationCandidate candidate = candidatesByCell.get(origin);
            long identitySeed = PatchSeedHasher.cellSeed(worldSeed, origin.cellX(), PATCH_IDENTITY_HASH_NAMESPACE, origin.cellZ());
            GrowingPatch patch = new GrowingPatch(candidate.biome(), targetRadiusFromCandidate(candidate), identitySeed);
            patchesByCell.put(origin, patch);
            resolvedCells.put(origin, candidate.biome().id());
        }

        PriorityQueue<FrontierEntry> frontier = new PriorityQueue<>();
        AtomicLong pushCounter = new AtomicLong();
        for (CoarseCell origin : germinationPoints) {
            pushEligibleNeighbors(frontier, pushCounter, origin, origin, patchesByCell.get(origin), resolvedCells, terrainClassByCell, bounds);
        }
        growUntilFrontierEmpty(frontier, pushCounter, patchesByCell, resolvedCells, terrainClassByCell, bounds, cellSizeBlocks);
        dissolveSmallIslands(resolvedCells, terrainClassByCell, biomes, cellSizeBlocks);
        return resolvedCells;
    }

    /** Inclusive/exclusive coarse-cell rectangle a single resolveWindow call is allowed
     * to touch. Growth MUST stay inside this rectangle regardless of target radius --
     * without this check, a biome with a size_max larger than the window (or, worse, an
     * effectively-unbounded placeholder config) grows forever outward, sampling
     * Climate.Sampler an unbounded number of times. That bug is what hung world creation
     * on "Scanning for structures": the eager stronghold-placement ring search triggered
     * a cache miss whose growth phase never terminated within a sane time budget. */
    private record WindowBounds(int minCellX, int minCellZ, int maxCellXExclusive, int maxCellZExclusive) {
        boolean contains(CoarseCell cell) {
            return cell.cellX() >= minCellX && cell.cellX() < maxCellXExclusive
                && cell.cellZ() >= minCellZ && cell.cellZ() < maxCellZExclusive;
        }
    }

    private static Map<CoarseCell, TerrainClass> classifyWindow(TerrainSampler terrainSampler, WindowBounds bounds) {
        Map<CoarseCell, TerrainClass> terrainClassByCell = new HashMap<>();
        for (int cellX = bounds.minCellX(); cellX < bounds.maxCellXExclusive(); cellX++) {
            for (int cellZ = bounds.minCellZ(); cellZ < bounds.maxCellZExclusive(); cellZ++) {
                terrainClassByCell.put(new CoarseCell(cellX, cellZ), terrainSampler.classify(cellX, cellZ));
            }
        }
        return terrainClassByCell;
    }

    /** Pure function of (worldSeed, cellX, cellZ) for every cell -- iteration order over
     * the window never affects the result, only which cells are considered at all. */
    private static Map<CoarseCell, GerminationCandidate> precomputeGerminationCandidates(
        long worldSeed, List<BiomeConfig> biomes, double cellSizeBlocks, Map<CoarseCell, TerrainClass> terrainClassByCell
    ) {
        Map<CoarseCell, GerminationCandidate> candidatesByCell = new HashMap<>();
        for (Map.Entry<CoarseCell, TerrainClass> entry : terrainClassByCell.entrySet()) {
            CoarseCell cell = entry.getKey();
            List<BiomeConfig> eligible = eligibleBiomesForTerrainClass(biomes, entry.getValue());
            if (eligible.isEmpty()) {
                continue;
            }
            long seed = PatchSeedHasher.cellSeed(worldSeed, cell.cellX(), GERMINATION_CANDIDATE_HASH_NAMESPACE, cell.cellZ());
            BiomeConfig chosenBiome = pickWeightedBiome(eligible, seed);
            int spacingCells = spacingRadiusInCells(chosenBiome, cellSizeBlocks);
            double priority = PatchSeedHasher.unitIntervalFromSeed(PatchSeedHasher.mix64Bits(seed));
            candidatesByCell.put(cell, new GerminationCandidate(chosenBiome, spacingCells, priority));
        }
        return candidatesByCell;
    }

    private static List<BiomeConfig> eligibleBiomesForTerrainClass(List<BiomeConfig> biomes, TerrainClass terrainClass) {
        List<BiomeConfig> eligible = new ArrayList<>();
        for (BiomeConfig biome : biomes) {
            if (biome.terrainClasses().contains(terrainClass)) {
                eligible.add(biome);
            }
        }
        return eligible;
    }

    /** Package-private: UndergroundPatchGrid reuses this same weighted-pick formula for
     * choosing among multiple cave biomes linked to the same surface biome. */
    static BiomeConfig pickWeightedBiome(List<BiomeConfig> candidates, long seed) {
        double totalWeight = 0;
        for (BiomeConfig biome : candidates) {
            totalWeight += biome.weight();
        }
        if (totalWeight <= 0) {
            totalWeight = 1.0;
        }
        double roll = PatchSeedHasher.unitIntervalFromSeed(seed) * totalWeight;
        double runningTotal = 0;
        for (BiomeConfig biome : candidates) {
            runningTotal += biome.weight();
            if (roll <= runningTotal) {
                return biome;
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    private static int spacingRadiusInCells(BiomeConfig biome, double cellSizeBlocks) {
        return Math.max(MINIMUM_SPACING_CELLS, (int) Math.round(biome.sizeMinBlocks() / (2 * cellSizeBlocks)));
    }

    /** Order-independent: see isLocalMaximum's docstring below. */
    private static List<CoarseCell> selectGerminationPoints(Map<CoarseCell, GerminationCandidate> candidatesByCell) {
        int lookaheadRadiusCells = 0;
        for (GerminationCandidate candidate : candidatesByCell.values()) {
            lookaheadRadiusCells = Math.max(lookaheadRadiusCells, candidate.spacingCells());
        }
        List<CoarseCell> germinationPoints = new ArrayList<>();
        for (Map.Entry<CoarseCell, GerminationCandidate> entry : candidatesByCell.entrySet()) {
            if (isLocalMaximum(entry.getKey(), entry.getValue(), candidatesByCell, lookaheadRadiusCells)) {
                germinationPoints.add(entry.getKey());
            }
        }
        return germinationPoints;
    }

    private static boolean isLocalMaximum(
        CoarseCell cell, GerminationCandidate candidate, Map<CoarseCell, GerminationCandidate> candidatesByCell, int lookaheadRadiusCells
    ) {
        for (int deltaX = -lookaheadRadiusCells; deltaX <= lookaheadRadiusCells; deltaX++) {
            for (int deltaZ = -lookaheadRadiusCells; deltaZ <= lookaheadRadiusCells; deltaZ++) {
                if (deltaX == 0 && deltaZ == 0) {
                    continue;
                }
                GerminationCandidate neighbor = candidatesByCell.get(new CoarseCell(cell.cellX() + deltaX, cell.cellZ() + deltaZ));
                if (neighbor == null) {
                    continue;
                }
                int symmetricRadius = Math.max(candidate.spacingCells(), neighbor.spacingCells());
                boolean withinRadius = Math.max(Math.abs(deltaX), Math.abs(deltaZ)) <= symmetricRadius;
                if (withinRadius && neighbor.priority() > candidate.priority()) {
                    return false;
                }
            }
        }
        return true;
    }

    private static double targetRadiusFromCandidate(GerminationCandidate candidate) {
        double sizeRangeBlocks = candidate.biome().sizeMaxBlocks() - candidate.biome().sizeMinBlocks();
        return candidate.biome().sizeMinBlocks() / 2.0 + candidate.priority() * sizeRangeBlocks / 2.0;
    }

    private static double computeCellScore(CoarseCell cell, GrowingPatch patch, Map<CoarseCell, ResourceKey<Biome>> resolvedCells) {
        double score = patch.biome.weight();
        for (int[] offset : CARDINAL_NEIGHBOR_OFFSETS) {
            ResourceKey<Biome> neighborBiome = resolvedCells.get(new CoarseCell(cell.cellX() + offset[0], cell.cellZ() + offset[1]));
            if (neighborBiome == null) {
                continue;
            }
            if (patch.biome.preferredNeighbors().contains(neighborBiome)) {
                score += PREFERRED_NEIGHBOR_SCORE_BONUS;
            }
            if (patch.biome.forbiddenNeighbors().contains(neighborBiome)) {
                score -= FORBIDDEN_NEIGHBOR_SCORE_PENALTY;
            }
        }
        // Deterministic tie-break unique per (cell, patch) -- makes real score ties
        // between competing patches vanishingly unlikely, so heap-pop order never
        // depends on push order. Keyed off patch.identitySeed (order-independent).
        long tieBreakSeed = PatchSeedHasher.cellSeed(patch.identitySeed, cell.cellX(), 0, cell.cellZ());
        return score + PatchSeedHasher.unitIntervalFromSeed(tieBreakSeed) * TIE_BREAK_SCORE_SCALE;
    }

    /** Approximate patch radius from assigned cell count, treating the patch as a disc.
     * Public: SurfacePatchGrid's debug tooling reuses this to report human-readable
     * patch sizes without duplicating the area/disc math. */
    public static double patchRadiusFromCellCount(int cellCount, double cellSizeBlocks) {
        double areaBlocksSquared = cellCount * (cellSizeBlocks * cellSizeBlocks);
        return Math.sqrt(areaBlocksSquared / Math.PI);
    }

    private static void pushEligibleNeighbors(
        PriorityQueue<FrontierEntry> frontier, AtomicLong pushCounter, CoarseCell cell, CoarseCell patchOrigin,
        GrowingPatch patch, Map<CoarseCell, ResourceKey<Biome>> resolvedCells,
        Map<CoarseCell, TerrainClass> terrainClassByCell, WindowBounds bounds
    ) {
        for (int[] offset : CARDINAL_NEIGHBOR_OFFSETS) {
            CoarseCell neighbor = new CoarseCell(cell.cellX() + offset[0], cell.cellZ() + offset[1]);
            if (!bounds.contains(neighbor)) {
                continue; // never grow outside the resolved window -- see WindowBounds' class comment
            }
            if (resolvedCells.containsKey(neighbor)) {
                continue;
            }
            if (!patch.biome.terrainClasses().contains(terrainClassByCell.get(neighbor))) {
                continue;
            }
            double score = computeCellScore(neighbor, patch, resolvedCells);
            frontier.add(new FrontierEntry(-score, pushCounter.getAndIncrement(), neighbor, patchOrigin));
        }
    }

    /** Global best-first growth: heap pop order is a pure function of scores (themselves
     * seed-derived), never of push order -- see computeCellScore's tie-break comment. */
    private static void growUntilFrontierEmpty(
        PriorityQueue<FrontierEntry> frontier, AtomicLong pushCounter, Map<CoarseCell, GrowingPatch> patchesByCell,
        Map<CoarseCell, ResourceKey<Biome>> resolvedCells, Map<CoarseCell, TerrainClass> terrainClassByCell,
        WindowBounds bounds, double cellSizeBlocks
    ) {
        while (!frontier.isEmpty()) {
            FrontierEntry entry = frontier.poll();
            if (resolvedCells.containsKey(entry.cell())) {
                continue; // a different patch already claimed this cell
            }
            GrowingPatch patch = patchesByCell.get(entry.patchOrigin());
            double prospectiveRadius = patchRadiusFromCellCount(patch.cellCount + 1, cellSizeBlocks);
            if (prospectiveRadius > patch.targetRadiusBlocks) {
                continue; // this patch has already reached its sampled target size
            }
            resolvedCells.put(entry.cell(), patch.biome.id());
            patch.cellCount++;
            pushEligibleNeighbors(frontier, pushCounter, entry.cell(), entry.patchOrigin(), patch, resolvedCells, terrainClassByCell, bounds);
        }
    }

    /** Post-processing pass: finds contiguous same-biome components too small to be a
     * real patch and reassigns them to whichever bordering biome is both dominant and
     * terrain-class-eligible for every cell involved. See the class-level constant's
     * comment for why this exists. Mutates resolvedCells in place.
     *
     * All decisions read from a frozen snapshot taken before any reassignment, and every
     * reassignment is applied only after every component has been decided -- otherwise
     * two adjacent small islands' border tallies would depend on which one got processed
     * first, breaking this class's order-independence guarantee (see class comment). */
    private static void dissolveSmallIslands(
        Map<CoarseCell, ResourceKey<Biome>> resolvedCells, Map<CoarseCell, TerrainClass> terrainClassByCell,
        List<BiomeConfig> biomes, double cellSizeBlocks
    ) {
        Map<ResourceKey<Biome>, BiomeConfig> configById = new HashMap<>();
        for (BiomeConfig biome : biomes) {
            configById.put(biome.id(), biome);
        }

        Map<CoarseCell, ResourceKey<Biome>> snapshot = new HashMap<>(resolvedCells);
        Map<CoarseCell, ResourceKey<Biome>> reassignments = new HashMap<>();
        Set<CoarseCell> visited = new HashSet<>();
        for (CoarseCell cell : snapshot.keySet()) {
            if (visited.contains(cell)) {
                continue;
            }
            ResourceKey<Biome> biome = snapshot.get(cell);
            List<CoarseCell> component = floodFillSameBiomeComponent(cell, biome, snapshot, visited);

            BiomeConfig biomeConfig = configById.get(biome);
            if (biomeConfig == null || component.size() >= smallIslandThreshold(biomeConfig, cellSizeBlocks)) {
                continue;
            }
            ResourceKey<Biome> replacement = pickDissolveReplacement(component, biome, snapshot, terrainClassByCell, configById);
            if (replacement != null) {
                for (CoarseCell componentCell : component) {
                    reassignments.put(componentCell, replacement);
                }
            }
        }
        resolvedCells.putAll(reassignments);
    }

    private static List<CoarseCell> floodFillSameBiomeComponent(
        CoarseCell start, ResourceKey<Biome> biome, Map<CoarseCell, ResourceKey<Biome>> resolvedCells, Set<CoarseCell> visited
    ) {
        List<CoarseCell> component = new ArrayList<>();
        Deque<CoarseCell> frontier = new ArrayDeque<>();
        visited.add(start);
        frontier.push(start);
        while (!frontier.isEmpty()) {
            CoarseCell cell = frontier.pop();
            component.add(cell);
            for (int[] offset : CARDINAL_NEIGHBOR_OFFSETS) {
                CoarseCell neighbor = new CoarseCell(cell.cellX() + offset[0], cell.cellZ() + offset[1]);
                if (visited.contains(neighbor) || !biome.equals(resolvedCells.get(neighbor))) {
                    continue;
                }
                visited.add(neighbor);
                frontier.push(neighbor);
            }
        }
        return component;
    }

    /** A component is "too small" if it's a meaningful fraction below what even the
     * smallest legitimate roll of its own biome's [size_min, size_max] range would
     * produce -- see the class-level SMALL_ISLAND_DISSOLVE_FRACTION comment. */
    private static int smallIslandThreshold(BiomeConfig biome, double cellSizeBlocks) {
        double minRadiusCells = biome.sizeMinBlocks() / 2.0 / cellSizeBlocks;
        double expectedMinCells = Math.PI * minRadiusCells * minRadiusCells;
        return (int) Math.max(1, Math.round(expectedMinCells * SMALL_ISLAND_DISSOLVE_FRACTION));
    }

    private static ResourceKey<Biome> pickDissolveReplacement(
        List<CoarseCell> component, ResourceKey<Biome> originalBiome, Map<CoarseCell, ResourceKey<Biome>> resolvedCells,
        Map<CoarseCell, TerrainClass> terrainClassByCell, Map<ResourceKey<Biome>, BiomeConfig> configById
    ) {
        Set<CoarseCell> componentCells = new HashSet<>(component);
        Map<ResourceKey<Biome>, Integer> borderCounts = new HashMap<>();
        for (CoarseCell cell : component) {
            for (int[] offset : CARDINAL_NEIGHBOR_OFFSETS) {
                CoarseCell neighbor = new CoarseCell(cell.cellX() + offset[0], cell.cellZ() + offset[1]);
                if (componentCells.contains(neighbor)) {
                    continue;
                }
                ResourceKey<Biome> neighborBiome = resolvedCells.get(neighbor);
                if (neighborBiome == null || neighborBiome.equals(originalBiome)) {
                    continue;
                }
                borderCounts.merge(neighborBiome, 1, Integer::sum);
            }
        }

        return borderCounts.entrySet().stream()
            .sorted(Map.Entry.<ResourceKey<Biome>, Integer>comparingByValue().reversed())
            .map(Map.Entry::getKey)
            .filter(candidate -> isEligibleForEveryCell(candidate, component, terrainClassByCell, configById))
            .findFirst()
            .orElse(null);
    }

    private static boolean isEligibleForEveryCell(
        ResourceKey<Biome> candidate, List<CoarseCell> component,
        Map<CoarseCell, TerrainClass> terrainClassByCell, Map<ResourceKey<Biome>, BiomeConfig> configById
    ) {
        BiomeConfig candidateConfig = configById.get(candidate);
        if (candidateConfig == null) {
            return false;
        }
        for (CoarseCell cell : component) {
            if (!candidateConfig.terrainClasses().contains(terrainClassByCell.get(cell))) {
                return false;
            }
        }
        return true;
    }
}
