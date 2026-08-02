package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Solves one region: {@link BudgetPlanner} decides WHAT to place (a list of
 * (biome, target size) demands bounded to fit the region's total capacity),
 * this class decides the placement ORDER (most adjacency-constrained biomes
 * first, largest-first within a tier — see {@link #orderByPlacementPriority})
 * and WHERE — trying each demand at {@link SeedPlacer}'s candidate positions,
 * preferring ones already touching a compatible neighbor, via
 * {@link GrowthEngine} until one succeeds. No constraint relaxation: a demand
 * that can't find a legal spot anywhere is dropped entirely, never forced or
 * shrunk (see doc/ARCHITECTURE.md "Decided" — replaces the old per-position
 * candidate-biome backtracking, which fought the wrong problem: over-
 * subscription from placing a fixed seed grid before knowing what sizes
 * would be needed, not adjacency conflicts).
 *
 * Once every demand has been tried, {@link GapFiller}, {@link PatchOpener},
 * {@link SeamWidener}, {@link PatchSizeEnforcer} and
 * {@link SeparatorOrganicizer} clean up what placement alone can't
 * guarantee: full coverage, no thin one-cell-wide spurs, no locally-pinched
 * seams between incompatible biomes even inside an otherwise large patch,
 * each patch's own minimum size, and — for forced separators between biome
 * pairs with no direct adjacency rule at all — an organic edge instead of a
 * straight wall.
 */
public final class RegionSolver {

    // How many candidate positions to generate relative to the budget's demand
    // count - enough slack that most demands have more than one shot at a legal
    // spot, without generating so many that most go unused. Placeholder, tune
    // from logged solve output like the grid constants in doc/ARCHITECTURE.md.
    private static final double POSITION_COUNT_SLACK = 1.5;

    private RegionSolver() {
    }

    public static RegionSolveResult solve(RegionSolveInput input) {
        List<PatchDemand> budget = BudgetPlanner.plan(input.graph(), input.gridConfig(), input.region(), input.worldSeed());
        List<PatchDemand> placementOrder = orderByPlacementPriority(budget, input.graph());
        List<MacroCellPos> positions = new ArrayList<>(
            SeedPlacer.placeSeeds(input.region(), input.gridConfig(), input.worldSeed(), spacingForDesiredCount(input.gridConfig(), budget.size()))
        );

        Map<MacroCellPos, BiomeId> assignment = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();

        for (PatchDemand demand : placementOrder) {
            GrowthEngine.GrowthAttempt attempt = tryPositions(input, demand, positions, assignment);
            if (attempt != null) {
                assignment.putAll(attempt.claimedCells());
            } else {
                warnings.add(
                    "Could not place " + demand.biomeId() + " (target " + demand.targetCells()
                        + " cells, min " + demand.minCells() + ") anywhere in the region — dropped, not forced or shrunk."
                );
            }
        }

        return new RegionSolveResult(cleanUp(assignment, input), List.copyOf(warnings), warnings.size());
    }

    /**
     * Placement order, not the budget's own largest-first order (that one only bounds
     * total demand to capacity — a separate concern). A biome with few legal partners
     * (e.g. badlands: only desert/plains) can get boxed out by whichever OTHER biomes
     * happen to claim the surrounding territory first, even when total space is fine —
     * placing the most constrained biomes first, while the most open territory is still
     * up for grabs, avoids that. Ties within the same constraint tier keep largest-first,
     * since that's still what gets a good size fit.
     */
    private static List<PatchDemand> orderByPlacementPriority(List<PatchDemand> budget, BiomeGraph graph) {
        Map<BiomeId, Long> legalPartnerCount = graph.nodes().stream()
            .map(BiomeNode::id)
            .collect(Collectors.toMap(id -> id, id -> graph.nodes().stream()
                .map(BiomeNode::id)
                .filter(other -> !other.equals(id) && graph.isAllowed(id, other))
                .count()));

        return budget.stream()
            .sorted(
                Comparator.<PatchDemand>comparingLong(demand -> legalPartnerCount.get(demand.biomeId()))
                    .thenComparing(Comparator.comparingInt(PatchDemand::targetCells).reversed())
            )
            .toList();
    }

    /**
     * Tries every remaining position, removing it once claimed so a later demand can't
     * reuse it. Positions already touching a legally-compatible committed cell go first —
     * clusters related biomes together (organic-looking, matches the design's adjacency
     * intent) and gives narrow-adjacency biomes their best shot at a legal spot, instead
     * of scattering into open territory that may end up surrounded by incompatible
     * neighbors by the time growth reaches the edges.
     */
    private static GrowthEngine.GrowthAttempt tryPositions(
        RegionSolveInput input, PatchDemand demand, List<MacroCellPos> positions, Map<MacroCellPos, BiomeId> committedSoFar
    ) {
        Map<MacroCellPos, BiomeId> externallyAssigned = mergeExternalContext(committedSoFar, input.fixedBoundaryCells());
        List<MacroCellPos> searchOrder = orderPositionsByCompatibleProximity(positions, demand.biomeId(), externallyAssigned, input.graph());

        for (MacroCellPos position : searchOrder) {
            GrowthEngine.GrowthAttempt attempt = GrowthEngine.attemptGrowth(
                position, demand.biomeId(), demand.minCells(), demand.targetCells(),
                input.region(), input.gridConfig(), input.graph(), externallyAssigned, input.worldSeed()
            );
            if (attempt.success()) {
                positions.remove(position);
                return attempt;
            }
        }
        return null;
    }

    private static List<MacroCellPos> orderPositionsByCompatibleProximity(
        List<MacroCellPos> positions, BiomeId demandBiome, Map<MacroCellPos, BiomeId> committed, BiomeGraph graph
    ) {
        List<MacroCellPos> nearCompatibleNeighbor = new ArrayList<>();
        List<MacroCellPos> rest = new ArrayList<>();
        for (MacroCellPos position : positions) {
            boolean touchesCompatible = position.orthogonalNeighbors().stream()
                .map(committed::get)
                .filter(Objects::nonNull)
                .anyMatch(neighborBiome -> graph.isAllowed(demandBiome, neighborBiome));
            (touchesCompatible ? nearCompatibleNeighbor : rest).add(position);
        }
        nearCompatibleNeighbor.addAll(rest);
        return nearCompatibleNeighbor;
    }

    /** Spacing that makes {@link SeedPlacer} generate roughly desiredCount positions (with some slack), not tied to a fixed constant. */
    private static int spacingForDesiredCount(MacroGridConfig grid, int desiredCount) {
        int sideLength = grid.regionSizeCellsPerSide();
        int positionsPerAxis = Math.max(1, (int) Math.ceil(Math.sqrt(desiredCount * POSITION_COUNT_SLACK)));
        int spacingCells = Math.max(1, sideLength / positionsPerAxis);
        return spacingCells * grid.macroCellSizeBlocks();
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

        GapFiller.fillGaps(workingAssignment, regionCellSet, input.graph(), input.worldSeed());
        PatchOpener.openThinProtrusions(workingAssignment, regionCellSet, input.graph(), input.worldSeed());
        // Re-enabled after the two prior "regressions" turned out to be measurement
        // artifacts, not real flaws - see doc/KNOWN_ISSUES.md. SeparatorWidener.widen
        // only ever REMOVES cells from a donor biome's set, and removing elements
        // from a set can only increase or hold steady its distance to another set,
        // never decrease it - so this pass cannot locally worsen any pair's
        // separation. The swath-wide "min distance between pairs, full sequential
        // solve" metric that flagged it as a regression twice is contaminated by
        // ordinary cross-region order-dependence (doc/KNOWN_ISSUES.md #4): enabling
        // ANY per-region pass shifts which boundary context downstream regions solve
        // against, which can move an unrelated pair's number with zero relation to
        // whether the change itself is sound. Confirmed: the already-shipped, known-
        // good PatchOpener tightening fails that exact same test too.
        SeamWidener.widenNarrowSeams(workingAssignment, regionCellSet, input.graph());
        PatchSizeEnforcer.enforceMinimumPatchSize(workingAssignment, regionCellSet, input.gridConfig(), input.graph());
        SeparatorOrganicizer.organicizeThinSeparators(workingAssignment, regionCellSet, input.gridConfig(), input.graph(), input.worldSeed());

        return Map.copyOf(workingAssignment.entrySet().stream()
            .filter(entry -> regionCellSet.contains(entry.getKey()))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
    }

    private static Map<MacroCellPos, BiomeId> mergeExternalContext(
        Map<MacroCellPos, BiomeId> committedSoFar, Map<MacroCellPos, BiomeId> fixedBoundaryCells
    ) {
        Map<MacroCellPos, BiomeId> merged = new LinkedHashMap<>(fixedBoundaryCells);
        merged.putAll(committedSoFar);
        return merged;
    }
}
