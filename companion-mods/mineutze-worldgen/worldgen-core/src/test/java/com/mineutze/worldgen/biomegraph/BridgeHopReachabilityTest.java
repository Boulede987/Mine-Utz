package com.mineutze.worldgen.biomegraph;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct small-fixture tests - flagged by this session's test-coverage audit as having
 * zero dedicated tests despite being {@link BiomeGraph}'s own construction-time
 * bridgeability check.
 */
class BridgeHopReachabilityTest {

    private static final BiomeId A = new BiomeId("test", "a");
    private static final BiomeId B = new BiomeId("test", "b");
    private static final BiomeId C = new BiomeId("test", "c");
    private static final BiomeId D = new BiomeId("test", "d");

    private static BiPredicate<BiomeId, BiomeId> chainAllowed(Set<List<BiomeId>> edges) {
        return (x, y) -> edges.contains(List.of(x, y)) || edges.contains(List.of(y, x));
    }

    @Test
    void directlyAllowedPairsNeverThrow() {
        BiPredicate<BiomeId, BiomeId> isAllowed = chainAllowed(Set.of(List.of(A, B)));

        assertDoesNotThrow(() -> BridgeHopReachability.requireEveryPairWithinHops(List.of(A, B), isAllowed, 3));
    }

    @Test
    void aPairBridgedWithinTheHopLimitDoesNotThrow() {
        // A-B-C-D chain: A to D needs exactly 3 hops.
        BiPredicate<BiomeId, BiomeId> isAllowed = chainAllowed(Set.of(List.of(A, B), List.of(B, C), List.of(C, D)));

        assertDoesNotThrow(() -> BridgeHopReachability.requireEveryPairWithinHops(List.of(A, B, C, D), isAllowed, 3));
    }

    @Test
    void aPairNeedingMoreThanTheHopLimitThrows() {
        BiPredicate<BiomeId, BiomeId> isAllowed = chainAllowed(Set.of(List.of(A, B), List.of(B, C), List.of(C, D)));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
            () -> BridgeHopReachability.requireEveryPairWithinHops(List.of(A, B, C, D), isAllowed, 2));
        assertTrue(thrown.getMessage().contains("3 hops"), "should report the real distance it actually found");
    }

    @Test
    void aCompletelyDisconnectedPairThrows() {
        BiPredicate<BiomeId, BiomeId> isAllowed = chainAllowed(Set.of()); // no edges at all

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
            () -> BridgeHopReachability.requireEveryPairWithinHops(List.of(A, B), isAllowed, 3));
        assertTrue(thrown.getMessage().contains("no bridging path at all"));
    }
}
