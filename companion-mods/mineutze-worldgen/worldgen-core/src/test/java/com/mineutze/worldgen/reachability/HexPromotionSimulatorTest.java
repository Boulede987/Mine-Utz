package com.mineutze.worldgen.reachability;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.EdgeData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.NodeData;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.coherence.RawRuleTable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HexPromotionSimulatorTest {

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

    // cap=1: a query's own cluster is always just the single seed face (see
    // HexPromotionSimulator's own doc - distance-1 neighbors are excluded, matching the
    // real hull-touching stop). Trivially colorable with any table, no odd-cycle/wheel
    // coloring concern - the two-biome table alone can't legally color a real hex WHEEL
    // (hub + 6-cycle rim, needs 3+ colors since consecutive rim members are mutually
    // adjacent too - see AxialPosTest.consecutiveNeighborsAreMutuallyAdjacent), which is
    // exactly what a cap>=2 isolated query would sweep in.

    @Test
    void firstQueryOfAnUndecidedFaceBecomesPermanent() {
        HexPromotionSimulator simulator = new HexPromotionSimulator(twoBiomeTable(), 1);
        AxialPos target = new AxialPos(0, 0);
        HexPromotionSimulator.QueryResult result = simulator.query(target);
        assertFalse(result.deadEnd());
        assertTrue(simulator.valueAt(target).orElseThrow().permanent());
    }

    @Test
    void requeryingAnAlreadyValuedFaceIsAHarmlessPromotionNoOp() {
        HexPromotionSimulator simulator = new HexPromotionSimulator(twoBiomeTable(), 1);
        AxialPos target = new AxialPos(0, 0);
        HexPromotionSimulator.QueryResult first = simulator.query(target);
        BiomeId firstBiome = simulator.valueAt(target).orElseThrow().biomeId();
        HexPromotionSimulator.QueryResult second = simulator.query(target);
        assertFalse(second.deadEnd());
        assertEquals(firstBiome, simulator.valueAt(target).orElseThrow().biomeId());
    }

    @Test
    void twoQueriesFarApartNeverInteract() {
        HexPromotionSimulator simulator = new HexPromotionSimulator(twoBiomeTable(), 1);
        AxialPos far1 = new AxialPos(0, 0);
        AxialPos far2 = new AxialPos(1000, 0);
        assertFalse(simulator.query(far1).deadEnd());
        assertFalse(simulator.query(far2).deadEnd());
    }

    /**
     * A rich 8-biome graph deliberately shaped so two SPECIFIC "wall" biomes share zero
     * common legal partner (forces attempt 1 to fail) while everything else stays flexible
     * enough that freeing a TENTATIVE wall (attempt 2) has real room to succeed - see this
     * test's own inline commentary for the exact geometry/biome reasoning.
     */
    private static RawRuleTable twoAttemptGraph() {
        BiomeId wallY = new BiomeId("test", "wall_y");
        BiomeId wallW = new BiomeId("test", "wall_w");
        BiomeId commonY = new BiomeId("test", "common_y");
        BiomeId commonW = new BiomeId("test", "common_w");
        BiomeId bridge = new BiomeId("test", "bridge");
        BiomeId hub = new BiomeId("test", "hub");
        BiomeId filler1 = new BiomeId("test", "filler1");
        BiomeId filler2 = new BiomeId("test", "filler2");
        List<BiomeId> all = List.of(wallY, wallW, commonY, commonW, bridge, hub, filler1, filler2);
        List<NodeData> nodes = new ArrayList<>();
        for (BiomeId id : all) {
            nodes.add(new NodeData(id.namespace(), id.path(), 100, 200, Integer.MAX_VALUE, List.of()));
        }
        List<EdgeData> edges = List.of(
            edge(wallY, commonY), edge(wallW, commonW),
            // wallY/wallW each get exactly one EXTRA legal partner (filler1/filler2
            // respectively, kept DISJOINT from each other) - needed so each can sit as a
            // spoke in its own hub's wheel (its rim-neighbors need a value legal to BOTH
            // the wall AND the hub - without this, forcing wallY onto a spoke is itself
            // unsatisfiable, a real dead end this test doesn't intend to exercise). Keeping
            // the two extra partners disjoint preserves "wallY and wallW share zero legal
            // partner" (the property attempt 1's real failure depends on).
            edge(wallY, filler1), edge(wallW, filler2),
            edge(commonY, bridge), edge(commonW, bridge),
            edge(hub, commonY), edge(hub, commonW), edge(hub, bridge), edge(hub, filler1), edge(hub, filler2),
            edge(filler1, commonY), edge(filler1, commonW), edge(filler1, bridge),
            edge(filler2, commonY), edge(filler2, commonW), edge(filler2, bridge),
            edge(filler1, filler2)
        );
        return RawRuleTable.from(new BiomeGraphData(nodes, edges));
    }

    private static EdgeData edge(BiomeId a, BiomeId b) {
        return new EdgeData(a.namespace(), a.path(), b.namespace(), b.path(), 10);
    }

    /**
     * Reproduces the real two-attempt mechanic end to end: a face (X) queried while one
     * neighbor is TENTATIVE ("wall_y") and another is PERMANENT ("wall_w"), where those two
     * walls share zero common legal partner - attempt 1 (tentative treated as fixed) must
     * fail; attempt 2 (tentative freed, re-negotiable) must succeed, matching {@code
     * TriangleFieldGeometryRealization.realizeCluster}'s own real behavior exactly.
     *
     * <p>Geometry (growth cap 2, so a direct 1-hop neighbor is reachable but a 2-hop one
     * is not): Z=(0,0) -[dir0]-> Ywall=(1,0) -[dir0]-> X=(2,0); separately Z2=(2,-2)
     * -[dir2]-> W=(2,-1) -[dir2, i.e. the same step reversed]-> X. Both Z and Z2 sit
     * exactly 2 hex-steps from X - outside the growth cap - so neither ever gets swept into
     * X's own query, and X stays genuinely undecided until directly queried.
     */
    @Test
    void attempt2RescuesAQueryThatAttempt1CannotSolve() {
        RawRuleTable table = twoAttemptGraph();
        BiomeId wallY = new BiomeId("test", "wall_y");
        BiomeId wallW = new BiomeId("test", "wall_w");
        HexPromotionSimulator simulator = new HexPromotionSimulator(table, 2);

        AxialPos z = new AxialPos(0, 0);
        AxialPos ywall = z.neighbor(0);
        AxialPos x = ywall.neighbor(0);
        AxialPos w = x.neighbor(2);
        AxialPos z2 = w.neighbor(2);

        assertEquals(2, z.distanceTo(x), "sanity: Z must be exactly at the growth-cap boundary from X");
        assertEquals(2, z2.distanceTo(x), "sanity: Z2 must be exactly at the growth-cap boundary from X");
        assertEquals(1, ywall.distanceTo(z), "sanity: Ywall must be a direct neighbor of Z");
        assertEquals(1, w.distanceTo(z2), "sanity: W must be a direct neighbor of Z2");

        // Step 1: Ywall becomes TENTATIVE=wallY, as a non-target member of Z's own query.
        HexPromotionSimulator.QueryResult zResult = simulator.query(z, Map.of(ywall, wallY));
        assertFalse(zResult.deadEnd(), "setting up Ywall as tentative should not itself be a dead end");
        assertFalse(simulator.valueAt(ywall).orElseThrow().permanent(), "Ywall must be TENTATIVE, not permanent, for this test to be meaningful");
        assertEquals(wallY, simulator.valueAt(ywall).orElseThrow().biomeId());

        // Step 2: W becomes TENTATIVE=wallW via Z2's query, then promoted to PERMANENT by directly re-querying it (fast path, no cluster growth - X stays untouched).
        HexPromotionSimulator.QueryResult z2Result = simulator.query(z2, Map.of(w, wallW));
        assertFalse(z2Result.deadEnd());
        HexPromotionSimulator.QueryResult wPromotion = simulator.query(w);
        assertFalse(wPromotion.deadEnd());
        assertTrue(simulator.valueAt(w).orElseThrow().permanent(), "re-querying W directly must promote it to permanent");
        assertEquals(wallW, simulator.valueAt(w).orElseThrow().biomeId());

        assertTrue(simulator.valueAt(x).isEmpty(), "X must still be genuinely undecided at this point");

        // Step 3: query X - attempt 1 (Ywall walled at wallY, W walled at wallW, zero shared legal partner) must fail internally, attempt 2 (Ywall freed) must rescue it.
        HexPromotionSimulator.QueryResult xResult = simulator.query(x);
        assertFalse(xResult.deadEnd(), "attempt 2 should rescue this query - real two-attempt mechanic, not a dead end");
        assertTrue(xResult.attempt2FreeFaceCount() > 0, "attempt 2 must have actually run (attempt 1 alone should not have been sufficient here)");
        assertTrue(simulator.valueAt(x).orElseThrow().permanent());
    }
}
