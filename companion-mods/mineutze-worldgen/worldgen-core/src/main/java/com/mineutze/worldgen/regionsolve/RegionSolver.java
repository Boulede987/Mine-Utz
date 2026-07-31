package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Solves one region: CSP backtracking over seeds, no constraint relaxation.
 * See doc/ARCHITECTURE.md layer 2 and doc/region-solve-activity.puml.
 *
 * On conflict, a seed tries its next candidate biome before anything backs
 * up to an earlier seed. If the backtrack depth cap is hit (or there's no
 * earlier seed left to back up to), the seed falls back to a
 * universal-filler biome — guaranteed legal by {@link BiomeGraph}'s
 * validated invariant, so this always terminates.
 */
public final class RegionSolver {

    private static final long CANDIDATE_SHUFFLE_SALT = 0xC0FFEE_D15CL;
    private static final long TARGET_SIZE_SALT = 0x517E_5EEDL;

    private RegionSolver() {
    }

    public static RegionSolveResult solve(RegionSolveInput input) {
        List<MacroCellPos> seeds = SeedPlacer.placeSeeds(input.region(), input.gridConfig(), input.worldSeed(), input.seedSpacingBlocks());
        List<BiomeId> baseCandidateOrder = input.graph().nodes().stream().map(BiomeNode::id).sorted().toList();
        List<List<BiomeId>> candidateOrders = seeds.stream()
            .map(seed -> shuffledCandidates(baseCandidateOrder, input.worldSeed(), seed))
            .toList();

        SolverState state = new SolverState(seeds.size());
        List<String> warnings = new ArrayList<>();

        int seedIndex = 0;
        while (seedIndex < seeds.size()) {
            MacroCellPos seedCell = seeds.get(seedIndex);
            List<BiomeId> candidates = candidateOrders.get(seedIndex);

            GrowthEngine.GrowthAttempt attempt = tryCandidates(input, seedCell, candidates, state, seedIndex);
            if (attempt != null) {
                state.commit(seedIndex, attempt.claimedCells());
                seedIndex++;
                continue;
            }

            if (seedIndex == 0 || state.backtrackStepsUsed >= input.backtrackDepthCap()) {
                warnings.add(fallbackToFiller(input, seedCell, state, seedIndex));
                seedIndex++;
            } else {
                seedIndex = state.backtrackOneSeed(seedIndex);
            }
        }

        // Fixed boundary cells (from already-solved neighbor regions) must be visible
        // to gap-fill and patch-size cleanup as legality/majority context — merge them
        // in, run both passes, then strip them back out (they belong to the neighbor
        // region, not this one).
        Set<MacroCellPos> regionCellSet = Set.copyOf(input.gridConfig().cellsInRegion(input.region()));
        Map<MacroCellPos, BiomeId> workingAssignment = mergeExternalContext(state.assignment, input.fixedBoundaryCells());

        fillGaps(workingAssignment, regionCellSet, input.graph());
        enforceMinimumPatchSize(workingAssignment, regionCellSet, input.gridConfig(), input.graph());

        Map<MacroCellPos, BiomeId> finalAssignment = workingAssignment.entrySet().stream()
            .filter(entry -> regionCellSet.contains(entry.getKey()))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        return new RegionSolveResult(Map.copyOf(finalAssignment), List.copyOf(warnings));
    }

