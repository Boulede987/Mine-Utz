package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
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

        fillGaps(state.assignment, input.gridConfig().cellsInRegion(input.region()), input.graph());

        return new RegionSolveResult(Map.copyOf(state.assignment), List.copyOf(warnings));
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
    private static void fillGaps(Map<MacroCellPos, BiomeId> assignment, List<MacroCellPos> regionCells, BiomeGraph graph) {
        List<MacroCellPos> remaining = regionCells.stream()
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

        if (!remaining.isEmpty()) {
            BiomeId filler = graph.universalFillers().stream().sorted().findFirst()
                .orElseThrow(() -> new IllegalStateException("BiomeGraph has no universalFiller; should have failed validation earlier"));
            remaining.forEach(cell -> assignment.put(cell, filler));
        }
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
        BiomeId filler = input.graph().universalFillers().stream().sorted().findFirst()
            .orElseThrow(() -> new IllegalStateException("BiomeGraph has no universalFiller; should have failed validation earlier"));
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
