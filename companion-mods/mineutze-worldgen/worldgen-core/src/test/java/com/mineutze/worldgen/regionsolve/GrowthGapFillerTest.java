package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.AdjacencyRule;
import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.regionsolve.topology.TriangleIdentitySizeAssignment;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.mineutze.worldgen.regionsolve.TestBiomeNodes.id;
import static com.mineutze.worldgen.regionsolve.TestBiomeNodes.node;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct small-fixture tests for the post-growth legality-fill pass - flagged by this
 * session's test-coverage audit as having zero dedicated tests despite this exact class
 * being the source of the large majority of this pipeline's remaining pinch points (see
 * git history: 99.6% of one measured baseline traced to a fillGaps decision).
 */
class GrowthGapFillerTest {

    private static final BiomeId DESERT = id("desert");
    private static final BiomeId SAVANNA = id("savanna");
    private static final BiomeId FILLER = id("filler");

    @Test
    void fillsAGapFromItsOnlyDecidedNeighbor() {
        BiomeGraph graph = new BiomeGraph(
            List.of(node("desert", 100, 200), node("savanna", 100, 200)),
            List.of(AdjacencyRule.allow(DESERT, SAVANNA, 10))
        );
        List<MacroCellPos> cells = List.of(new MacroCellPos(1, 0), new MacroCellPos(2, 0));
        Map<MacroCellPos, BiomeId> decided = new HashMap<>();
        decided.put(new MacroCellPos(0, 0), DESERT); // real ground truth, outside the gap list itself

        GrowthGapFiller.fillGaps(cells, decided, graph, Map.of(), Map.of());

        assertEquals(DESERT, decided.get(new MacroCellPos(1, 0)), "the only decided neighbor's own biome has the highest match count and no competing candidate");
        assertEquals(DESERT, decided.get(new MacroCellPos(2, 0)), "cell (1,0) fills first within the same pass, giving cell (2,0) a decided neighbor too");
    }

    @Test
    void bootstrapsFromTheNearestFaceSeedWhenNoNeighborIsDecidedAtAll() {
        BiomeGraph graph = new BiomeGraph(
            List.of(node("desert", 100, 200)),
            List.of()
        );
        MacroCellPos gap = new MacroCellPos(0, 0);
        Map<Integer, MacroCellPos> seedCellByFace = Map.of(1, new MacroCellPos(0, 0));
        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments = Map.of(1, new TriangleIdentitySizeAssignment.Assignment(DESERT, 100));
        Map<MacroCellPos, BiomeId> decided = new HashMap<>();

        GrowthGapFiller.fillGaps(List.of(gap), decided, graph, seedCellByFace, assignments);

        assertEquals(DESERT, decided.get(gap), "a cell with zero decided Moore neighbors anywhere bootstraps to the nearest face's own biome, vacuously legal since there's nothing nearby to conflict with");
    }

    @Test
    void failsLoudOnAGenuineLocalDeadEnd() {
        // A-B: no direct rule and no biome legal to both simultaneously, but still
        // bridgeable pairwise (A-C-D-B) so the bare constructor's own reachability
        // check doesn't reject the graph outright - this isolates fillGaps' own
        // per-neighborhood check from BiomeGraph's separate, weaker pairwise one.
        BiomeId a = id("a");
        BiomeId b = id("b");
        BiomeId c = id("c");
        BiomeId d = id("d");
        BiomeGraph graph = new BiomeGraph(
            List.of(node("a", 100, 200), node("b", 100, 200), node("c", 100, 200), node("d", 100, 200)),
            List.of(AdjacencyRule.allow(a, c, 10), AdjacencyRule.allow(c, d, 10), AdjacencyRule.allow(d, b, 10))
        );
        MacroCellPos gap = new MacroCellPos(0, 0);
        Map<MacroCellPos, BiomeId> decided = new HashMap<>();
        decided.put(new MacroCellPos(1, 0), a);
        decided.put(new MacroCellPos(-1, 0), b);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> GrowthGapFiller.fillGaps(List.of(gap), decided, graph, Map.of(), Map.of()));
        assertTrue(thrown.getMessage().contains("genuine BiomeGraph configuration gap"));
    }

    @Test
    void prefersACandidateThatAvoidsRetroactivelyPinchingAnAlreadyDecidedNeighbor() {
        BiomeGraph graph = new BiomeGraph(
            List.of(node("savanna", 100, 200), node("filler", 100, 200)),
            List.of(AdjacencyRule.allow(SAVANNA, FILLER, 10))
        );
        MacroCellPos gap = new MacroCellPos(0, 0);
        Map<MacroCellPos, BiomeId> decided = new HashMap<>();
        decided.put(new MacroCellPos(0, -1), SAVANNA); // north - matches savanna, tie on match count
        decided.put(new MacroCellPos(-1, 0), SAVANNA); // west
        decided.put(new MacroCellPos(1, 0), FILLER);   // east - matches filler, tie on match count
        decided.put(new MacroCellPos(0, 1), FILLER);   // south
        // Two Moore-steps north of the gap: choosing FILLER here would leave the north
        // neighbor (savanna) pinched between the gap (filler) and this cell (desert-like
        // third biome) on the north-south axis; choosing SAVANNA would not.
        decided.put(new MacroCellPos(0, -2), id("desert"));

        GrowthGapFiller.fillGaps(List.of(gap), decided, graph, Map.of(), Map.of());

        assertEquals(SAVANNA, decided.get(gap),
            "match count ties 2-2 and neither candidate is self-pinched - only the retroactive-pinch check distinguishes them, and it must prefer savanna");
    }
}
