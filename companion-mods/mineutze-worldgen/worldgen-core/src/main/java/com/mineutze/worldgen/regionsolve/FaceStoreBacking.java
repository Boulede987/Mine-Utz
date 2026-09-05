package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.regionsolve.topology.HexGrid;

import java.util.Optional;

/**
 * Optional durable backing for {@link DecidedFaceStore} — same "never independently
 * decide solved-ness" discipline {@link RegionPersistenceStore} already established for
 * the old region-keyed path: this interface only ever answers "do you have this face's
 * ALREADY-DECIDED result," never "should this face be solved." {@link DecidedFaceStore}
 * itself is still the only thing that decides whether a face needs solving — a backing
 * implementation is purely a bigger, slower key-value store underneath it.
 *
 * <p>{@code worldgen-core} has no Minecraft/NeoForge dependency by design — a real
 * implementation backed by NeoForge's {@code SavedData}, sharded purely for file-size
 * reasons (never for legality/ownership — see this whole redesign's own plan doc), lives
 * in the mod's {@code integration} module instead.
 */
public interface FaceStoreBacking {

    /** No persistence at all — {@link DecidedFaceStore}'s no-arg constructor defaults to this. */
    FaceStoreBacking NONE = new FaceStoreBacking() {
        @Override
        public Optional<DecidedFaceStore.DecidedFace> load(HexGrid.AxialPos axialPos) {
            return Optional.empty();
        }

        @Override
        public void save(HexGrid.AxialPos axialPos, DecidedFaceStore.DecidedFace face) {
        }
    };

    /** Empty if this face was never decided, or its persisted result is unavailable — never "not yet, but go solve it." */
    Optional<DecidedFaceStore.DecidedFace> load(HexGrid.AxialPos axialPos);

    /** Called once, immediately after a face is freshly committed — durable before it could ever be evicted. */
    void save(HexGrid.AxialPos axialPos, DecidedFaceStore.DecidedFace face);
}
