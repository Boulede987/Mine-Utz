package com.mineutze.worldgen.regionsolve.pixelgrid;

import com.mineutze.worldgen.regionsolve.MacroCellPos;

import com.mineutze.worldgen.biomegraph.AdjacencyRule;
import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.mineutze.worldgen.regionsolve.TestBiomeNodes.id;
import static com.mineutze.worldgen.regionsolve.TestBiomeNodes.node;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct small-fixture tests for shared legality/cohesion core - flagged by earlier
 * test-coverage audit as having zero dedicated tests despite backing 3 real callers,
 * export-only today ({@code PatchSizeEnforcer}, {@code PixelFragmentRepair}, {@code
 * SeparatorWidener} - see {@code CellPlacementRules}'s doc).
 */
class CellPlacementRulesTest {

    private static final BiomeId DESERT = id("desert");
    private static final BiomeId SAVANNA = id("savanna");
    private static final BiomeId OCEAN = id("ocean"); // deliberately unregistered - see CellPlacementRulesTest.graph()

    /** OCEAN never registered node - only needs to exist as stand-in "illegal neighbor" value; registering it would require giving it real bridging path just to satisfy BiomeGraph's construction-time check, which these tests have no use for. */
    private static BiomeGraph graph() {
        return new BiomeGraph(
            List.of(node("desert", 100, 200), node("savanna", 100, 200)),
            List.of(AdjacencyRule.allow(DESERT, SAVANNA, 10))
        );
    }

    @Test
    void satisfiesCohesionIsFalseBelowTheThreshold() {
        MacroCellPos candidate = new MacroCellPos(0, 0);
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        assignment.put(new MacroCellPos(1, 0), DESERT); // only 1 same-biome Moore neighbor

        assertFalse(CellPlacementRules.satisfiesCohesion(candidate, DESERT, assignment));
    }

    @Test
    void satisfiesCohesionIsTrueAtTheThreshold() {
        MacroCellPos candidate = new MacroCellPos(0, 0);
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        assignment.put(new MacroCellPos(1, 0), DESERT);
        assignment.put(new MacroCellPos(-1, 0), DESERT);

        assertTrue(CellPlacementRules.satisfiesCohesion(candidate, DESERT, assignment));
    }

    @Test
    void satisfiesCohesionCountsDiagonalNeighborsNotJustOrthogonal() {
        MacroCellPos candidate = new MacroCellPos(0, 0);
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        assignment.put(new MacroCellPos(1, 1), DESERT); // both same-biome supports are diagonal-only
        assignment.put(new MacroCellPos(-1, -1), DESERT);

        assertTrue(CellPlacementRules.satisfiesCohesion(candidate, DESERT, assignment),
            "cohesion is measured over the full Moore neighborhood - a corridor cell touching only diagonals must still count");
    }

    @Test
    void isLegalForCellRejectsAnIllegalMooreNeighbor() {
        MacroCellPos cell = new MacroCellPos(0, 0);
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        assignment.put(new MacroCellPos(1, 1), OCEAN); // diagonal neighbor - Moore, not just orthogonal

        assertFalse(CellPlacementRules.isLegalForCell(cell, Set.of(), DESERT, assignment, graph()));
    }

    @Test
    void isLegalForCellIgnoresAnExcludedNeighbor() {
        MacroCellPos cell = new MacroCellPos(0, 0);
        MacroCellPos illegalNeighbor = new MacroCellPos(1, 0);
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        assignment.put(illegalNeighbor, OCEAN);

        assertTrue(CellPlacementRules.isLegalForCell(cell, Set.of(illegalNeighbor), DESERT, assignment, graph()),
            "a neighbor inside the caller's own still-being-assembled working set must not count against legality yet");
    }

    @Test
    void isLegalForGroupExcludesTheGroupsOwnInteriorCells() {
        MacroCellPos a = new MacroCellPos(0, 0);
        MacroCellPos b = new MacroCellPos(1, 0);
        Set<MacroCellPos> group = Set.of(a, b);
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        assignment.put(b, OCEAN); // b's OWN stale value - irrelevant, b is interior to the group being checked

        assertTrue(CellPlacementRules.isLegalForGroup(group, DESERT, assignment, graph()),
            "a's Moore neighbor b is part of the group itself and must never count as an external neighbor");
    }

    @Test
    void isLegalForGroupRejectsARealExternalIllegalNeighbor() {
        MacroCellPos a = new MacroCellPos(0, 0);
        MacroCellPos b = new MacroCellPos(1, 0);
        Set<MacroCellPos> group = Set.of(a, b);
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        assignment.put(new MacroCellPos(2, 0), OCEAN); // real neighbor of b, outside the group

        assertFalse(CellPlacementRules.isLegalForGroup(group, DESERT, assignment, graph()),
            "an illegal neighbor genuinely outside the group must still be checked");
    }
}
