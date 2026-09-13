package com.mineutze.worldgen.regionsolve.livepath;

import com.mineutze.worldgen.regionsolve.topology.mesh.HexGrid;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.mineutze.worldgen.regionsolve.TestBiomeNodes.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecidedFaceStoreTest {

    @Test
    void getIsEmptyForAnUndecidedFace() {
        DecidedFaceStore store = new DecidedFaceStore();
        assertTrue(store.get(new HexGrid.AxialPos(0, 0)).isEmpty());
    }

    @Test
    void commitPermanentThenGetRoundTrips() {
        DecidedFaceStore store = new DecidedFaceStore();
        HexGrid.AxialPos pos = new HexGrid.AxialPos(3, -2);
        DecidedFaceStore.DecidedFace face = new DecidedFaceStore.DecidedFace(id("desert"), 400);

        store.commitPermanent(Map.of(pos, face));

        assertEquals(Optional.of(face), store.get(pos));
        assertEquals(Optional.of(face), store.getPermanent(pos));
    }

    @Test
    void reCommittingTheSamePermanentValueForAnAlreadyDecidedFaceIsANoOp() {
        DecidedFaceStore store = new DecidedFaceStore();
        HexGrid.AxialPos pos = new HexGrid.AxialPos(1, 1);
        DecidedFaceStore.DecidedFace face = new DecidedFaceStore.DecidedFace(id("plains"), 300);

        store.commitPermanent(Map.of(pos, face));
        store.commitPermanent(Map.of(pos, face)); // must not throw

        assertEquals(Optional.of(face), store.get(pos));
    }

    @Test
    void reCommittingADifferentPermanentValueForAnAlreadyDecidedFaceFailsLoud() {
        DecidedFaceStore store = new DecidedFaceStore();
        HexGrid.AxialPos pos = new HexGrid.AxialPos(0, 5);
        store.commitPermanent(Map.of(pos, new DecidedFaceStore.DecidedFace(id("savanna"), 350)));

        assertThrows(IllegalStateException.class, () ->
            store.commitPermanent(Map.of(pos, new DecidedFaceStore.DecidedFace(id("badlands"), 350)))
        );
    }

    @Test
    void tentativeCommitIsFreelyOverwritable() {
        DecidedFaceStore store = new DecidedFaceStore();
        HexGrid.AxialPos pos = new HexGrid.AxialPos(2, 2);
        DecidedFaceStore.DecidedFace first = new DecidedFaceStore.DecidedFace(id("desert"), 400);
        DecidedFaceStore.DecidedFace second = new DecidedFaceStore.DecidedFace(id("savanna"), 350);

        store.commitTentative(Map.of(pos, first));
        assertEquals(Optional.of(first), store.get(pos));

        store.commitTentative(Map.of(pos, second)); // must not throw, freely revises
        assertEquals(Optional.of(second), store.get(pos));
        assertTrue(store.getPermanent(pos).isEmpty(), "a tentative-only face must not appear as permanent");
    }

    @Test
    void tentativelyRecommittingAnAlreadyPermanentFaceWithADifferentValueFailsLoud() {
        DecidedFaceStore store = new DecidedFaceStore();
        HexGrid.AxialPos pos = new HexGrid.AxialPos(4, 4);
        store.commitPermanent(Map.of(pos, new DecidedFaceStore.DecidedFace(id("desert"), 400)));

        assertThrows(IllegalStateException.class, () ->
            store.commitTentative(Map.of(pos, new DecidedFaceStore.DecidedFace(id("savanna"), 350)))
        );
    }

    @Test
    void promotingATentativeFaceToPermanentRemovesItFromTheTentativeTier() {
        DecidedFaceStore store = new DecidedFaceStore();
        HexGrid.AxialPos pos = new HexGrid.AxialPos(6, 6);
        DecidedFaceStore.DecidedFace face = new DecidedFaceStore.DecidedFace(id("plains"), 300);

        store.commitTentative(Map.of(pos, face));
        assertEquals(1, store.tentativeCount());
        assertEquals(0, store.permanentCount());

        store.commitPermanent(Map.of(pos, face));
        assertEquals(0, store.tentativeCount());
        assertEquals(1, store.permanentCount());
        assertEquals(Optional.of(face), store.getPermanent(pos));
    }

    /**
     * Two "batches" committing disjoint face sets concurrently must never lose an entry -
     * same "adjacent regions racing each other" scenario {@code SeedRegionCache}'s doc
     * already documents real crash for, one layer down at store itself instead of at
     * cache/lock layer built on top of it later.
     */
    @Test
    void concurrentPermanentCommitsOfDisjointFacesNeverLoseData() throws InterruptedException {
        DecidedFaceStore store = new DecidedFaceStore();
        int facesPerBatch = 200;

        Map<HexGrid.AxialPos, DecidedFaceStore.DecidedFace> batchA = new HashMap<>();
        Map<HexGrid.AxialPos, DecidedFaceStore.DecidedFace> batchB = new HashMap<>();
        for (int i = 0; i < facesPerBatch; i++) {
            batchA.put(new HexGrid.AxialPos(i, 0), new DecidedFaceStore.DecidedFace(id("desert"), 400));
            batchB.put(new HexGrid.AxialPos(i, 1000), new DecidedFaceStore.DecidedFace(id("plains"), 300));
        }

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        try {
            var futureA = executor.submit(() -> {
                await(startLatch);
                store.commitPermanent(batchA);
            });
            var futureB = executor.submit(() -> {
                await(startLatch);
                store.commitPermanent(batchB);
            });
            startLatch.countDown();
            futureA.get();
            futureB.get();
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        for (int i = 0; i < facesPerBatch; i++) {
            assertEquals(Optional.of(batchA.get(new HexGrid.AxialPos(i, 0))), store.get(new HexGrid.AxialPos(i, 0)));
            assertEquals(Optional.of(batchB.get(new HexGrid.AxialPos(i, 1000))), store.get(new HexGrid.AxialPos(i, 1000)));
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static final class RecordingBacking implements FaceStoreBacking {
        final Map<HexGrid.AxialPos, DecidedFaceStore.DecidedFace> saved = new HashMap<>();
        final Map<HexGrid.AxialPos, DecidedFaceStore.DecidedFace> preloaded = new HashMap<>();
        int loadCalls = 0;

        @Override
        public Optional<DecidedFaceStore.DecidedFace> load(HexGrid.AxialPos axialPos) {
            loadCalls++;
            return Optional.ofNullable(preloaded.get(axialPos));
        }

        @Override
        public void save(HexGrid.AxialPos axialPos, DecidedFaceStore.DecidedFace face) {
            saved.put(axialPos, face);
        }
    }

    @Test
    void commitPermanentWritesThroughToBacking() {
        RecordingBacking backing = new RecordingBacking();
        DecidedFaceStore store = new DecidedFaceStore(backing);
        HexGrid.AxialPos pos = new HexGrid.AxialPos(9, 9);
        DecidedFaceStore.DecidedFace face = new DecidedFaceStore.DecidedFace(id("desert"), 400);

        store.commitPermanent(Map.of(pos, face));

        assertEquals(face, backing.saved.get(pos));
    }

    @Test
    void tentativeCommitNeverWritesToBacking() {
        RecordingBacking backing = new RecordingBacking();
        DecidedFaceStore store = new DecidedFaceStore(backing);
        HexGrid.AxialPos pos = new HexGrid.AxialPos(-9, -9);
        DecidedFaceStore.DecidedFace face = new DecidedFaceStore.DecidedFace(id("desert"), 400);

        store.commitTentative(Map.of(pos, face));

        assertTrue(backing.saved.isEmpty(), "a tentative commit must never persist - only promotion to permanent should");
    }

    @Test
    void getPermanentLoadsThroughBackingOnAMemoryMissAndCachesIt() {
        RecordingBacking backing = new RecordingBacking();
        HexGrid.AxialPos pos = new HexGrid.AxialPos(4, -4);
        DecidedFaceStore.DecidedFace face = new DecidedFaceStore.DecidedFace(id("plains"), 300);
        backing.preloaded.put(pos, face);
        DecidedFaceStore store = new DecidedFaceStore(backing);

        assertEquals(Optional.of(face), store.get(pos));
        assertEquals(Optional.of(face), store.get(pos));

        assertEquals(1, backing.loadCalls, "expected the second get() to hit the in-memory cache, not re-read the backing store");
    }

    @Test
    void reCommittingAFaceAlreadyLoadedFromBackingWithTheSameValueIsANoOp() {
        RecordingBacking backing = new RecordingBacking();
        HexGrid.AxialPos pos = new HexGrid.AxialPos(1, -1);
        DecidedFaceStore.DecidedFace face = new DecidedFaceStore.DecidedFace(id("savanna"), 350);
        backing.preloaded.put(pos, face);
        DecidedFaceStore store = new DecidedFaceStore(backing);

        store.get(pos); // populates memory from backing
        store.commitPermanent(Map.of(pos, face)); // must not throw, must not re-save

        assertEquals(Optional.of(face), store.get(pos));
    }
}
