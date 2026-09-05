package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.regionsolve.topology.Point2D;
import com.mineutze.worldgen.regionsolve.topology.VertexKey;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global, vertex-keyed, TWO-TIER decision store — same PERMANENT/TENTATIVE split as
 * {@link DecidedFaceStore}, one level down (a mesh vertex instead of a mesh face). See
 * that class's own doc for the full reasoning.
 *
 * <p>A vertex becomes eligible for PERMANENT promotion only once ALL 3 of its owning
 * faces are permanent — since a vertex's position depends on all 3 owning faces'
 * weights jointly (see this class's own original doc below), it can never be more
 * "settled" than the least-settled of its own 3 faces.
 *
 * <p>Must be persisted (PERMANENT tier only — see {@link DecidedFaceStore}'s own doc for
 * why tentative data is never persisted) — unlike a face's own biome+width, a vertex's
 * position is NOT a pure function of just its 3 owning faces' widths (it depends on
 * whichever OTHER faces happened to be in the specific batch that first computed it), so
 * it can't be safely recomputed later and trusted to match. See this whole redesign's own
 * plan doc for the rejected alternative (a fixed small calibration neighborhood, avoiding
 * the need for persistence entirely) and why it wasn't chosen.
 */
public final class DecidedVertexStore {

    private final Map<VertexKey, Point2D> permanent = new ConcurrentHashMap<>();
    private final Map<VertexKey, Point2D> tentative = new ConcurrentHashMap<>();
    private final VertexStoreBacking backing;

    public DecidedVertexStore() {
        this(VertexStoreBacking.NONE);
    }

    /** @param backing defaults to {@link VertexStoreBacking#NONE} on the no-arg constructor — every existing caller/test wants today's plain in-memory-only behavior, not a real store. Only ever consulted for the PERMANENT tier. */
    public DecidedVertexStore(VertexStoreBacking backing) {
        this.backing = backing;
    }

    /** Package-visible test support only. */
    int permanentCount() {
        return permanent.size();
    }

    /** Package-visible test support only. */
    int tentativeCount() {
        return tentative.size();
    }

    /** Permanent (checking {@code backing} on a memory miss) OR tentative — either is a real, currently-standing position, usable for answering a query. */
    public Optional<Point2D> get(VertexKey key) {
        Optional<Point2D> fromPermanent = getPermanent(key);
        if (fromPermanent.isPresent()) {
            return fromPermanent;
        }
        return Optional.ofNullable(tentative.get(key));
    }

    /** PERMANENT only — see {@link DecidedFaceStore#getPermanent}'s own doc for why cluster-growth needs this narrower read. */
    public Optional<Point2D> getPermanent(VertexKey key) {
        Point2D inMemory = permanent.get(key);
        if (inMemory != null) {
            return Optional.of(inMemory);
        }
        Optional<Point2D> fromBacking = backing.load(key);
        fromBacking.ifPresent(position -> permanent.putIfAbsent(key, position));
        return fromBacking;
    }

    /** Freely overwrites the tentative entry for each key — see {@link DecidedFaceStore#commitTentative}'s own doc, identical reasoning one level down. Fails loud if a key is already PERMANENT with a different value. */
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

    /** Promotes each entry to PERMANENT, forever — see {@link DecidedFaceStore#commitPermanent}'s own doc, identical reasoning one level down. */
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
