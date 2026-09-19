package com.mineutze.worldgen.reachability;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.EdgeData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.NodeData;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.coherence.RawRuleTable;
import com.mineutze.worldgen.regionsolve.topology.HexGeometry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SizeAwareLocalClusterSolverTest {

    private static final BiomeId SMALL_MAX = new BiomeId("test", "small_max");
    private static final BiomeId ROOMY = new BiomeId("test", "roomy");

    // Calibrated so a LONE face's own equivalent width (see ClusterAreaMath.clusterWidthBlocks)
    // is exactly 100 - real, necessary fix (2026-09-17): width grows with the SQUARE ROOT of
    // face count, not linearly, so cluster widths here follow width(N) = 100*sqrt(N): 1 face
    // = 100, 2 faces = ~141.42, 3 faces = ~173.21. Test min/max values below are chosen
    // relative to THIS real formula, not hand-picked area numbers (that was the original bug).
    private static final double BASELINE_AREA = HexGeometry.areaForCircumradius(HexGeometry.circumradiusForWidth(100));

    /** SMALL_MAX's own max (120) fits exactly 1 face (width 100) but not 2 (width ~141.42) - a second SMALL_MAX face adjacent to it must be rejected. ROOMY has plenty of room. SMALL_MAX-ROOMY legal (only cross-biome rule needed - same-biome is always allowed, see SizeAwareLocalClusterSolver's own doc). */
    private static RawRuleTable table() {
        List<NodeData> nodes = List.of(
            new NodeData(SMALL_MAX.namespace(), SMALL_MAX.path(), 1, 120, Integer.MAX_VALUE, List.of()),
            new NodeData(ROOMY.namespace(), ROOMY.path(), 1, 100_000, Integer.MAX_VALUE, List.of())
        );
        List<EdgeData> edges = List.of(new EdgeData(SMALL_MAX.namespace(), SMALL_MAX.path(), ROOMY.namespace(), ROOMY.path(), 10));
        return RawRuleTable.from(new BiomeGraphData(nodes, edges));
    }

    private static Function<AxialPos, Optional<BiomeId>> noCommittedContext() {
        return pos -> Optional.empty();
    }

    @Test
    void sameBiomeAdjacencyIsAllowedWhenItFitsUnderMax() {
        RawRuleTable table = table();
        AxialPos p0 = new AxialPos(0, 0);
        AxialPos p1 = p0.neighbor(0);
        // ROOMY's max easily covers 2 faces - forcing both to ROOMY must succeed.
        Optional<SizeAwareLocalClusterSolver.Solution> solution = SizeAwareLocalClusterSolver.solve(
            table, java.util.Set.of(p0, p1), noCommittedContext(), BASELINE_AREA, Map.of(p0, ROOMY, p1, ROOMY)
        );
        assertTrue(solution.isPresent());
        assertEquals(ROOMY, solution.get().assignment().get(p0));
        assertEquals(ROOMY, solution.get().assignment().get(p1));
    }

    @Test
    void forcingBothFacesPastMaxClusterWidthFails() {
        RawRuleTable table = table();
        AxialPos p0 = new AxialPos(0, 0);
        AxialPos p1 = p0.neighbor(0);
        // SMALL_MAX's max (120) fits only 1 face - forcing BOTH adjacent faces to it (width ~141.42) exceeds max, no substitution possible (forced).
        Optional<SizeAwareLocalClusterSolver.Solution> solution = SizeAwareLocalClusterSolver.solve(
            table, java.util.Set.of(p0, p1), noCommittedContext(), BASELINE_AREA, Map.of(p0, SMALL_MAX, p1, SMALL_MAX)
        );
        assertTrue(solution.isEmpty());
    }

    @Test
    void unforcedSearchAvoidsGrowingASmallMaxBiomeClusterPastItsOwnLimit() {
        RawRuleTable table = table();
        AxialPos p0 = new AxialPos(0, 0);
        AxialPos p1 = p0.neighbor(0);
        // p0 forced to SMALL_MAX (fits alone); p1 left free - must NOT also become SMALL_MAX (would exceed max jointly), must fall back to ROOMY.
        Optional<SizeAwareLocalClusterSolver.Solution> solution = SizeAwareLocalClusterSolver.solve(
            table, java.util.Set.of(p0, p1), noCommittedContext(), BASELINE_AREA, Map.of(p0, SMALL_MAX)
        );
        assertTrue(solution.isPresent());
        assertEquals(SMALL_MAX, solution.get().assignment().get(p0));
        assertEquals(ROOMY, solution.get().assignment().get(p1));
    }

    private static final BiomeId NEEDS_THREE = new BiomeId("test", "needs_three");
    private static final BiomeId FILLER = new BiomeId("test", "filler");

    /** NEEDS_THREE's own minimum (150) needs 3 faces (width ~173.21) - 2 faces (width ~141.42) fall short, needs real recruitment. */
    private static RawRuleTable needsThreeTable() {
        List<NodeData> nodes = List.of(
            new NodeData(NEEDS_THREE.namespace(), NEEDS_THREE.path(), 150, 100_000, Integer.MAX_VALUE, List.of()),
            new NodeData(FILLER.namespace(), FILLER.path(), 1, 100_000, Integer.MAX_VALUE, List.of())
        );
        List<EdgeData> edges = List.of(new EdgeData(NEEDS_THREE.namespace(), NEEDS_THREE.path(), FILLER.namespace(), FILLER.path(), 10));
        return RawRuleTable.from(new BiomeGraphData(nodes, edges));
    }

    @Test
    void growToMinimumRecruitsEnoughFreeNeighborsToReachMinimum() {
        RawRuleTable table = needsThreeTable();
        AxialPos p0 = new AxialPos(0, 0);
        AxialPos p1 = p0.neighbor(0);
        AxialPos p2 = p1.neighbor(0);
        // 3 free faces in a line - exactly enough (width ~173.21) to reach NEEDS_THREE's 150 minimum; 2 alone (~141.42) would not.
        Optional<SizeAwareLocalClusterSolver.Solution> solution = SizeAwareLocalClusterSolver.solve(
            table, java.util.Set.of(p0, p1, p2), noCommittedContext(), BASELINE_AREA, Map.of(p0, NEEDS_THREE)
        );
        assertTrue(solution.isPresent(), "with 3 free faces available, recruitment should reach the 150 minimum");
        long needsThreeCount = solution.get().assignment().values().stream().filter(NEEDS_THREE::equals).count();
        assertEquals(3, needsThreeCount, "all 3 free faces should have been recruited into the same NEEDS_THREE cluster");
    }

    @Test
    void growToMinimumFailsWhenNotEnoughEligibleFreeFacesExist() {
        RawRuleTable table = needsThreeTable();
        AxialPos p0 = new AxialPos(0, 0);
        AxialPos p1 = p0.neighbor(0);
        // Only 2 free faces total (width ~141.42) - can never reach the 150 minimum, and NEEDS_THREE is forced (no fallback candidate).
        Optional<SizeAwareLocalClusterSolver.Solution> solution = SizeAwareLocalClusterSolver.solve(
            table, java.util.Set.of(p0, p1), noCommittedContext(), BASELINE_AREA, Map.of(p0, NEEDS_THREE)
        );
        assertTrue(solution.isEmpty(), "with only 2 free faces available, NEEDS_THREE's own minimum can never be reached");
    }
}
