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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SizeAwareHexPromotionSimulatorTest {

    private static final BiomeId FILLER = new BiomeId("test", "filler");
    private static final BiomeId NEEDS_THREE = new BiomeId("test", "needs_three");

    // See SizeAwareLocalClusterSolverTest's own comment: calibrated so width(N faces) = 100*sqrt(N).
    private static final double BASELINE_AREA = HexGeometry.areaForCircumradius(HexGeometry.circumradiusForWidth(100));

    private static RawRuleTable fillerOnlyTable() {
        List<NodeData> nodes = List.of(new NodeData(FILLER.namespace(), FILLER.path(), 1, 100_000, Integer.MAX_VALUE, List.of()));
        return RawRuleTable.from(new BiomeGraphData(nodes, List.of()));
    }

    /** NEEDS_THREE's own minimum (300) needs 9 faces (width 100*sqrt(9)=300) - real recruitment needed, see SizeAwareLocalClusterSolverTest for the solver-level detail. */
    private static RawRuleTable needsThreeTable() {
        List<NodeData> nodes = List.of(
            new NodeData(NEEDS_THREE.namespace(), NEEDS_THREE.path(), 300, 100_000, Integer.MAX_VALUE, List.of()),
            new NodeData(FILLER.namespace(), FILLER.path(), 1, 100_000, Integer.MAX_VALUE, List.of())
        );
        List<EdgeData> edges = List.of(new EdgeData(NEEDS_THREE.namespace(), NEEDS_THREE.path(), FILLER.namespace(), FILLER.path(), 10));
        return RawRuleTable.from(new BiomeGraphData(nodes, edges));
    }

    @Test
    void firstQueryBecomesPermanent() {
        SizeAwareHexPromotionSimulator simulator = new SizeAwareHexPromotionSimulator(fillerOnlyTable(), 1, BASELINE_AREA);
        AxialPos target = new AxialPos(0, 0);
        SizeAwareHexPromotionSimulator.QueryResult result = simulator.query(target, Map.of(target, FILLER));
        assertFalse(result.deadEnd());
        assertTrue(simulator.valueAt(target).orElseThrow().permanent());
    }

    /**
     * Real needs-driven recruitment, exercised end to end: on FRESH territory (nothing
     * committed anywhere nearby) with a real growth cap, a query forced to a biome whose
     * minimum exceeds one face's own area should automatically recruit enough free
     * neighbors to satisfy it - no false dead end, matching real production's own "fresh
     * territory legitimately uses the whole growth budget" behavior.
     */
    @Test
    void freshTerritoryQueryRecruitsEnoughToSatisfyItsOwnMinimum() {
        SizeAwareHexPromotionSimulator simulator = new SizeAwareHexPromotionSimulator(needsThreeTable(), 3, BASELINE_AREA);
        AxialPos target = new AxialPos(0, 0);
        SizeAwareHexPromotionSimulator.QueryResult result = simulator.query(target, Map.of(target, NEEDS_THREE));
        assertFalse(result.deadEnd(), "fresh territory has ample free neighbors to recruit - should never be a dead end: " + result.message());
        assertEquals(NEEDS_THREE, simulator.valueAt(target).orElseThrow().biomeId());
        assertTrue(simulator.valueAt(target).orElseThrow().permanent());
    }

    @Test
    void requeryingAnAlreadyValuedFaceIsAHarmlessPromotionNoOp() {
        SizeAwareHexPromotionSimulator simulator = new SizeAwareHexPromotionSimulator(fillerOnlyTable(), 1, BASELINE_AREA);
        AxialPos target = new AxialPos(0, 0);
        simulator.query(target, Map.of(target, FILLER));
        SizeAwareHexPromotionSimulator.QueryResult second = simulator.query(target);
        assertFalse(second.deadEnd());
        assertEquals(FILLER, simulator.valueAt(target).orElseThrow().biomeId());
    }
}