    /**
     * Seed growth rarely tiles a region perfectly — patches can leave small
     * gaps between them where neither neighbor's growth reached. Sweep
     * leftover cells repeatedly (a filled cell can make its own neighbors
     * fillable next pass), assigning each the most common already-assigned
     * neighbor biome that's still legal there. Any cell with no legal
     * neighbor option (isolated, or none of its neighbors are assigned at
     * all) gets the universal filler — always legal by construction.
     */
    private static void fillGaps(Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet, BiomeGraph graph) {
        List<MacroCellPos> remaining = regionCellSet.stream()
            .filter(cell -> !assignment.containsKey(cell))
            .sorted(Comparator.comparingInt(MacroCellPos::x).thenComparingInt(MacroCellPos::z))
            .collect(Collectors.toCollection(ArrayList::new));

        boolean progress = true;
        while (progress && !remaining.isEmpty()) {
            progress = false;
            for (var iterator = remaining.iterator(); iterator.hasNext(); ) {
                MacroCellPos cell = iterator.next();
                Optional<BiomeId> choice = mostCommonLegalNeighborBiome(cell, assignment, graph);
                if (choice.isPresent()) {
                    assignment.put(cell, choice.get());
                    iterator.remove();
                    progress = true;
                }
            }
        }

        remaining.forEach(cell -> assignment.put(cell, firstUniversalFiller(graph)));
    }

    /**
     * Adjacency legality alone doesn't guarantee a biome's own declared size
     * range. In practice this shouldn't currently trigger: growth only
     * commits a patch once it already meets minCells, and gap-fill only
     * ever extends an existing same-biome component (a gap cell's chosen
     * biome always comes from an already-assigned neighbor, so it fuses
     * onto that neighbor's component rather than starting a disconnected
     * one) — so no code path today produces an undersized non-filler
     * patch. Kept as a safety net against that invariant breaking later
     * (e.g. a future weighted candidate selection or gap-fill change);
     * package-visible so it can be exercised directly in tests rather than
     * only through the (currently unreachable) integration path.
     * Universal-filler patches are exempt — they're already the fallback
     * of last resort, nothing to merge them into.
     */
    static void enforceMinimumPatchSize(
        Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet, MacroGridConfig grid, BiomeGraph graph
    ) {
        List<Set<MacroCellPos>> components = connectedComponents(assignment, regionCellSet);
        Map<MacroCellPos, Integer> patchSizeByCell = new HashMap<>();
        for (Set<MacroCellPos> component : components) {
            component.forEach(cell -> patchSizeByCell.put(cell, component.size()));
        }

        for (Set<MacroCellPos> component : components) {
            BiomeId biome = assignment.get(component.iterator().next());
            BiomeNode node = graph.node(biome);
            int minCells = grid.cellCountForWidthBlocks(node.sizeRange().minWidthBlocks());
            if (node.universalFiller() || component.size() >= minCells) {
                continue;
            }

            BiomeId replacement = bestMergeTarget(component, biome, assignment, patchSizeByCell, graph)
                .orElseGet(() -> firstUniversalFiller(graph));
            component.forEach(cell -> assignment.put(cell, replacement));
        }
    }

    private static List<Set<MacroCellPos>> connectedComponents(Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet) {
        List<MacroCellPos> orderedCells = regionCellSet.stream()
            .sorted(Comparator.comparingInt(MacroCellPos::x).thenComparingInt(MacroCellPos::z))
            .toList();
        Set<MacroCellPos> visited = new HashSet<>();
        List<Set<MacroCellPos>> components = new ArrayList<>();

        for (MacroCellPos start : orderedCells) {
            if (visited.contains(start)) {
                continue;
            }
            BiomeId biome = assignment.get(start);
            Set<MacroCellPos> component = new LinkedHashSet<>();
            Deque<MacroCellPos> frontier = new ArrayDeque<>(List.of(start));
            visited.add(start);
            while (!frontier.isEmpty()) {
                MacroCellPos cell = frontier.pop();
                component.add(cell);
                for (MacroCellPos neighbor : cell.orthogonalNeighbors()) {
                    if (regionCellSet.contains(neighbor) && !visited.contains(neighbor) && biome.equals(assignment.get(neighbor))) {
                        visited.add(neighbor);
                        frontier.push(neighbor);
                    }
                }
            }
            components.add(component);
        }
        return components;
    }

