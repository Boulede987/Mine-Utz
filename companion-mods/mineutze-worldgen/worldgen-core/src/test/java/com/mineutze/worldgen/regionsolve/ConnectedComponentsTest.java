package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeId;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.mineutze.worldgen.regionsolve.TestBiomeNodes.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct small-fixture tests for the same-biome flood-fill grouping - flagged by this
 * session's test-coverage audit as having zero dedicated tests despite backing
 * PatchSizeEnforcer, RegionIntegrityValidator, and the export CLI's own size reporting.
 */
class ConnectedComponentsTest {

    private static final BiomeId DESERT = id("desert");
    private static final BiomeId SAVANNA = id("savanna");

    @Test
    void groupsOrthogonallyAdjacentSameBiomeCellsIntoOneComponent() {
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        assignment.put(new MacroCellPos(0, 0), DESERT);
        assignment.put(new MacroCellPos(1, 0), DESERT);
        assignment.put(new MacroCellPos(2, 0), DESERT);

        List<Set<MacroCellPos>> components = ConnectedComponents.find(assignment, assignment.keySet());

        assertEquals(1, components.size());
        assertEquals(3, components.get(0).size());
    }

    @Test
    void diagonalTouchAloneDoesNotConnectUnderOrthogonalDefault() {
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        assignment.put(new MacroCellPos(0, 0), DESERT);
        assignment.put(new MacroCellPos(1, 1), DESERT); // corner-touching only

        List<Set<MacroCellPos>> components = ConnectedComponents.find(assignment, assignment.keySet());

        assertEquals(2, components.size(), "the default overload's connectivity is orthogonal-only");
    }

    @Test
    void diagonalTouchDoesConnectUnderMooreNeighborFn() {
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        assignment.put(new MacroCellPos(0, 0), DESERT);
        assignment.put(new MacroCellPos(1, 1), DESERT);

        List<Set<MacroCellPos>> components = ConnectedComponents.find(assignment, assignment.keySet(), MacroCellPos::mooreNeighbors);

        assertEquals(1, components.size(), "RegionIntegrityValidator's small-fragment check relies on this Moore overload treating a diagonal-only pair as one real component");
    }

    @Test
    void differentBiomesAreNeverGroupedTogetherEvenWhenAdjacent() {
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        assignment.put(new MacroCellPos(0, 0), DESERT);
        assignment.put(new MacroCellPos(1, 0), SAVANNA);

        List<Set<MacroCellPos>> components = ConnectedComponents.find(assignment, assignment.keySet());

        assertEquals(2, components.size());
    }

    @Test
    void traversalReachesThroughContextCellsBeyondOwnCellSet() {
        // ownCellSet seeds the search but traversal follows the WHOLE assignment map -
        // a patch continuing into read-only context (e.g. fixedBoundaryCells) must be
        // measured at its true size, not truncated at the caller's own cell boundary.
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        assignment.put(new MacroCellPos(0, 0), DESERT);
        assignment.put(new MacroCellPos(1, 0), DESERT); // context cell, not in ownCellSet
        Set<MacroCellPos> ownCellSet = Set.of(new MacroCellPos(0, 0));

        List<Set<MacroCellPos>> components = ConnectedComponents.find(assignment, ownCellSet);

        assertEquals(1, components.size());
        assertEquals(2, components.get(0).size(), "the component's TRUE size includes the context cell even though only the own cell seeded the search");
    }

    @Test
    void everyCellIsClaimedByExactlyOneComponent() {
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        assignment.put(new MacroCellPos(0, 0), DESERT);
        assignment.put(new MacroCellPos(1, 0), DESERT);
        assignment.put(new MacroCellPos(5, 5), SAVANNA);

        List<Set<MacroCellPos>> components = ConnectedComponents.find(assignment, assignment.keySet());

        long totalCellsAcrossComponents = components.stream().mapToLong(Set::size).sum();
        assertEquals(assignment.size(), totalCellsAcrossComponents, "no cell should be counted twice or dropped");
        assertTrue(components.stream().allMatch(component -> !component.isEmpty()));
    }
}
