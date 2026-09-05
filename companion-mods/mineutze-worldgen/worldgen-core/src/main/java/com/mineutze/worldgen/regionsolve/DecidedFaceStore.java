package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.regionsolve.topology.HexGrid;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global, face-keyed, TWO-TIER decision store — replaces the per-{@code RegionPos}
 * ownership this codebase used to commit face identity through (see
 * doc/decisions/2026-08-29-remove-region-stitching.md), then a further redesign to
 * close the "how much already-permanent context can pile up around a not-yet-queried
 * face" gap that a real dead-end crash exposed (see this whole redesign's own plan doc
 * for the full incident and the reasoning behind this two-tier model).
 *
 * <p><b>PERMANENT</b> — a face becomes permanent the moment it's the ACTUAL target of a
 * real query (see {@code SeedMeshCache}'s own doc): Minecraft already used this exact
 * value to place real terrain, so it can never change again, matching this store's own
 * previous single-tier "decided forever" contract exactly. Persisted via {@code backing}.
 *
 * <p><b>TENTATIVE</b> — every OTHER face a cluster solve touches (real CSP context, but
 * never the literal query target) — a real, currently-standing value, freely
 * overwritable by a LATER solve that needs to revise it, since nothing irrevocable has
 * happened for it yet. Never persisted (in-memory only, cheaply re-derived if lost on
 * restart — it was never authoritative in the first place).
 *
 * <p>{@link #get} reads either tier (permanent preferred) for ordinary lookups (a
 * tentative value is just as usable for answering a query — see {@code
 * TriangleFieldGeometryRealization#tryFastPathLookup}'s own doc); {@link #getPermanent}
 * is the narrower read cluster-growth needs to decide "is this a hard wall, or can I
 * still revise it."
 */
public final class DecidedFaceStore {

    /** A face's own committed CSP decision (tentative or permanent): which biome, and how wide it was assigned. */
    public record DecidedFace(BiomeId biomeId, int widthBlocks) {
    }

    private final Map<HexGrid.AxialPos, DecidedFace> permanent = new ConcurrentHashMap<>();
    private final Map<HexGrid.AxialPos, DecidedFace> tentative = new ConcurrentHashMap<>();
    private final FaceStoreBacking backing;

    public DecidedFaceStore() {
        this(FaceStoreBacking.NONE);
    }

    /** @param backing defaults to {@link FaceStoreBacking#NONE} on the no-arg constructor — every existing caller/test wants today's plain in-memory-only behavior, not a real store. Only ever consulted for the PERMANENT tier — tentative data is never persisted, see this class's own doc. */
    public DecidedFaceStore(FaceStoreBacking backing) {
        this.backing = backing;
    }

    /** Package-visible test support only — how many faces are currently PERMANENT (proving a cluster solve promoted only what it should have). */
    int permanentCount() {
        return permanent.size();
    }

    /** Package-visible test support only. */
    int tentativeCount() {
        return tentative.size();
    }

    /** Permanent (checking {@code backing} on a memory miss) OR tentative — either is a real, currently-standing value, usable for answering a query. Permanent wins if somehow both are present (shouldn't happen — {@link #commitPermanent} always removes the tentative entry when promoting). */
    public Optional<DecidedFace> get(HexGrid.AxialPos axialPos) {
        Optional<DecidedFace> fromPermanent = getPermanent(axialPos);
        if (fromPermanent.isPresent()) {
            return fromPermanent;
        }
        return Optional.ofNullable(tentative.get(axialPos));
    }

    /** PERMANENT only — the read cluster-growth needs to decide "is this a hard wall (never revisable) or can I still pull it in and revise it." Checks {@code backing} on a memory miss, same load-through discipline as before. */
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
     * Freely overwrites the tentative entry for each key — no conflict check against a
     * PRIOR tentative value (that's the whole point: tentative is meant to be revised).
     * Fails loud if a key is already PERMANENT with a DIFFERENT value — that would mean a
     * caller tried to tentatively "revise" something that's supposed to be immutable,
     * which should be structurally impossible given cluster-growth's own discipline (a
     * permanent face is never pulled into a cluster's own free set) — a real bug if it
     * ever fires, not an expected occurrence.
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
     * Promotes each entry to PERMANENT, forever — fails loud if already permanent with a
     * DIFFERENT value (a real determinism bug: two independent solves disagreed about the
     * same physical face's own final, irrevocable answer). Already-permanent with the SAME
     * value is a harmless no-op. Removes the tentative entry (if any) once promoted, and
     * saves to {@code backing} immediately, durable before it could ever be evicted from
     * memory — matches this class's own previous single-tier discipline exactly, now
     * scoped to only the faces actually promoted.
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
