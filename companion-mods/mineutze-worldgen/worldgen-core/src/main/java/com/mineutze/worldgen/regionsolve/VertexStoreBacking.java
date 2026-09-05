package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.regionsolve.topology.Point2D;
import com.mineutze.worldgen.regionsolve.topology.VertexKey;

import java.util.Optional;

/**
 * Optional durable backing for {@link DecidedVertexStore} — same discipline as {@link
 * FaceStoreBacking}, one level down (a mesh vertex instead of a mesh face). Must actually
 * be persisted once real gameplay is on the line: unlike a face's biome+width, a vertex's
 * position is NOT a pure function of just its 3 owning faces (see {@link
 * DecidedVertexStore}'s own doc), so it can't be safely recomputed from scratch after a
 * restart and trusted to land on the exact same value a previous session already
 * rasterized real, saved terrain against.
 */
public interface VertexStoreBacking {

    /** No persistence at all — {@link DecidedVertexStore}'s no-arg constructor defaults to this. */
    VertexStoreBacking NONE = new VertexStoreBacking() {
        @Override
        public Optional<Point2D> load(VertexKey key) {
            return Optional.empty();
        }

        @Override
        public void save(VertexKey key, Point2D position) {
        }
    };

    Optional<Point2D> load(VertexKey key);

    void save(VertexKey key, Point2D position);
}
