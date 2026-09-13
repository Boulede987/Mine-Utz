package com.mineutze.worldgen.regionsolve.livepath;

import com.mineutze.worldgen.regionsolve.topology.mesh.HexGrid;

import java.util.Optional;

/**
 * Optional durable backing for {@link DecidedFaceStore} - same "never independently
 * decide solved-ness" discipline {@link RegionPersistenceStore} used for the old
 * region-keyed path: answers "do you have this face's ALREADY-DECIDED result," never
 * "should this face be solved." {@link DecidedFaceStore} alone decides whether a face
 * needs solving - a backing implementation is just a bigger, slower key-value store
 * underneath it.
 *
 * <p>{@code worldgen-core} has no Minecraft/NeoForge dependency by design - a real
 * implementation backed by NeoForge's {@code SavedData}, sharded purely for file-size
 * (never legality/ownership - see this redesign's plan doc), lives in the mod's {@code
 * integration} module instead.
 */
public interface FaceStoreBacking {

    /** No persistence - {@link DecidedFaceStore}'s no-arg constructor defaults to this. */
    FaceStoreBacking NONE = new FaceStoreBacking() {
        @Override
        public Optional<DecidedFaceStore.DecidedFace> load(HexGrid.AxialPos axialPos) {
            return Optional.empty();
        }

        @Override
        public void save(HexGrid.AxialPos axialPos, DecidedFaceStore.DecidedFace face) {
        }
    };

    /** Empty if this face was never decided, or its persisted result is unavailable - never "not yet, but go solve it." */
    Optional<DecidedFaceStore.DecidedFace> load(HexGrid.AxialPos axialPos);

    /** Called once, right after a face is freshly committed - durable before it could ever be evicted. */
    void save(HexGrid.AxialPos axialPos, DecidedFaceStore.DecidedFace face);
}