    /** Largest (by existing patch size) neighboring biome that stays legal across every cell in the component if adopted. */
    private static Optional<BiomeId> bestMergeTarget(
        Set<MacroCellPos> component, BiomeId ownBiome, Map<MacroCellPos, BiomeId> assignment,
        Map<MacroCellPos, Integer> patchSizeByCell, BiomeGraph graph
    ) {
        Map<BiomeId, Integer> candidatePatchSize = new HashMap<>();
        for (MacroCellPos cell : component) {
            for (MacroCellPos neighbor : cell.orthogonalNeighbors()) {
                if (component.contains(neighbor)) {
                    continue;
                }
                BiomeId neighborBiome = assignment.get(neighbor);
                if (neighborBiome != null && !neighborBiome.equals(ownBiome)) {
                    candidatePatchSize.merge(neighborBiome, patchSizeByCell.getOrDefault(neighbor, 0), Math::max);
                }
            }
        }

        return candidatePatchSize.keySet().stream()
            .filter(candidate -> isLegalForWholeComponent(component, candidate, assignment, graph))
            .max(Comparator.<BiomeId>comparingInt(candidatePatchSize::get).thenComparing(Comparator.naturalOrder()));
    }

    /** Like GrowthEngine.isLegal, but checked across every cell of a whole component at once, ignoring the component's own internal edges. */
    private static boolean isLegalForWholeComponent(
        Set<MacroCellPos> component, BiomeId candidate, Map<MacroCellPos, BiomeId> assignment, BiomeGraph graph
    ) {
        for (MacroCellPos cell : component) {
            for (MacroCellPos neighbor : cell.orthogonalNeighbors()) {
                if (component.contains(neighbor)) {
                    continue;
                }
                BiomeId neighborBiome = assignment.get(neighbor);
                if (neighborBiome != null && !graph.isAllowed(candidate, neighborBiome)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static BiomeId firstUniversalFiller(BiomeGraph graph) {
        return graph.universalFillers().stream().sorted().findFirst()
            .orElseThrow(() -> new IllegalStateException("BiomeGraph has no universalFiller; should have failed validation earlier"));
    }

    private static Optional<BiomeId> mostCommonLegalNeighborBiome(MacroCellPos cell, Map<MacroCellPos, BiomeId> assignment, BiomeGraph graph) {
        Map<BiomeId, Long> neighborBiomeCounts = cell.orthogonalNeighbors().stream()
            .map(assignment::get)
            .filter(Objects::nonNull)
            .collect(Collectors.groupingBy(biome -> biome, Collectors.counting()));

        return neighborBiomeCounts.entrySet().stream()
            .filter(entry -> GrowthEngine.isLegal(cell, entry.getKey(), assignment, graph))
            .max(Comparator.<Map.Entry<BiomeId, Long>>comparingLong(Map.Entry::getValue).thenComparing(Map.Entry::getKey))
            .map(Map.Entry::getKey);
    }

    private static GrowthEngine.GrowthAttempt tryCandidates(
        RegionSolveInput input, MacroCellPos seedCell, List<BiomeId> candidates, SolverState state, int seedIndex
    ) {
        while (state.candidateIndex[seedIndex] < candidates.size()) {
            BiomeId candidate = candidates.get(state.candidateIndex[seedIndex]);
            GrowthEngine.GrowthAttempt attempt = attemptCandidateGrowth(input, seedCell, candidate, state.assignment);
            if (attempt.success()) {
                return attempt;
            }
            state.candidateIndex[seedIndex]++;
        }
        return null;
    }

    private static GrowthEngine.GrowthAttempt attemptCandidateGrowth(
        RegionSolveInput input, MacroCellPos seedCell, BiomeId candidate, Map<MacroCellPos, BiomeId> committedSoFar
    ) {
        BiomeNode node = input.graph().node(candidate);
        int minCells = input.gridConfig().cellCountForWidthBlocks(node.sizeRange().minWidthBlocks());
        int maxCells = input.gridConfig().cellCountForWidthBlocks(node.sizeRange().maxWidthBlocks());
        int targetCells = pickTargetCells(minCells, maxCells, input.worldSeed(), seedCell, candidate);

        Map<MacroCellPos, BiomeId> externallyAssigned = mergeExternalContext(committedSoFar, input.fixedBoundaryCells());
        return GrowthEngine.attemptGrowth(
            seedCell, candidate, minCells, targetCells, input.region(), input.gridConfig(), input.graph(), externallyAssigned
        );
    }

    private static String fallbackToFiller(RegionSolveInput input, MacroCellPos seedCell, SolverState state, int seedIndex) {
        BiomeId filler = firstUniversalFiller(input.graph());
        BiomeNode fillerNode = input.graph().node(filler);
        int maxCells = input.gridConfig().cellCountForWidthBlocks(fillerNode.sizeRange().maxWidthBlocks());

        Map<MacroCellPos, BiomeId> externallyAssigned = mergeExternalContext(state.assignment, input.fixedBoundaryCells());
        // minCells=1: filler is allowed next to every node by BiomeGraph's validated
        // invariant, so growth always succeeds for at least the seed cell itself.
        GrowthEngine.GrowthAttempt attempt = GrowthEngine.attemptGrowth(
            seedCell, filler, 1, maxCells, input.region(), input.gridConfig(), input.graph(), externallyAssigned
        );
        state.commit(seedIndex, attempt.claimedCells());
        return "Backtrack depth cap reached at seed " + seedCell + " — assigned universal filler " + filler
            + " instead. Check the biome graph for an over-constrained region.";
    }

    private static Map<MacroCellPos, BiomeId> mergeExternalContext(
        Map<MacroCellPos, BiomeId> committedSoFar, Map<MacroCellPos, BiomeId> fixedBoundaryCells
    ) {
        Map<MacroCellPos, BiomeId> merged = new LinkedHashMap<>(fixedBoundaryCells);
        merged.putAll(committedSoFar);
        return merged;
    }

    private static List<BiomeId> shuffledCandidates(List<BiomeId> baseOrder, long worldSeed, MacroCellPos seedCell) {
        List<BiomeId> shuffled = new ArrayList<>(baseOrder);
        long seed = worldSeed ^ CANDIDATE_SHUFFLE_SALT;
        seed = seed * 31 + seedCell.x();
        seed = seed * 31 + seedCell.z();
        Collections.shuffle(shuffled, new Random(seed));
        return shuffled;
    }

    private static int pickTargetCells(int minCells, int maxCells, long worldSeed, MacroCellPos seedCell, BiomeId candidate) {
        if (minCells >= maxCells) {
            return minCells;
        }
        long seed = worldSeed ^ TARGET_SIZE_SALT;
        seed = seed * 31 + seedCell.x();
        seed = seed * 31 + seedCell.z();
        seed = seed * 31 + candidate.hashCode();
        return minCells + new Random(seed).nextInt(maxCells - minCells + 1);
    }

    /** Mutable bookkeeping for the backtracking search — one CSP solve attempt. */
    private static final class SolverState {
        final Map<MacroCellPos, BiomeId> assignment = new LinkedHashMap<>();
        final int[] candidateIndex;
        final List<Map<MacroCellPos, BiomeId>> claimedBySeed;
        int backtrackStepsUsed = 0;

        SolverState(int seedCount) {
            this.candidateIndex = new int[seedCount];
            this.claimedBySeed = new ArrayList<>(Collections.nCopies(seedCount, Map.of()));
        }

        void commit(int seedIndex, Map<MacroCellPos, BiomeId> claimedCells) {
            claimedBySeed.set(seedIndex, claimedCells);
            assignment.putAll(claimedCells);
        }

        /** Undoes the previous seed's claim, advances its candidate pointer, returns its index. */
        int backtrackOneSeed(int currentSeedIndex) {
            backtrackStepsUsed++;
            candidateIndex[currentSeedIndex] = 0;

            int previousSeedIndex = currentSeedIndex - 1;
            assignment.keySet().removeAll(claimedBySeed.get(previousSeedIndex).keySet());
            claimedBySeed.set(previousSeedIndex, Map.of());
            candidateIndex[previousSeedIndex]++;
            return previousSeedIndex;
        }
    }
}
