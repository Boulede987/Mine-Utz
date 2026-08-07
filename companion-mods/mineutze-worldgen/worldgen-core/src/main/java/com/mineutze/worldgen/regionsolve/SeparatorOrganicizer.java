package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Some biome pairs have no direct adjacency rule at all (e.g. two "climate
 * clusters" that only ever touch through a shared filler biome) — wherever
 * those clusters end up spatially adjacent, {@link GapFiller} has no legal
 * choice but to fill the entire shared boundary with the one biome that's
 * legal on both sides. That boundary is already at or
 * above its own minimum size (see {@link PatchSizeEnforcer}), so nothing
 * upstream flags it — but a straight, cell-wide wall running the length of
 * the region is exactly the "ugly seam" this design set out to avoid, just
 * arrived at honestly through the graph's own rules instead of a placement
 * bug.
 *
 * This pass finds separator patches with an elongated bounding box and
 * roughens their edges: for each boundary cell, deterministically (same
 * jitter technique as {@link GrowthEngine}'s organic patch outlines) tries
 * to steal one adjacent cell from whichever flanking patch is touching it.
 * A steal only happens if it stays legal, doesn't cut the donor patch into
 * two pieces, and doesn't drop the donor below its own declared minimum
 * size — the same guarantees {@link SeparatorWidener} enforces, reused
 * directly. Purely cosmetic: never runs before every correctness pass
 * above it, and any cell it can't legally claim is simply left alone.
 */
final class SeparatorOrganicizer {

    private static final double ASPECT_RATIO_THRESHOLD = 3.0;
    private static final double BULGE_PROBABILITY = 0.35;
    private static final long BULGE_SALT = 0x0BB1_6EEDL;

    private SeparatorOrganicizer() {
    }

    static void organicizeThinSeparators(
        Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet, MacroGridConfig grid, BiomeGraph graph, long worldSeed
    ) {
        for (Set<MacroCellPos> component : ConnectedComponents.find(assignment, regionCellSet)) {
            if (isElongated(component) && isRequiredSeparator(component, assignment, graph)) {
                bulge(component, assignment, regionCellSet, grid, graph, worldSeed);
            }
        }
    }

    /**
     * Only a patch that's actually bridging two mutually-incompatible neighbors is a
     * forced separator (same concept {@link PatchSizeEnforcer} uses to decide whether a
     * merge is even possible). A patch that merely happens to run in a straight line
     * because it borders one of those bridges — e.g. the flanking biome's own edge along
     * a long wall — is NOT one, and must never bulge into the actual separator: eating
     * cells out of a 1-wide wall from both sides at once can isolate a middle cell even
     * though each individual steal passes its own connectivity check.
     */
    private static boolean isRequiredSeparator(Set<MacroCellPos> component, Map<MacroCellPos, BiomeId> assignment, BiomeGraph graph) {
        Set<BiomeId> neighborBiomes = new HashSet<>();
        for (MacroCellPos cell : component) {
            for (MacroCellPos neighbor : cell.orthogonalNeighbors()) {
                BiomeId neighborBiome = assignment.get(neighbor);
                if (neighborBiome != null && !component.contains(neighbor)) {
                    neighborBiomes.add(neighborBiome);
                }
            }
        }
        for (BiomeId a : neighborBiomes) {
            for (BiomeId b : neighborBiomes) {
                if (!a.equals(b) && !graph.isAllowed(a, b)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isElongated(Set<MacroCellPos> component) {
        int minX = component.stream().mapToInt(MacroCellPos::x).min().orElseThrow();
        int maxX = component.stream().mapToInt(MacroCellPos::x).max().orElseThrow();
        int minZ = component.stream().mapToInt(MacroCellPos::z).min().orElseThrow();
        int maxZ = component.stream().mapToInt(MacroCellPos::z).max().orElseThrow();
        int longSide = Math.max(maxX - minX + 1, maxZ - minZ + 1);
        int shortSide = Math.min(maxX - minX + 1, maxZ - minZ + 1);
        return (double) longSide / shortSide >= ASPECT_RATIO_THRESHOLD;
    }

    private static void bulge(
        Set<MacroCellPos> component, Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet,
        MacroGridConfig grid, BiomeGraph graph, long worldSeed
    ) {
        BiomeId separatorBiome = assignment.get(component.iterator().next());
        List<MacroCellPos> ordered = component.stream()
            .sorted(Comparator.comparingInt(MacroCellPos::x).thenComparingInt(MacroCellPos::z))
            .toList();

        for (MacroCellPos cell : ordered) {
            for (MacroCellPos neighbor : cell.orthogonalNeighbors()) {
                if (!regionCellSet.contains(neighbor) || component.contains(neighbor)) {
                    continue;
                }
                BiomeId donorBiome = assignment.get(neighbor);
                if (donorBiome == null || donorBiome.equals(separatorBiome) || !shouldBulge(worldSeed, cell, neighbor)) {
                    continue;
                }
                if (!GrowthEngine.isLegal(neighbor, separatorBiome, assignment, graph)) {
                    continue;
                }
                if (!SeparatorWidener.stealingPreservesDonorConnectivity(neighbor, assignment, regionCellSet, Set.of(neighbor))) {
                    continue;
                }
                if (donorComponentSizeWithout(neighbor, donorBiome, assignment, regionCellSet) < minCellsFor(donorBiome, grid, graph)) {
                    continue;
                }
                assignment.put(neighbor, separatorBiome);
            }
        }
    }

    /** Deterministic: same (worldSeed, from, to) always decides the same way, so the result stays reproducible. */
    private static boolean shouldBulge(long worldSeed, MacroCellPos from, MacroCellPos to) {
        long hash = worldSeed ^ BULGE_SALT;
        hash = hash * 31 + from.x();
        hash = hash * 31 + from.z();
        hash = hash * 31 + to.x();
        hash = hash * 31 + to.z();
        return new Random(hash).nextDouble() < BULGE_PROBABILITY;
    }

    /** Package-visible: {@link GapFiller}'s dead-end fallback reuses this exact check when considering whether reassigning a neighbor is safe. */
    static int minCellsFor(BiomeId biome, MacroGridConfig grid, BiomeGraph graph) {
        return grid.cellCountForWidthBlocks(graph.node(biome).sizeRange().minWidthBlocks());
    }

    /** Size the donor patch would have with {@code stolen} removed — computed fresh so it reflects steals already applied earlier in this pass. Package-visible: see {@link #minCellsFor}. */
    static int donorComponentSizeWithout(
        MacroCellPos stolen, BiomeId donorBiome, Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet
    ) {
        List<MacroCellPos> starts = stolen.orthogonalNeighbors().stream()
            .filter(n -> regionCellSet.contains(n) && donorBiome.equals(assignment.get(n)))
            .toList();
        if (starts.isEmpty()) {
            return 0;
        }

        Set<MacroCellPos> visited = new HashSet<>();
        Deque<MacroCellPos> stack = new ArrayDeque<>(List.of(starts.get(0)));
        visited.add(starts.get(0));
        while (!stack.isEmpty()) {
            MacroCellPos current = stack.pop();
            for (MacroCellPos neighbor : current.orthogonalNeighbors()) {
                if (regionCellSet.contains(neighbor) && !neighbor.equals(stolen) && donorBiome.equals(assignment.get(neighbor)) && visited.add(neighbor)) {
                    stack.push(neighbor);
                }
            }
        }
        return visited.size();
    }
}
