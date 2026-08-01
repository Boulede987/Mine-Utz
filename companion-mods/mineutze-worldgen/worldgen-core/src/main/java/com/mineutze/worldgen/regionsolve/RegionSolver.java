package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * validated invariant, so this always terminates. Once every seed is
 * resolved, {@link GapFiller} and {@link PatchSizeEnforcer} clean up what
 * the seed-growth phase alone can't guarantee (full coverage and each
 * patch's own minimum size, respectively).
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

        return new RegionSolveResult(cleanUp(state.assignment, input), List.copyOf(warnings));
    }

    /**
     * Fixed boundary cells (from already-solved neighbor regions) must be visible
     * to gap-fill and patch-size cleanup as legality/majority context — merge them
     * in, run both passes, then strip them back out (they belong to the neighbor
     * region, not this one).
     */
    private static Map<MacroCellPos, BiomeId> cleanUp(Map<MacroCellPos, BiomeId> solvedAssignment, RegionSolveInput input) {
        Set<MacroCellPos> regionCellSet = Set.copyOf(input.gridConfig().cellsInRegion(input.region()));
        Map<MacroCellPos, BiomeId> workingAssignment = mergeExternalContext(solvedAssignment, input.fixedBoundaryCells());

        GapFiller.fillGaps(workingAssignment, regionCellSet, input.graph());
        PatchSizeEnforcer.enforceMinimumPatchSize(workingAssignment, regionCellSet, input.gridConfig(), input.graph());

        return Map.copyOf(workingAssignment.entrySet().stream()
            .filter(entry -> regionCellSet.contains(entry.getKey()))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
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
            seedCell, candidate, minCells, targetCells, input.region(), input.gridConfig(), input.graph(), externallyAssigned, input.worldSeed()
        );
    }

    private static String fallbackToFiller(RegionSolveInput input, MacroCellPos seedCell, SolverState state, int seedIndex) {
        BiomeId filler = input.graph().firstUniversalFiller();
        BiomeNode fillerNode = input.graph().node(filler);
        int maxCells = input.gridConfig().cellCountForWidthBlocks(fillerNode.sizeRange().maxWidthBlocks());

        Map<MacroCellPos, BiomeId> externallyAssigned = mergeExternalContext(state.assignment, input.fixedBoundaryCells());
        // minCells=1: filler is allowed next to every node by BiomeGraph's validated
        // invariant, so growth always succeeds for at least the seed cell itself.
        GrowthEngine.GrowthAttempt attempt = GrowthEngine.attemptGrowth(
            seedCell, filler, 1, maxCells, input.region(), input.gridConfig(), input.graph(), externallyAssigned, input.worldSeed()
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
