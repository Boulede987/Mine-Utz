package com.mineutze.worldgen.coherence;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.EdgeData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.NodeData;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.coherence.PeriodicRowLayout.Cell;
import com.mineutze.worldgen.coherence.PeriodicRowLayout.Row;
import com.mineutze.worldgen.coherence.ReachabilityScoutingChecks.AdversarialRowResult;
import com.mineutze.worldgen.coherence.ReachabilityScoutingChecks.UniversalSinkResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Small hand-built synthetic graphs, not the real catalog. The full adversarial-search
 * path (does an isolated row actually get found or not) is exercised for real against
 * production data separately (0/10 found there) - here we only verify the deterministic
 * mechanism pieces are individually correct, same split proof #1's own tests use.
 */
class ReachabilityScoutingChecksTest {

    private static final BiomeId A = new BiomeId("test", "a");
    private static final BiomeId B = new BiomeId("test", "b");
    private static final BiomeId C = new BiomeId("test", "c");

    /** Every pair among A/B/C legal - a real sink CAN exist under this fixture. */
    private static RawRuleTable tableFullyLegal() {
        BiomeGraphData data = new BiomeGraphData(
            List.of(
                new NodeData(A.namespace(), A.path(), 500, 800, Integer.MAX_VALUE, List.of()),
                new NodeData(B.namespace(), B.path(), 500, 800, Integer.MAX_VALUE, List.of()),
                new NodeData(C.namespace(), C.path(), 500, 800, Integer.MAX_VALUE, List.of())
            ),
            List.of(
                new EdgeData(A.namespace(), A.path(), B.namespace(), B.path(), 10),
                new EdgeData(A.namespace(), A.path(), C.namespace(), C.path(), 10),
                new EdgeData(B.namespace(), B.path(), C.namespace(), C.path(), 10)
            )
        );
        return RawRuleTable.from(data);
    }

    /** A-B legal, but B-C NOT legal - real asymmetry to detect. */
    private static RawRuleTable tableWithAnIllegalPair() {
        BiomeGraphData data = new BiomeGraphData(
            List.of(
                new NodeData(A.namespace(), A.path(), 500, 800, Integer.MAX_VALUE, List.of()),
                new NodeData(B.namespace(), B.path(), 500, 800, Integer.MAX_VALUE, List.of()),
                new NodeData(C.namespace(), C.path(), 500, 800, Integer.MAX_VALUE, List.of())
            ),
            List.of(new EdgeData(A.namespace(), A.path(), B.namespace(), B.path(), 10))
        );
        return RawRuleTable.from(data);
    }

    @Test
    void checkUniversalSinkDetectsARealSink() {
        // Every row here is legal against every other row (fully connected fixture) - every witness row IS a sink.
        Row row0 = new Row(List.of(new Cell(A, 5, 0), new Cell(B, 5, 5)));
        Row row1 = new Row(List.of(new Cell(C, 10, 0)));
        PeriodicRowLayout witness = new PeriodicRowLayout(10, 10, List.of(row0, row1));

        List<UniversalSinkResult> results = ReachabilityScoutingChecks.checkUniversalSink(witness, tableFullyLegal());
        assertTrue(results.stream().allMatch(UniversalSinkResult::isUniversalSinkAmongWitnessRows));
    }

    @Test
    void checkUniversalSinkDetectsANonSink() {
        // row0 (B,C) touching row1 (single cell) - B-C is not legal in this fixture, so row0 is NOT a sink.
        Row row0 = new Row(List.of(new Cell(B, 5, 0), new Cell(C, 5, 5)));
        Row row1 = new Row(List.of(new Cell(A, 10, 0)));
        PeriodicRowLayout witness = new PeriodicRowLayout(10, 10, List.of(row0, row1));

        List<UniversalSinkResult> results = ReachabilityScoutingChecks.checkUniversalSink(witness, tableWithAnIllegalPair());
        UniversalSinkResult row0Result = results.stream().filter(r -> r.rowIndex() == 0).findFirst().orElseThrow();
        assertFalse(row0Result.isUniversalSinkAmongWitnessRows());
        assertEquals(1, row0Result.counterexampleRowIndex());
    }

    /**
     * Real, deliberately DIFFERENT ranges per biome (500-800, 400-700, 600-900, 300-600,
     * 450-750) - not just "5 fully-legal biomes." A real bug was found while building this
     * test: with only 3 biomes sharing the EXACT SAME range (500-800 each), {@code
     * buildRowSequence}'s provisional midpoint-width estimate (6.5) systematically
     * disagreed with the actual final exact width ({@link RowFeasibility}'s real fit,
     * 6.25) enough to falsely reject the only viable successor candidates - confirmed by
     * hand-constructing one, verifying it {@code isVerticallyLegal} (true), then still
     * failing to find it via search even at 100,000 retries. Varied ranges avoid the
     * degenerate exact-tie case; see {@code PeriodicCoherenceProof.buildRowSequence}'s own
     * doc for the permanent record of this finding.
     */
    private static RawRuleTable tableWithVariedRangesFullyLegal() {
        BiomeId a2 = new BiomeId("test", "a2");
        BiomeId b2 = new BiomeId("test", "b2");
        BiomeId c2 = new BiomeId("test", "c2");
        BiomeId d2 = new BiomeId("test", "d2");
        BiomeId e2 = new BiomeId("test", "e2");
        List<NodeData> nodes = List.of(
            new NodeData(a2.namespace(), a2.path(), 500, 800, Integer.MAX_VALUE, List.of()),
            new NodeData(b2.namespace(), b2.path(), 400, 700, Integer.MAX_VALUE, List.of()),
            new NodeData(c2.namespace(), c2.path(), 600, 900, Integer.MAX_VALUE, List.of()),
            new NodeData(d2.namespace(), d2.path(), 300, 600, Integer.MAX_VALUE, List.of()),
            new NodeData(e2.namespace(), e2.path(), 450, 750, Integer.MAX_VALUE, List.of())
        );
        List<BiomeId> all = List.of(a2, b2, c2, d2, e2);
        List<EdgeData> edges = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                edges.add(new EdgeData(all.get(i).namespace(), all.get(i).path(), all.get(j).namespace(), all.get(j).path(), 10));
            }
        }
        return RawRuleTable.from(new BiomeGraphData(nodes, edges));
    }

    @Test
    void checkForIsolatedRowFindsASuccessorOnAFullyLegalFixture() {
        PeriodicRowLayout witness = new PeriodicRowLayout(100, 60, List.of());
        AdversarialRowResult result = ReachabilityScoutingChecks.checkForIsolatedRow(witness, tableWithVariedRangesFullyLegal(), new Random(1));
        assertTrue(result.hardRowFound());
        assertTrue(result.successorFound(), "every pair is legal in this fixture, a successor must always exist");
    }

    @Test
    void checkForIsolatedRowReportsNoHardRowWhenCircumferenceIsUnreachable() {
        // A circumference far beyond what any single biome's own range (500-800/height) could ever reach with only 2-3 biomes.
        Row row0 = new Row(List.of(new Cell(A, 100_000, 0)));
        PeriodicRowLayout unreachableWitness = new PeriodicRowLayout(10, 100_000, List.of(row0));

        AdversarialRowResult result = ReachabilityScoutingChecks.checkForIsolatedRow(unreachableWitness, tableFullyLegal(), new Random(1));
        assertFalse(result.hardRowFound(), "no combination of only 3 biomes at 500-800 range should ever sum to a circumference of 100,000");
    }
}
