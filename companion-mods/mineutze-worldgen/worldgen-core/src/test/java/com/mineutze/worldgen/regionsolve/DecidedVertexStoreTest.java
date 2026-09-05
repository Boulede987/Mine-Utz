package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.regionsolve.topology.HexGrid;
import com.mineutze.worldgen.regionsolve.topology.Point2D;
import com.mineutze.worldgen.regionsolve.topology.VertexKey;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecidedVertexStoreTest {

    private static VertexKey key(int a, int b, int c) {
        return new VertexKey(new HexGrid.AxialPos(a, 0), new HexGrid.AxialPos(b, 0), new HexGrid.AxialPos(c, 0));
    }

    @Test
    void getIsEmptyForAnUndecidedVertex() {
        DecidedVertexStore store = new DecidedVertexStore();
        assertTrue(store.get(key(0, 1, 2)).isEmpty());
    }

    @Test
    void commitPermanentThenGetRoundTrips() {
        DecidedVertexStore store = new DecidedVertexStore();
        VertexKey vertexKey = key(0, 1, 2);
        Point2D position = new Point2D(12.5, -7.25);

        store.commitPermanent(Map.of(vertexKey, position));

        assertEquals(Optional.of(position), store.get(vertexKey));
        assertEquals(Optional.of(position), store.getPermanent(vertexKey));
    }

    @Test
    void reCommittingTheSamePermanentValueForAnAlreadyDecidedVertexIsANoOp() {
        DecidedVertexStore store = new DecidedVertexStore();
        VertexKey vertexKey = key(3, 4, 5);
        Point2D position = new Point2D(1.0, 2.0);

        store.commitPermanent(Map.of(vertexKey, position));
        store.commitPermanent(Map.of(vertexKey, position)); // must not throw

        assertEquals(Optional.of(position), store.get(vertexKey));
    }

    @Test
    void reCommittingADifferentPermanentValueForAnAlreadyDecidedVertexFailsLoud() {
        DecidedVertexStore store = new DecidedVertexStore();
        VertexKey vertexKey = key(6, 7, 8);
        store.commitPermanent(Map.of(vertexKey, new Point2D(0.0, 0.0)));

        assertThrows(IllegalStateException.class, () -> store.commitPermanent(Map.of(vertexKey, new Point2D(1.0, 1.0))));
    }

    @Test
    void tentativeCommitIsFreelyOverwritable() {
        DecidedVertexStore store = new DecidedVertexStore();
        VertexKey vertexKey = key(9, 10, 11);
        Point2D first = new Point2D(1.0, 1.0);
        Point2D second = new Point2D(2.0, 2.0);

        store.commitTentative(Map.of(vertexKey, first));
        assertEquals(Optional.of(first), store.get(vertexKey));

        store.commitTentative(Map.of(vertexKey, second));
        assertEquals(Optional.of(second), store.get(vertexKey));
        assertTrue(store.getPermanent(vertexKey).isEmpty());
    }

    @Test
    void tentativelyRecommittingAnAlreadyPermanentVertexWithADifferentValueFailsLoud() {
        DecidedVertexStore store = new DecidedVertexStore();
        VertexKey vertexKey = key(12, 13, 14);
        store.commitPermanent(Map.of(vertexKey, new Point2D(0.0, 0.0)));

        assertThrows(IllegalStateException.class, () -> store.commitTentative(Map.of(vertexKey, new Point2D(5.0, 5.0))));
    }

    @Test
    void promotingATentativeVertexToPermanentRemovesItFromTheTentativeTier() {
        DecidedVertexStore store = new DecidedVertexStore();
        VertexKey vertexKey = key(15, 16, 17);
        Point2D position = new Point2D(3.0, 3.0);

        store.commitTentative(Map.of(vertexKey, position));
        assertEquals(1, store.tentativeCount());
        assertEquals(0, store.permanentCount());

        store.commitPermanent(Map.of(vertexKey, position));
        assertEquals(0, store.tentativeCount());
        assertEquals(1, store.permanentCount());
    }

    private static final class RecordingBacking implements VertexStoreBacking {
        final Map<VertexKey, Point2D> saved = new HashMap<>();
        final Map<VertexKey, Point2D> preloaded = new HashMap<>();
        int loadCalls = 0;

        @Override
        public Optional<Point2D> load(VertexKey key) {
            loadCalls++;
            return Optional.ofNullable(preloaded.get(key));
        }

        @Override
        public void save(VertexKey key, Point2D position) {
            saved.put(key, position);
        }
    }

    @Test
    void commitPermanentWritesThroughToBacking() {
        RecordingBacking backing = new RecordingBacking();
        DecidedVertexStore store = new DecidedVertexStore(backing);
        VertexKey vertexKey = key(0, 1, 2);
        Point2D position = new Point2D(5.0, 6.0);

        store.commitPermanent(Map.of(vertexKey, position));

        assertEquals(position, backing.saved.get(vertexKey));
    }

    @Test
    void tentativeCommitNeverWritesToBacking() {
        RecordingBacking backing = new RecordingBacking();
        DecidedVertexStore store = new DecidedVertexStore(backing);
        VertexKey vertexKey = key(20, 21, 22);
        store.commitTentative(Map.of(vertexKey, new Point2D(1.0, 1.0)));
        assertTrue(backing.saved.isEmpty(), "a tentative commit must never persist - only promotion to permanent should");
    }

    @Test
    void getPermanentLoadsThroughBackingOnAMemoryMissAndCachesIt() {
        RecordingBacking backing = new RecordingBacking();
        VertexKey vertexKey = key(3, 4, 9);
        Point2D position = new Point2D(1.0, -1.0);
        backing.preloaded.put(vertexKey, position);
        DecidedVertexStore store = new DecidedVertexStore(backing);

        assertEquals(Optional.of(position), store.get(vertexKey));
        assertEquals(Optional.of(position), store.get(vertexKey));

        assertEquals(1, backing.loadCalls, "expected the second get() to hit the in-memory cache, not re-read the backing store");
    }
}
