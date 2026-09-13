package com.mineutze.worldgen.regionsolve.livepath;

import com.mineutze.worldgen.regionsolve.topology.mesh.HexGrid;

import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global, face-keyed, TWO-TIER decision store - replaces per-{@code RegionPos} ownership
 * this codebase used for face identity (see doc/decisions/2026-08-29-remove-region-
 * stitching.md), then a further redesign closing the "how much already-permanent context
 * piles up around a not-yet-queried face" gap a real dead-end crash exposed (see this
 * redesign's own plan doc for the full incident/reasoning).
 *
 * <p><b>PERMANENT</b> — a face becomes permanent the moment it's the ACTUAL target of a
 * real query (see {@code SeedMeshCache}'s own doc): Minecraft already used this value to
 * place real terrain, so it can never change - matches this store's old single-tier
 * "decided forever" contract. Persisted via {@code backing}.
 *
 * <p><b>TENTATIVE</b> — every OTHER face a cluster solve touches (real CSP context, never
 * the literal query target) - real, currently-standing value, freely overwritable by a
 * LATER solve since nothing's irrevocable yet. Never persisted (in-memory only, cheaply
 * re-derived if lost on restart - never authoritative anyway).
 *
 * <p>{@link #get} reads either tier (permanent preferred) for ordinary lookups (tentative
 * is just as usable - see {@code TriangleFieldGeometryRealization#tryFastPathLookup}'s
 * own doc); {@link #getPermanent} is the narrower read cluster-growth needs to decide
 * "hard wall, or still revisable."
 */
public final class DecidedFaceStore {

    /** A face's committed CSP decision (tentative or permanent): which biome, how wide. */
    public record DecidedFace(BiomeId biomeId, int widthBlocks) {
    }

    private final Map<HexGrid.AxialPos, DecidedFace> permanent = new ConcurrentHashMap<>();
    private final Map<HexGrid.AxialPos, DecidedFace> tentative = new ConcurrentHashMap<>();
    private final FaceStoreBacking backing;

    public DecidedFaceStore() {
        this(FaceStoreBacking.NONE);
    }

    /** @param backing defaults to {@link FaceStoreBacking#NONE} on no-arg constructor - every caller/test wants plain in-memory behavior, not a real store. Only consulted for PERMANENT tier - tentative never persisted, see this class's doc. */
    public DecidedFaceStore(FaceStoreBacking backing) {
        this.backing = backing;
    }

    /** Test support only - how many faces currently PERMANENT (proves a cluster solve promoted only what it should). */
    int permanentCount() {
        return permanent.size();
    }

    /** Test support only. */
    int tentativeCount() {
        return tentative.size();
    }

    /** Permanent (checks {@code backing} on memory miss) OR tentative - either is real, currently-standing, usable for a query. Permanent wins if both present (shouldn't happen - {@link #commitPermanent} always removes tentative entry when promoting). */
    public Optional<DecidedFace> get(HexGrid.AxialPos axialPos) {
        Optional<DecidedFace> fromPermanent = getPermanent(axialPos);
        if (fromPermanent.isPresent()) {
            return fromPermanent;
        }
        return Optional.ofNullable(tentative.get(axialPos));
    }

    /** PERMANENT only - read cluster-growth needs to decide "hard wall (never revisable) or still revisable." Checks {@code backing} on memory miss, same load-through discipline. */
    public Optional<DecidedFace> getPermanent(HexGrid.AxialPos axialPos) {
        DecidedFace inMemory = permanent.get(axialPos);
        if (inMemory != null) {
            return Optional.of(inMemory);
        }
        Optional<DecidedFace> fromBacking = backing.load(axialPos);
        fromBacking.ifPresent(face -> permanent.putIfAbsent(axialPos, face));
        return fromBacking;
    }

    /**
     * Freely overwrites tentative entry per key - no conflict check vs prior tentative
     * value (tentative's meant to be revised). Fails loud if key already PERMANENT with a
     * DIFFERENT value - would mean a caller tried to revise something immutable,
     * structurally impossible given cluster-growth's discipline (permanent face never
     * pulled into a cluster's free set) - real bug if it fires.
     */
    public void commitTentative(Map<HexGrid.AxialPos, DecidedFace> newlyDecided) {
        for (Map.Entry<HexGrid.AxialPos, DecidedFace> entry : newlyDecided.entrySet()) {
            DecidedFace existingPermanent = permanent.get(entry.getKey());
            if (existingPermanent != null && !existingPermanent.equals(entry.getValue())) {
                throw new IllegalStateException(
                    "DecidedFaceStore: face " + entry.getKey() + " is already PERMANENT as " + existingPermanent
                        + ", cannot tentatively re-commit as " + entry.getValue() + " - a permanent face must never be pulled into a cluster's own free set"
                );
            }
            tentative.put(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Promotes each entry to PERMANENT, forever - fails loud if already permanent with a
     * DIFFERENT value (real determinism bug: two solves disagreed on the same face's final
     * answer). Already-permanent with SAME value is a harmless no-op. Removes tentative
     * entry (if any) once promoted, saves to {@code backing} immediately, durable before
     * eviction - matches this class's old single-tier discipline, now scoped to just the
     * promoted faces.
     */
    public void commitPermanent(Map<HexGrid.AxialPos, DecidedFace> newlyPermanent) {
        for (Map.Entry<HexGrid.AxialPos, DecidedFace> entry : newlyPermanent.entrySet()) {
            DecidedFace existing = permanent.putIfAbsent(entry.getKey(), entry.getValue());
            if (existing == null) {
                tentative.remove(entry.getKey());
                backing.save(entry.getKey(), entry.getValue());
            } else if (!existing.equals(entry.getValue())) {
                throw new IllegalStateException(
                    "DecidedFaceStore: face " + entry.getKey() + " already PERMANENT as " + existing
                        + ", cannot re-commit as " + entry.getValue() + " - two independent solves disagreed about the same physical face's own final answer"
                );
            }
        }
    }
}
