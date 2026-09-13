package com.mineutze.worldgen.regionsolve.windowcache;

import com.mineutze.worldgen.regionsolve.RegionPos;

import com.mineutze.worldgen.regionsolve.RegionSolveResult;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Direct test for {@code NONE} no-op implementation - flagged by test-coverage audit as having zero dedicated tests. */
class RegionPersistenceStoreTest {

    @Test
    void noneNeverHasAnythingToLoad() {
        Optional<RegionSolveResult> loaded = RegionPersistenceStore.NONE.load(new RegionPos(0, 0));

        assertTrue(loaded.isEmpty(), "NONE must mean no persistence at all, not a silently-populated fake result");
    }

    @Test
    void noneSilentlyDiscardsAnySave() {
        RegionSolveResult result = new RegionSolveResult(Map.of(), List.of(), 0);

        assertDoesNotThrow(() -> RegionPersistenceStore.NONE.save(new RegionPos(0, 0), result));
    }
}
