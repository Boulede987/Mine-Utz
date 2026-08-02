package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.GrowthCohesionProfile;

import java.util.Comparator;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;

/**
 * Seed growth (see {@link GrowthEngine}) rarely tiles a region perfectly —
 * patches can leave gaps between them where neither neighbor's growth
 * reached. Grows every already-assigned patch simultaneously into the
 * unclaimed territory (multi-source BFS / discrete Voronoi): each gap cell
 * ends up owned by whichever patch's frontier reaches it first, subject to
 * the same legality ({@link GrowthEngine#isLegal}) and shape-identity
 * ({@link GrowthEngine#satisfiesCohesion}) checks {@link GrowthEngine}
 * itself uses. Any cell no patch can legally/cohesively reach (boxed
 * between mutually-incompatible neighbors with nothing else nearby, or
 * fully isolated) gets the universal filler — always legal by construction.
 *
 * Previously a per-cell majority vote among immediate neighbors only, no
 * lookahead, no shape check — a fundamentally different (and more myopic)
 * model than {@link GrowthEngine}'s for no real reason, and very plausibly
 * a source of the same thin/winding shapes {@link GrowthCohesionProfile}
 * was built to fix in ordinary growth: a gap boxed between two different
 * patches could flip which one "wins" cell-by-cell rather than being
 * claimed coherently by whichever is actually closest.
 */
final class GapFiller {

    // Same technique as GrowthEngine's own frontier jitter (JITTER_MAGNITUDE/SALT
    // there) — deterministic per (worldSeed, cell, biome), so competing patches at
    // equal distance resolve reproducibly instead of by insertion order, and the
    // resulting boundary reads as organic rather than a hard bias toward whichever
    // biome sorts first alphabetically.
    private static final double JITTER_MAGNITUDE = 12.0;
    private static final long JITTER_SALT = 0x6A9F_11E4L;

    private GapFiller() {
    }

    private record FillCandidate(MacroCellPos cell, BiomeId biome, int distance) {
    }

    static void fillGaps(Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet, BiomeGraph graph, long worldSeed) {
        PriorityQueue<FillCandidate> frontier = new PriorityQueue<>(candidateOrder(worldSeed));

        // Seed from every already-assigned cell, including ones outside regionCellSet
        // (fixedBoundaryCells, i.e. an already-solved neighbor region's edge) — its
        // biome is legitimate source context for cells just inside our own region,
        // same as GrowthEngine and the old majority-vote both already treated it.
        // addUnclaimedRegionNeighbors below still only ever pushes/claims cells
        // actually inside regionCellSet.
        for (Map.Entry<MacroCellPos, BiomeId> entry : assignment.entrySet()) {
            addUnclaimedRegionNeighbors(entry.getKey(), entry.getValue(), 1, assignment, regionCellSet, frontier);
        }

        while (!frontier.isEmpty()) {
            FillCandidate candidate = frontier.poll();
            MacroCellPos cell = candidate.cell();
            if (assignment.containsKey(cell)) {
                continue; // already claimed by a competing biome that reached it first
            }

            GrowthCohesionProfile cohesion = graph.node(candidate.biome()).cohesionProfile();
            boolean stillLegal = GrowthEngine.isLegal(cell, candidate.biome(), assignment, graph);
            boolean stillCohesive = GrowthEngine.satisfiesCohesion(cell, candidate.biome(), assignment, cohesion);
            if (!stillLegal || !stillCohesive) {
                // Legality can only get MORE restrictive as neighbors fill in, so an entry
                // valid when pushed can go stale by the time it's popped - recheck fresh
                // rather than trusting push-time state. Not a dead end: some other source
                // may still legally/cohesively reach this cell later, and if none ever
                // does, the final sweep below hands it to filler.
                continue;
            }

            assignment.put(cell, candidate.biome());
            addUnclaimedRegionNeighbors(cell, candidate.biome(), candidate.distance() + 1, assignment, regionCellSet, frontier);
        }

        regionCellSet.stream()
            .filter(cell -> !assignment.containsKey(cell))
            .forEach(cell -> assignment.put(cell, graph.firstUniversalFiller()));
    }

    private static void addUnclaimedRegionNeighbors(
        MacroCellPos cell, BiomeId biome, int distance, Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet, PriorityQueue<FillCandidate> frontier
    ) {
        for (MacroCellPos neighbor : cell.orthogonalNeighbors()) {
            if (regionCellSet.contains(neighbor) && !assignment.containsKey(neighbor)) {
                frontier.add(new FillCandidate(neighbor, biome, distance));
            }
        }
    }

    private static Comparator<FillCandidate> candidateOrder(long worldSeed) {
        return Comparator
            .comparingDouble((FillCandidate candidate) -> candidate.distance() + jitter(worldSeed, candidate.cell(), candidate.biome()))
            .thenComparingInt(candidate -> candidate.cell().x())
            .thenComparingInt(candidate -> candidate.cell().z())
            .thenComparing(candidate -> candidate.biome().toString());
    }

    /** Deterministic: same (worldSeed, cell, biome) always jitters the same way, so the fill stays reproducible. */
    private static double jitter(long worldSeed, MacroCellPos cell, BiomeId biome) {
        long hash = worldSeed ^ JITTER_SALT;
        hash = hash * 31 + cell.x();
        hash = hash * 31 + cell.z();
        hash = hash * 31 + biome.toString().hashCode();
        return new Random(hash).nextDouble() * JITTER_MAGNITUDE;
    }
}
