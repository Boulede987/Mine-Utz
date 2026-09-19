package com.mineutze.worldgen.reachability;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.EdgeData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.NodeData;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.coherence.RawRuleTable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalClusterSolverTest {

    private static final BiomeId A = new BiomeId("test", "a");
    private static final BiomeId B = new BiomeId("test", "b");

    private static RawRuleTable twoBiomeTable() {
        List<NodeData> nodes = List.of(
            new NodeData(A.namespace(), A.path(), 100, 200, Integer.MAX_VALUE, List.of()),
            new NodeData(B.namespace(), B.path(), 100, 200, Integer.MAX_VALUE, List.of())
        );
        List<EdgeData> edges = List.of(new EdgeData(A.namespace(), A.path(), B.namespace(), B.path(), 10));
        return RawRuleTable.from(new BiomeGraphData(nodes, edges));
    }

    @Test
    void twoAdjacentFreeFacesSolveWithTheOnlyLegalPair() {
        RawRuleTable table = twoBiomeTable();
        AxialPos p1 = new AxialPos(0, 0);
        AxialPos p2 = new AxialPos(1, 0);
        Optional<Map<AxialPos, BiomeId>> solution = LocalClusterSolver.solve(table, Set.of(p1, p2), Map.of());
        assertTrue(solution.isPresent());
        assertTrue(!solution.get().get(p1).equals(solution.get().get(p2)), "adjacent faces must get different biomes when only a cross-pair is legal");
    }

    @Test
    void threeMutuallyAdjacentFreeFacesAreUnsatWithOnlyTwoBiomes() {
        // Real hex-mesh vertex-triple shape: 3 faces sharing a vertex are all mutually
        // adjacent (see AxialPosTest.consecutiveNeighborsAreMutuallyAdjacent). With only 2
        // biomes total and X-X always illegal, a 3-clique can never be validly colored -
        // pigeonhole forces two of the three to share a biome, which is always illegal
        // against itself, regardless of which AdjacencyRule pairs exist.
        RawRuleTable table = twoBiomeTable();
        AxialPos p1 = new AxialPos(0, 0);
        AxialPos p2 = p1.neighbor(0);
        AxialPos p3 = p1.neighbor(1); // consecutive with direction 0 - mutually adjacent to both p1 and p2
        Optional<Map<AxialPos, BiomeId>> solution = LocalClusterSolver.solve(table, Set.of(p1, p2, p3), Map.of());
        assertTrue(solution.isEmpty(), "a 3-clique can never be legally colored with only 2 biomes");
    }

    @Test
    void fixedContextConstrainsTheFreeFace() {
        RawRuleTable table = twoBiomeTable();
        AxialPos free = new AxialPos(0, 0);
        AxialPos fixedNeighbor = free.neighbor(0);
        Optional<Map<AxialPos, BiomeId>> solution = LocalClusterSolver.solve(table, Set.of(free), Map.of(fixedNeighbor, A));
        assertEquals(Optional.of(B), solution.map(s -> s.get(free)));
    }

    @Test
    void fixedContextCanMakeItUnsat() {
        // Free face has two fixed neighbors, one A one B - nothing is legal to both (X-X illegal, and A/B's only rule is to each other).
        RawRuleTable table = twoBiomeTable();
        AxialPos free = new AxialPos(0, 0);
        AxialPos fixedA = free.neighbor(0);
        AxialPos fixedB = free.neighbor(1);
        Optional<Map<AxialPos, BiomeId>> solution = LocalClusterSolver.solve(table, Set.of(free), Map.of(fixedA, A, fixedB, B));
        assertTrue(solution.isEmpty());
    }

    @Test
    void forcedAssignmentPinsASpecificBiomeWhenLegal() {
        RawRuleTable table = twoBiomeTable();
        AxialPos p1 = new AxialPos(0, 0);
        AxialPos p2 = new AxialPos(1, 0);
        Optional<Map<AxialPos, BiomeId>> solution = LocalClusterSolver.solve(table, Set.of(p1, p2), Map.of(), Map.of(p1, A));
        assertTrue(solution.isPresent());
        assertEquals(A, solution.get().get(p1));
        assertEquals(B, solution.get().get(p2));
    }

    @Test
    void forcedAssignmentFailsWhenIllegal() {
        RawRuleTable table = twoBiomeTable();
        AxialPos p1 = new AxialPos(0, 0);
        AxialPos p2 = new AxialPos(1, 0);
        // Forcing BOTH free faces to the same biome (A) is unsat: adjacent + X-X always illegal.
        Optional<Map<AxialPos, BiomeId>> solution = LocalClusterSolver.solve(table, Set.of(p1, p2), Map.of(), Map.of(p1, A, p2, A));
        assertTrue(solution.isEmpty());
    }
}
