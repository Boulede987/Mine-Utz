package com.mineutze.worldgen.regionsolve.livepath;

import com.mineutze.worldgen.regionsolve.topology.mesh.VertexKey;

import com.mineutze.worldgen.regionsolve.topology.Point2D;

import java.util.Optional;

/**
 * Optional durable backing for {@link DecidedVertexStore} - same discipline as {@link
 * FaceStoreBacking}, one level down (mesh vertex instead of mesh face). Must actually be
 * persisted once real gameplay is on the line: unlike a face's biome+width, a vertex's
 * position isn't a pure function of just its 3 owning faces (see {@link
 * DecidedVertexStore}'s doc), so it can't be safely recomputed after a restart and
 * trusted to match what a previous session already rasterized real, saved terrain
 * against.
 */
public interface VertexStoreBacking {

    /** No persistence - {@link DecidedVertexStore}'s no-arg constructor defaults to this. */
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
