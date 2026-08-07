package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.GrowthCohesionProfile;
import com.mineutze.worldgen.regionsolve.topology.SeedGraph;
import com.mineutze.worldgen.regionsolve.topology.SeedGraphNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Realizes geometry from a topology already fixed by {@link
 * com.mineutze.worldgen.regionsolve.topology.CspIdentityAssignment}: every
 * free {@link SeedGraphNode} grows outward toward its assigned identity's
 * target size, all patches interleaved one cell per turn (priority = furthest
 * below target) rather than one patch grown to completion before the next
 * starts — a genuinely different growth discipline from {@link GrowthEngine}'s
 * own single-patch-at-a-time flow, though it reuses {@link GrowthEngine}'s
 * legality/cohesion/frontier-ordering building blocks directly. Whatever
 * territory no patch's growth reached falls to {@link GapFiller#fillGaps},
 * unchanged. See doc/Modelisation/ActivityDiagram/geometry-realization.puml.
 */
public final class GeometryRealization {

    private GeometryRealization() {
    }

    public static Map<MacroCellPos, BiomeId> realize(SeedGraph seedGraph, Map<Integer, BiomeId> assignedIdentities, RegionSolveInput input) {
        Set<MacroCellPos> regionCells = Set.copyOf(input.gridConfig().cellsInRegion(input.region()));
        Map<MacroCellPos, BiomeId> workingAssignment = new LinkedHashMap<>(input.fixedBoundaryCells());

        List<PatchGrowthState> patches = seedPatches(seedGraph, assignedIdentities, input, workingAssignment, regionCells);
        growPatchesToTarget(patches, workingAssignment, regionCells, input.graph());

        GapFiller.fillGaps(workingAssignment, regionCells, input.gridConfig(), input.graph(), input.worldSeed());

        return workingAssignment.entrySet().stream()
            .filter(entry -> regionCells.contains(entry.getKey()))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static List<PatchGrowthState> seedPatches(
        SeedGraph seedGraph,
        Map<Integer, BiomeId> assignedIdentities,
        RegionSolveInput input,
        Map<MacroCellPos, BiomeId> workingAssignment,
        Set<MacroCellPos> regionCells
    ) {
        List<PatchGrowthState> patches = new ArrayList<>();
        for (SeedGraphNode node : seedGraph.nodes()) {
            if (node.fixed()) {
                continue;
            }
            BiomeId biome = requireAssignment(assignedIdentities, node);
            MacroCellPos seedCell = node.position();

            boolean seedSpotUsable = regionCells.contains(seedCell)
                && !workingAssignment.containsKey(seedCell)
                && GrowthEngine.isLegal(seedCell, biome, workingAssignment, input.graph());
            if (!seedSpotUsable) {
                continue; // GapFiller's sweep reclaims this territory from a neighbor instead
            }

            int targetCells = input.gridConfig().cellCountForWidthBlocks(input.graph().node(biome).sizeRange().maxWidthBlocks());
            GrowthCohesionProfile cohesion = input.graph().node(biome).cohesionProfile();
            PatchGrowthState patch = new PatchGrowthState(seedCell, biome, targetCells, cohesion, input.worldSeed());

            workingAssignment.put(seedCell, biome);
            pushLegalFrontierNeighbors(patch, seedCell, workingAssignment, regionCells, input.graph());
            patches.add(patch);
        }
        return patches;
    }

    private static BiomeId requireAssignment(Map<Integer, BiomeId> assignedIdentities, SeedGraphNode node) {
        BiomeId biome = assignedIdentities.get(node.id());
        if (biome == null) {
            throw new IllegalArgumentException("No CSP assignment for free SeedGraphNode " + node.id());
        }
        return biome;
    }

    private static void growPatchesToTarget(List<PatchGrowthState> patches, Map<MacroCellPos, BiomeId> workingAssignment, Set<MacroCellPos> regionCells, BiomeGraph graph) {
        while (true) {
            Optional<PatchGrowthState> next = patches.stream()
                .filter(patch -> patch.belowTarget() && patch.hasClaimableFrontier())
                .max(patchPriorityOrder());
            if (next.isEmpty()) {
                return;
            }
            claimNextValidFrontierCell(next.get(), workingAssignment, regionCells, graph);
        }
    }

    /** Furthest below target goes first; ties break on seed position for determinism. */
    private static Comparator<PatchGrowthState> patchPriorityOrder() {
        return Comparator.<PatchGrowthState>comparingInt(PatchGrowthState::remainingGap)
            .thenComparing(patch -> patch.seedCell, Comparator.comparingInt(MacroCellPos::x).thenComparingInt(MacroCellPos::z));
    }

    /**
     * Pops the patch's frontier until it finds a cell that's still unclaimed,
     * legal, and cohesive right now — a cell valid when it was pushed can go
     * stale once a competing patch claims something nearby, same "recheck
     * fresh" principle {@link GapFiller}'s own fill pass uses. Drains the
     * whole frontier without claiming anything if every entry has gone stale
     * — that's fine, it just makes this patch permanently ineligible next
     * round via {@link PatchGrowthState#hasClaimableFrontier}, not a stall.
     */
    private static void claimNextValidFrontierCell(PatchGrowthState patch, Map<MacroCellPos, BiomeId> workingAssignment, Set<MacroCellPos> regionCells, BiomeGraph graph) {
        while (!patch.frontier.isEmpty()) {
            MacroCellPos cell = patch.frontier.pollFirst();
            if (workingAssignment.containsKey(cell) || !GrowthEngine.isLegal(cell, patch.biome, workingAssignment, graph) || !satisfiesSeedBootstrapCohesion(cell, patch, workingAssignment)) {
                continue;
            }
            patch.claimedCount++;
            workingAssignment.put(cell, patch.biome);
            pushLegalFrontierNeighbors(patch, cell, workingAssignment, regionCells, graph);
            return;
        }
    }

    private static void pushLegalFrontierNeighbors(PatchGrowthState patch, MacroCellPos cell, Map<MacroCellPos, BiomeId> workingAssignment, Set<MacroCellPos> regionCells, BiomeGraph graph) {
        for (MacroCellPos neighbor : cell.orthogonalNeighbors()) {
            if (regionCells.contains(neighbor)
                && !workingAssignment.containsKey(neighbor)
                && GrowthEngine.isLegal(neighbor, patch.biome, workingAssignment, graph)
                && satisfiesSeedBootstrapCohesion(neighbor, patch, workingAssignment)) {
                patch.frontier.add(neighbor);
            }
        }
    }

    /** Exempts the seed's own immediate ring, same reason as {@link GrowthEngine}'s own bootstrap exemption: a brand-new seed has zero same-biome neighbors by definition. */
    private static boolean satisfiesSeedBootstrapCohesion(MacroCellPos candidate, PatchGrowthState patch, Map<MacroCellPos, BiomeId> workingAssignment) {
        return candidate.distanceSquaredTo(patch.seedCell) <= 1 || GrowthEngine.satisfiesCohesion(candidate, patch.biome, workingAssignment, patch.cohesion);
    }

    private static final class PatchGrowthState {
        final MacroCellPos seedCell;
        final BiomeId biome;
        final int targetCells;
        final GrowthCohesionProfile cohesion;
        final TreeSet<MacroCellPos> frontier;
        int claimedCount = 1; // the seed cell itself

        PatchGrowthState(MacroCellPos seedCell, BiomeId biome, int targetCells, GrowthCohesionProfile cohesion, long worldSeed) {
            this.seedCell = seedCell;
            this.biome = biome;
            this.targetCells = targetCells;
            this.cohesion = cohesion;
            this.frontier = new TreeSet<>(GrowthEngine.frontierOrder(seedCell, worldSeed));
        }

        boolean belowTarget() {
            return claimedCount < targetCells;
        }

        boolean hasClaimableFrontier() {
            return !frontier.isEmpty();
        }

        int remainingGap() {
            return targetCells - claimedCount;
        }
    }
}
