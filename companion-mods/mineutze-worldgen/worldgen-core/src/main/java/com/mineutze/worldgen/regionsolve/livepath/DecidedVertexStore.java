package com.mineutze.worldgen.regionsolve.livepath;

import com.mineutze.worldgen.regionsolve.topology.mesh.VertexKey;

import com.mineutze.worldgen.regionsolve.topology.Point2D;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global, vertex-keyed, TWO-TIER decision store - same PERMANENT/TENTATIVE split as
 * {@link DecidedFaceStore}, one level down (mesh vertex instead of mesh face). See that
 * class's doc for full reasoning.
 *
 * <p>A vertex becomes eligible for PERMANENT promotion only once ALL 3 owning faces are
 * permanent — since a vertex's position depends on all 3 owning faces' weights jointly,
 * it can never be more "settled" than the least-settled of its own 3 faces.
 *
 * <p>Must be persisted (PERMANENT tier only - see {@link DecidedFaceStore}'s doc for why
 * tentative is never persisted) - unlike a face's biome+width, a vertex's position isn't
 * a pure function of just its 3 owning faces' widths (depends on whichever OTHER faces
 * were in the batch that first computed it), so it can't be safely recomputed later and
 * trusted to match. See this redesign's plan doc for the rejected alternative (fixed
 * small calibration neighborhood, avoiding persistence entirely) and why not chosen.
 */
public final class DecidedVertexStore {

    private final Map<VertexKey, Point2D> permanent = new ConcurrentHashMap<>();
    private final Map<VertexKey, Point2D> tentative = new ConcurrentHashMap<>();
    private final VertexStoreBacking backing;

    public DecidedVertexStore() {
        this(VertexStoreBacking.NONE);
    }

    /** @param backing defaults to {@link VertexStoreBacking#NONE} on no-arg constructor - every caller/test wants plain in-memory behavior, not a real store. Only consulted for PERMANENT tier. */
    public DecidedVertexStore(VertexStoreBacking backing) {
        this.backing = backing;
    }

    /** Test support only. */
    int permanentCount() {
        return permanent.size();
    }

    /** Test support only. */
    int tentativeCount() {
        return tentative.size();
    }

    /** Permanent (checks {@code backing} on memory miss) OR tentative - either is real, currently-standing, usable for a query. */
    public Optional<Point2D> get(VertexKey key) {
        Optional<Point2D> fromPermanent = getPermanent(key);
        if (fromPermanent.isPresent()) {
            return fromPermanent;
        }
        return Optional.ofNullable(tentative.get(key));
    }

    /** PERMANENT only - see {@link DecidedFaceStore#getPermanent}'s doc for why cluster-growth needs this narrower read. */
    public Optional<Point2D> getPermanent(VertexKey key) {
        Point2D inMemory = permanent.get(key);
        if (inMemory != null) {
            return Optional.of(inMemory);
        }
        Optional<Point2D> fromBacking = backing.load(key);
        fromBacking.ifPresent(position -> permanent.putIfAbsent(key, position));
        return fromBacking;
    }

    /** Freely overwrites tentative entry per key - see {@link DecidedFaceStore#commitTentative}'s doc, same reasoning one level down. Fails loud if key already PERMANENT with a different value. */
    public void commitTentative(Map<VertexKey, Point2D> newlyDecided) {
        for (Map.Entry<VertexKey, Point2D> entry : newlyDecided.entrySet()) {
            Point2D existingPermanent = permanent.get(entry.getKey());
            if (existingPermanent != null && !existingPermanent.equals(entry.getValue())) {
                throw new IllegalStateException(
                    "DecidedVertexStore: vertex " + entry.getKey() + " is already PERMANENT as " + existingPermanent
                        + ", cannot tentatively re-commit as " + entry.getValue() + " - a permanent vertex must never be revised"
                );
            }
            tentative.put(entry.getKey(), entry.getValue());
        }
    }

    /** Promotes each entry to PERMANENT, forever - see {@link DecidedFaceStore#commitPermanent}'s doc, same reasoning one level down. */
    public void commitPermanent(Map<VertexKey, Point2D> newlyPermanent) {
        for (Map.Entry<VertexKey, Point2D> entry : newlyPermanent.entrySet()) {
            Point2D existing = permanent.putIfAbsent(entry.getKey(), entry.getValue());
            if (existing == null) {
                tentative.remove(entry.getKey());
                backing.save(entry.getKey(), entry.getValue());
            } else if (!existing.equals(entry.getValue())) {
                throw new IllegalStateException(
                    "DecidedVertexStore: vertex " + entry.getKey() + " already PERMANENT as " + existing
                        + ", cannot re-commit as " + entry.getValue() + " - two independent solves disagreed about the same physical vertex's own final answer"
                );
            }
        }
    }
}
