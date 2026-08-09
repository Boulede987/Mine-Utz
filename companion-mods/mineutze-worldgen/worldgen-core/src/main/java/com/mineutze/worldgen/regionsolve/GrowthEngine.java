package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.GrowthCohesionProfile;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Grows one candidate biome outward from a seed cell by flood-fill,
 * claiming only macro-cells inside the region that are still free, legal
 * (the candidate must be allowed, per the BiomeGraph, next to every
 * already-assigned cell outside the growing patch itself), and cohesive
 * enough per the candidate's own {@link GrowthCohesionProfile} — see
 * {@link #satisfiesCohesion}. Frontier order is nearest-distance-to-seed
 * plus a small deterministic jitter, so the outline is an irregular blob
 * rather than a perfect circle/diamond.
 *
 * Does not backtrack or pick a different candidate — that's
 * {@link RegionSolver}'s job. This just answers: "can this one candidate
 * grow to at least its minimum size here, and if so, which cells does it
 * claim?"
 */
public final class GrowthEngine {

    // Placeholder, tune from how organic patches actually look in-game (same pattern
    // as the grid constants in doc/ARCHITECTURE.md). Scaled relative to how much
    // distanceSquared changes between adjacent "rings" from a seed, so it's big enough
    // to visibly reorder which ring claims a cell next, not just break exact ties.
    private static final double JITTER_MAGNITUDE = 12.0;
    private static final long JITTER_SALT = 0x610B_B10BL;

    private GrowthEngine() {
    }

    public record GrowthAttempt(boolean success, Map<MacroCellPos, BiomeId> claimedCells) {
    }

    public static GrowthAttempt attemptGrowth(
        MacroCellPos seedCell,
        BiomeId candidate,
        int minCells,
        int targetCells,
        RegionPos region,
        MacroGridConfig grid,
        BiomeGraph graph,
        Map<MacroCellPos, BiomeId> externallyAssigned,
        long worldSeed
    ) {
        Set<MacroCellPos> regionCells = Set.copyOf(grid.cellsInRegion(region, worldSeed));

        if (externallyAssigned.containsKey(seedCell) || !isLegal(seedCell, candidate, externallyAssigned, graph)) {
            return new GrowthAttempt(false, Map.of());
        }

        Map<MacroCellPos, BiomeId> claimed = new LinkedHashMap<>();
        claimed.put(seedCell, candidate);

        GrowthCohesionProfile cohesion = graph.node(candidate).cohesionProfile();
        TreeSet<MacroCellPos> frontier = new TreeSet<>(frontierOrder(seedCell, worldSeed));
        addLegalUnclaimedNeighbors(seedCell, seedCell, candidate, regionCells, externallyAssigned, claimed, frontier, graph, cohesion);

        while (claimed.size() < targetCells && !frontier.isEmpty()) {
            MacroCellPos next = frontier.pollFirst();
            claimed.put(next, candidate);
            addLegalUnclaimedNeighbors(next, seedCell, candidate, regionCells, externallyAssigned, claimed, frontier, graph, cohesion);
        }

        boolean success = claimed.size() >= minCells;
        return new GrowthAttempt(success, success ? Map.copyOf(claimed) : Map.of());
    }

    private static void addLegalUnclaimedNeighbors(
        MacroCellPos cell,
        MacroCellPos seedCell,
        BiomeId candidate,
        Set<MacroCellPos> regionCells,
        Map<MacroCellPos, BiomeId> externallyAssigned,
        Map<MacroCellPos, BiomeId> claimed,
        TreeSet<MacroCellPos> frontier,
        BiomeGraph graph,
        GrowthCohesionProfile cohesion
    ) {
        for (MacroCellPos neighbor : cell.orthogonalNeighbors()) {
            if (regionCells.contains(neighbor)
                && !claimed.containsKey(neighbor)
                && !externallyAssigned.containsKey(neighbor)
                && isLegal(neighbor, candidate, externallyAssigned, graph)
                && satisfiesCohesionDuringGrowth(neighbor, seedCell, candidate, claimed, cohesion)) {
                frontier.add(neighbor);
            }
        }
    }

    /**
     * {@link #satisfiesCohesion} exempts the seed's own immediate ring (distance-
     * squared &lt;= 1: the seed itself or orthogonally touching it): a brand-new seed
     * has zero same-biome neighbors by definition, so any {@code minSameBiomeNeighbors}
     * above 1 would make growth impossible from cell one without this bootstrap.
     * Beyond that ring, there's enough of a core shape for the check to be meaningful.
     * Only {@link GrowthEngine}'s own single-seed flood-fill needs this — {@link
     * GapFiller}'s multi-source fill extends already-large existing patches, which
     * never has a bootstrap problem to begin with (see {@link #satisfiesCohesion}'s
     * own doc for why), so it calls that directly.
     */
    private static boolean satisfiesCohesionDuringGrowth(
        MacroCellPos candidate, MacroCellPos seedCell, BiomeId biome, Map<MacroCellPos, BiomeId> assignment, GrowthCohesionProfile cohesion
    ) {
        return candidate.distanceSquaredTo(seedCell) <= 1 || satisfiesCohesion(candidate, biome, assignment, cohesion);
    }

    /**
     * A biome's shape identity (see {@link GrowthCohesionProfile}'s own doc) — does
     * {@code biome} have enough already-assigned support around {@code candidate} to
     * grow there. Independent of adjacency legality ({@link #isLegal}), which is a
     * separate concern against OTHER biomes; this only looks at cells already equal
     * to {@code biome} itself. Package-visible: {@link GapFiller}'s multi-source fill
     * reuses this exact check, unlike {@link #isLegal} it never needs the seed-ring
     * bootstrap exemption {@link #satisfiesCohesionDuringGrowth} adds, since gap-fill
     * only ever extends an already-established (non-trivial-size) patch.
     *
     * The same-biome neighbor count is over the full 8-cell Moore neighborhood
     * (orthogonal + diagonal), not just the 4 orthogonal cells — a solid, convex
     * blob's exterior boundary cell only ever touches ONE already-claimed cell
     * orthogonally (a fact of 4-connectivity grids, not an edge case), so any
     * {@code minSameBiomeNeighbors} above 1 checked orthogonal-only makes growth
     * dead-end after the seed's ring no matter the shape — discovered the hard way
     * via the export CLI turning solid filler-only once a 2-neighbor profile was
     * tried. Diagonal-inclusive counting actually measures thickness: a true
     * 1-wide corridor cell still touches only 1 Moore-neighbor (correctly
     * blocked), a 2-wide strip touches 2 (correctly allowed).
     */
    static boolean satisfiesCohesion(
        MacroCellPos candidate, BiomeId biome, Map<MacroCellPos, BiomeId> assignment, GrowthCohesionProfile cohesion
    ) {
        long sameBiomeNeighbors = Stream.concat(candidate.orthogonalNeighbors().stream(), candidate.diagonalNeighbors().stream())
            .filter(neighbor -> biome.equals(assignment.get(neighbor))).count();
        if (sameBiomeNeighbors < cohesion.minSameBiomeNeighbors()) {
            return false;
        }
        return cohesion.directionalRunMajor() == 0 || satisfiesDirectionalRun(candidate, biome, assignment, cohesion);
    }

    /** Contiguous same-biome run through the candidate, required >= major along one axis and >= minor along the other (either assignment). */
    private static boolean satisfiesDirectionalRun(MacroCellPos candidate, BiomeId biome, Map<MacroCellPos, BiomeId> assignment, GrowthCohesionProfile cohesion) {
        int xRun = 1 + contiguousRun(candidate, biome, assignment, 1, 0) + contiguousRun(candidate, biome, assignment, -1, 0);
        int zRun = 1 + contiguousRun(candidate, biome, assignment, 0, 1) + contiguousRun(candidate, biome, assignment, 0, -1);
        return (xRun >= cohesion.directionalRunMajor() && zRun >= cohesion.directionalRunMinor())
            || (zRun >= cohesion.directionalRunMajor() && xRun >= cohesion.directionalRunMinor());
    }

    private static int contiguousRun(MacroCellPos from, BiomeId biome, Map<MacroCellPos, BiomeId> assignment, int stepX, int stepZ) {
        int count = 0;
        int x = from.x() + stepX;
        int z = from.z() + stepZ;
        while (biome.equals(assignment.get(new MacroCellPos(x, z)))) {
            count++;
            x += stepX;
            z += stepZ;
        }
        return count;
    }

    /**
     * Candidate is legal at this cell if it's allowed next to every
     * already-assigned neighbor outside the growing patch. Package-visible:
     * {@link RegionSolver}'s gap-fill pass reuses this exact check.
     */
    static boolean isLegal(
        MacroCellPos cell,
        BiomeId candidate,
        Map<MacroCellPos, BiomeId> externallyAssigned,
        BiomeGraph graph
    ) {
        for (MacroCellPos neighbor : cell.orthogonalNeighbors()) {
            BiomeId neighborBiome = externallyAssigned.get(neighbor);
            if (neighborBiome != null && !graph.isAllowed(candidate, neighborBiome)) {
                return false;
            }
        }
        return true;
    }

    /** Package-visible: {@code GeometryRealization}'s interleaved multi-patch growth reuses this exact ordering, one cell per patch per round instead of one patch grown to completion at a time. */
    static Comparator<MacroCellPos> frontierOrder(MacroCellPos seedCell, long worldSeed) {
        return Comparator
            .comparingDouble((MacroCellPos cell) -> cell.distanceSquaredTo(seedCell) + jitter(worldSeed, seedCell, cell))
            .thenComparingInt(MacroCellPos::x)
            .thenComparingInt(MacroCellPos::z);
    }

    /** Deterministic: same (worldSeed, seedCell, cell) always jitters the same way, so growth stays reproducible. */
    private static double jitter(long worldSeed, MacroCellPos seedCell, MacroCellPos cell) {
        long hash = worldSeed ^ JITTER_SALT;
        hash = hash * 31 + seedCell.x();
        hash = hash * 31 + seedCell.z();
        hash = hash * 31 + cell.x();
        hash = hash * 31 + cell.z();
        return new Random(hash).nextDouble() * JITTER_MAGNITUDE;
    }
}
