package com.mineutze.worldgen.regionsolve.pixelgrid;

import com.mineutze.worldgen.regionsolve.MacroCellPos;

import com.mineutze.worldgen.biomegraph.AdjacencyRule;
import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.mineutze.worldgen.regionsolve.TestBiomeNodes.id;
import static com.mineutze.worldgen.regionsolve.TestBiomeNodes.node;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Direct small-fixture tests for {@code repair()} - flagged by test-coverage audit as
 * having zero dedicated tests. Only public entry point tested, matching this codebase's
 * existing pattern for classes like PatchSizeEnforcerTest whose private helpers are
 * exercised only through it.
 */
class PixelFragmentRepairTest {

    private static final BiomeId DESERT = id("desert");
    private static final BiomeId SAVANNA = id("savanna");
    private static final BiomeId OCEAN = id("ocean");

    @Test
    void reassignsAnIllegalCellToItsBestLegalMatchingNeighbor() {
        BiomeGraph graph = new BiomeGraph(
            List.of(node("desert", 100, 200), node("savanna", 100, 200), node("ocean", 100, 200)),
            List.of(AdjacencyRule.allow(SAVANNA, OCEAN, 10), AdjacencyRule.allow(DESERT, SAVANNA, 10))
        );
        Map<MacroCellPos, BiomeId> ownCells = Map.of(new MacroCellPos(0, 0), DESERT);
        // desert has no rule against ocean (illegal, forcing fix) even though it does have
        // one against savanna; savanna outnumbers ocean 3-to-1 among orthogonal neighbors,
        // so should win match-count tie-break among legal candidates.
        Map<MacroCellPos, BiomeId> fixedContext = Map.of(
            new MacroCellPos(1, 0), OCEAN,
            new MacroCellPos(-1, 0), SAVANNA,
            new MacroCellPos(0, 1), SAVANNA,
            new MacroCellPos(0, -1), SAVANNA
        );

        Map<MacroCellPos, BiomeId> repaired = PixelFragmentRepair.repair(ownCells, fixedContext, graph);

        assertEquals(SAVANNA, repaired.get(new MacroCellPos(0, 0)));
    }

    @Test
    void mergesASmallLegalFragmentEvenWhenItIsNotIllegal() {
        BiomeGraph graph = new BiomeGraph(
            List.of(node("desert", 100, 200), node("savanna", 100, 200)),
            List.of(AdjacencyRule.allow(DESERT, SAVANNA, 10))
        );
        // 2-cell desert patch, fully legal where it sits (desert-savanna allowed), but
        // under PixelFragmentRepair.SMALL_FRAGMENT_MAX_SIZE - discretization debris, not
        // real patch, even though nothing here actually illegal.
        Map<MacroCellPos, BiomeId> ownCells = Map.of(
            new MacroCellPos(0, 0), DESERT,
            new MacroCellPos(1, 0), DESERT
        );
        Map<MacroCellPos, BiomeId> fixedContext = Map.of(
            new MacroCellPos(-1, 0), SAVANNA,
            new MacroCellPos(2, 0), SAVANNA,
            new MacroCellPos(0, 1), SAVANNA,
            new MacroCellPos(1, 1), SAVANNA
        );

        Map<MacroCellPos, BiomeId> repaired = PixelFragmentRepair.repair(ownCells, fixedContext, graph);

        assertEquals(SAVANNA, repaired.get(new MacroCellPos(0, 0)));
        assertEquals(SAVANNA, repaired.get(new MacroCellPos(1, 0)));
    }

    @Test
    void leavesALegalProperlySizedPatchUntouched() {
        BiomeGraph graph = new BiomeGraph(List.of(node("desert", 100, 200)), List.of());
        // 5 cells, one connected component, over SMALL_FRAGMENT_MAX_SIZE (4) - nothing
        // borders it, so trivially legal too. Nothing should change.
        Map<MacroCellPos, BiomeId> ownCells = Map.of(
            new MacroCellPos(0, 0), DESERT, new MacroCellPos(1, 0), DESERT,
            new MacroCellPos(2, 0), DESERT, new MacroCellPos(3, 0), DESERT,
            new MacroCellPos(4, 0), DESERT
        );

        Map<MacroCellPos, BiomeId> repaired = PixelFragmentRepair.repair(ownCells, Map.of(), graph);

        assertEquals(ownCells, repaired);
    }

    @Test
    void leavesAnIllegalCellAloneWhenNoLegalCandidateExistsAndNeverMutatesFixedContext() {
        BiomeGraph graph = new BiomeGraph(List.of(node("desert", 100, 200)), List.of());
        Map<MacroCellPos, BiomeId> ownCells = Map.of(new MacroCellPos(0, 0), DESERT);
        // ocean never registered and never legal to desert - no candidate in whole graph
        // can fix this cell. Must not crash, force bad value, or hang. Immutable Map.of()
        // here also means: if repair() ever tried to write into fixedContext directly
        // instead of only reading through it, this call would throw
        // UnsupportedOperationException instead of silently corrupting shared state.
        Map<MacroCellPos, BiomeId> fixedContext = Map.of(new MacroCellPos(1, 0), OCEAN);

        Map<MacroCellPos, BiomeId> repaired = PixelFragmentRepair.repair(ownCells, fixedContext, graph);

        assertEquals(DESERT, repaired.get(new MacroCellPos(0, 0)),
            "logged and accepted rather than forced when no legal replacement exists - see this class's own doc");
        assertEquals(OCEAN, fixedContext.get(new MacroCellPos(1, 0)), "fixedContext must remain exactly as the caller provided it");
    }
}
