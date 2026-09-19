package com.mineutze.worldgen.reachability;

import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.coherence.RawRuleTable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Same joint CSP as {@link LocalClusterSolver}, plus a SIZE dimension via {@link
 * ClusterAreaMath}'s "self-adjacency as clustering" model - deliberately NOT the live
 * power-diagram mechanism (2026-09-17 user direction): this package proves properties of
 * the abstract rule GRAPH, not of the specific live algorithm (same standing as {@code
 * coherence} package's own independence rule) - an independent, simpler size model is the
 * RIGHT choice here, not a fallback.
 *
 * <p><b>MIN is actively pursued during search, not just checked afterward</b> - a real,
 * necessary fix (2026-09-17): a first version checked MIN only reactively, after a plain
 * one-candidate-per-face backtrack - against the real catalog, EVERY query failed, even
 * fully isolated ones, because plain backtracking never deliberately clustered enough
 * contiguous same-biome faces to reach any biome's own minimum before the surrounding
 * territory was fully (if arbitrarily) resolved. Real production avoids this via NEEDS-
 * DRIVEN self-adjacency ({@code SelfAdjacentPairing}) - recruiting exactly enough
 * contiguous same-biome neighbors to hit the minimum WHILE they are still free. {@link
 * #growToMinimum} reproduces that same idea independently: whenever a face's own cluster
 * starts below its biome's minimum, greedily recruit free, still-eligible neighbors into
 * the same biome (first legal one found each round, real MAX still enforced) until the
 * minimum is reached or genuinely no eligible neighbor remains - a real, reported failure,
 * not silently accepted.
 *
 * <p><b>Same-biome adjacency is always allowed here</b> - deliberately different from
 * {@link RawRuleTable#isLegal}'s unconditional X-X-illegal rule (right for {@code
 * coherence}'s own row substrate, wrong here - see {@link #identityConsistent}'s own doc).
 * Deliberately UNGATED by any face-count cap (no {@code selfAdjacencyLimit} restriction
 * like production's own mechanism) - the more permissive choice is right for a GRAPH-level
 * "can this even be validly filled" question, not an algorithm-level one; MAX area is the
 * only real cap on cluster growth.
 */
public final class SizeAwareLocalClusterSolver {

    private SizeAwareLocalClusterSolver() {
    }

    public record Solution(Map<AxialPos, BiomeId> assignment) {
    }

    public static Optional<Solution> solve(
        RawRuleTable table, Set<AxialPos> freeFaces, Function<AxialPos, Optional<BiomeId>> committedBiomeAt, double baselineFaceAreaBlocks2
    ) {
        return solve(table, freeFaces, committedBiomeAt, baselineFaceAreaBlocks2, Map.of());
    }

    /** @param forcedAssignment pins specific free faces to a specific target biome before search starts - same real, fully-checked contract as {@link LocalClusterSolver#solve(RawRuleTable, Set, Map, Map)}. */
    public static Optional<Solution> solve(
        RawRuleTable table, Set<AxialPos> freeFaces, Function<AxialPos, Optional<BiomeId>> committedBiomeAt, double baselineFaceAreaBlocks2,
        Map<AxialPos, BiomeId> forcedAssignment
    ) {
        List<AxialPos> order = LocalClusterSolver.bfsOrder(freeFaces);
        Map<AxialPos, BiomeId> assignment = new LinkedHashMap<>();
        boolean solved = backtrack(table, order, freeFaces, committedBiomeAt, forcedAssignment, assignment, baselineFaceAreaBlocks2);
        return solved ? Optional.of(new Solution(Map.copyOf(assignment))) : Optional.empty();
    }

    private static boolean backtrack(
        RawRuleTable table, List<AxialPos> order, Set<AxialPos> freeFaces, Function<AxialPos, Optional<BiomeId>> committedBiomeAt,
        Map<AxialPos, BiomeId> forcedAssignment, Map<AxialPos, BiomeId> assignment, double baselineFaceAreaBlocks2
    ) {
        AxialPos face = nextUnassigned(order, assignment);
        if (face == null) {
            return true;
        }
        BiomeId forced = forcedAssignment.get(face);
        for (BiomeId candidate : table.allBiomeIds()) {
            if (forced != null && !candidate.equals(forced)) {
                continue;
            }
            if (!identityConsistent(table, committedBiomeAt, assignment, face, candidate)) {
                continue;
            }
            if (!withinMaxClusterArea(table, committedBiomeAt, assignment, face, candidate, baselineFaceAreaBlocks2)) {
                continue;
            }
            assignment.put(face, candidate);
            Optional<List<AxialPos>> recruited = growToMinimum(table, freeFaces, committedBiomeAt, assignment, face, candidate, baselineFaceAreaBlocks2);
            if (recruited.isPresent()) {
                if (backtrack(table, order, freeFaces, committedBiomeAt, forcedAssignment, assignment, baselineFaceAreaBlocks2)) {
                    return true;
                }
                for (AxialPos recruit : recruited.get()) {
                    assignment.remove(recruit);
                }
            }
            assignment.remove(face);
        }
        return false;
    }

    private static AxialPos nextUnassigned(List<AxialPos> order, Map<AxialPos, BiomeId> assignment) {
        for (AxialPos face : order) {
            if (!assignment.containsKey(face)) {
                return face;
            }
        }
        return null;
    }

    /**
     * Real "needs-driven self-adjacency," independent implementation - see this class's own
     * doc. Recruits are written directly into {@code assignment}; on failure every recruit
     * made THIS call is undone before returning empty (the seed face itself is the caller's
     * own responsibility to undo, not touched here).
     */
    private static Optional<List<AxialPos>> growToMinimum(
        RawRuleTable table, Set<AxialPos> freeFaces, Function<AxialPos, Optional<BiomeId>> committedBiomeAt,
        Map<AxialPos, BiomeId> assignment, AxialPos seed, BiomeId biome, double baselineFaceAreaBlocks2
    ) {
        Set<AxialPos> cluster = new LinkedHashSet<>(ClusterAreaMath.clusterOf(seed, biome, assignment, committedBiomeAt));
        List<AxialPos> recruited = new ArrayList<>();
        int minWidthBlocks = table.sizeRangeOf(biome).minWidthBlocks();
        int maxWidthBlocks = table.sizeRangeOf(biome).maxWidthBlocks();

        Deque<AxialPos> frontier = new ArrayDeque<>();
        Set<AxialPos> queued = new HashSet<>(cluster);
        for (AxialPos member : cluster) {
            for (AxialPos neighbor : member.neighbors()) {
                if (queued.add(neighbor)) {
                    frontier.add(neighbor);
                }
            }
        }

        while (ClusterAreaMath.clusterWidthBlocks(cluster.size(), baselineFaceAreaBlocks2) < minWidthBlocks) {
            AxialPos recruit = null;
            while (!frontier.isEmpty()) {
                AxialPos candidate = frontier.poll();
                if (!freeFaces.contains(candidate) || assignment.containsKey(candidate)) {
                    continue; // permanently ineligible - fixed/external context, or already committed to something else this solve
                }
                if (!identityConsistent(table, committedBiomeAt, assignment, candidate, biome)) {
                    continue; // permanently ineligible - legality against its OTHER neighbors never improves
                }
                if (ClusterAreaMath.clusterWidthBlocks(cluster.size() + 1, baselineFaceAreaBlocks2) > maxWidthBlocks) {
                    continue; // permanently ineligible - cluster only grows from here, never shrinks
                }
                recruit = candidate;
                break;
            }
            if (recruit == null) {
                for (AxialPos alreadyRecruited : recruited) {
                    assignment.remove(alreadyRecruited);
                }
                return Optional.empty();
            }
            assignment.put(recruit, biome);
            recruited.add(recruit);
            cluster.add(recruit);
            for (AxialPos neighbor : recruit.neighbors()) {
                if (queued.add(neighbor)) {
                    frontier.add(neighbor);
                }
            }
        }
        return Optional.of(recruited);
    }

    /**
     * Deliberately DIFFERENT from {@link LocalClusterSolver}'s own identity check: {@link
     * RawRuleTable#isLegal} unconditionally forbids a biome bordering itself (X-X always
     * illegal, no exception - see that method's own doc), which is exactly right for {@code
     * coherence}'s row substrate (no clustering concept there, see that package's own doc)
     * but wrong HERE - same-biome adjacency IS this class's whole clustering mechanism (see
     * this class's own doc: "self-adjacency as clustering"), so it is always structurally
     * ALLOWED here regardless of {@code isLegal}'s general rule; only a DIFFERENT-biome pair
     * still needs real {@code RawRuleTable} legality.
     */
    private static boolean identityConsistent(
        RawRuleTable table, Function<AxialPos, Optional<BiomeId>> committedBiomeAt, Map<AxialPos, BiomeId> assignment, AxialPos face, BiomeId candidate
    ) {
        for (AxialPos neighbor : face.neighbors()) {
            BiomeId neighborValue = assignment.get(neighbor);
            if (neighborValue == null) {
                neighborValue = committedBiomeAt.apply(neighbor).orElse(null);
            }
            if (neighborValue != null && !candidate.equals(neighborValue) && !table.isLegal(candidate, neighborValue)) {
                return false;
            }
        }
        return true;
    }

    private static boolean withinMaxClusterArea(
        RawRuleTable table, Function<AxialPos, Optional<BiomeId>> committedBiomeAt, Map<AxialPos, BiomeId> assignment,
        AxialPos face, BiomeId candidate, double baselineFaceAreaBlocks2
    ) {
        int clusterFaceCount = ClusterAreaMath.clusterFaceCount(face, candidate, assignment, committedBiomeAt);
        double clusterWidthBlocks = ClusterAreaMath.clusterWidthBlocks(clusterFaceCount, baselineFaceAreaBlocks2);
        return clusterWidthBlocks <= table.sizeRangeOf(candidate).maxWidthBlocks();
    }
}
