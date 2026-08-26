package com.mineutze.worldgen.biomegraph;

/**
 * Per-biome shape identity for {@link com.mineutze.worldgen.regionsolve.GrowthEngine}'s
 * flood-fill: how tightly a growing patch must hold together, checked at claim-time
 * against the patch's own already-claimed cells (never relaxed to hit a size target —
 * same "drop, don't force" philosophy as everything else in the solver).
 *
 * <p>{@code minSameBiomeNeighbors} — a candidate cell needs at least this many
 * already-claimed neighbors, counted over the full 8-cell Moore neighborhood
 * (orthogonal + diagonal — see {@code GrowthEngine.satisfiesCohesion}'s doc for why
 * orthogonal-only doesn't work: a solid convex blob's exterior boundary cell only ever
 * touches ONE orthogonal interior cell, so any threshold above 1 checked orthogonal-only
 * makes growth dead-end immediately). 1 (the default, {@link #UNRESTRICTED}) is today's
 * behavior: any cell touching the patch anywhere can join, which allows a
 * single-cell-wide neck to snake the patch around an obstacle. 2+ forces chunkier growth
 * — a cell reachable only through a 1-wide corridor still touches just 1 Moore-neighbor,
 * so it never qualifies; a 2-wide-or-thicker extension does.
 *
 * <p>Only applies once a patch has grown past its own seed's immediate ring — see
 * {@code GrowthEngine.satisfiesCohesion}'s bootstrap exemption. A brand-new seed has
 * zero same-biome neighbors by definition, so any {@code minSameBiomeNeighbors} above 1
 * would make growth impossible from cell one without that exemption.
 *
 * <p>A directional-run knob (encouraging a biome to stretch elongated along one axis,
 * for rivers/coastlines) was designed and briefly wired up here, but removed before any
 * real profile used it successfully — deadlocked whenever its minor axis was >= 2 (the
 * perpendicular "thickness" it demanded can only exist once multiple cells have already
 * joined together, but growth claims one cell at a time, so the first cell extending a
 * new row/column can never have it pre-satisfied), and per explicit direction, per-biome
 * shape complexity beyond correct size + a natural (COMPACT) shape is deferred until a
 * fully working proof of concept exists. See git history if this needs resurrecting -
 * likely as a soft frontier-ordering preference instead of a hard per-cell gate.
 *
 * <p>Instances are plain values — two {@link BiomeNode}s wanting the same shape identity
 * just reference the same constant, no registry needed (same trick {@link AdjacencyRule}'s
 * blend width already relies on via {@code ModBiomeGraph}'s shared constant).
 */
public record GrowthCohesionProfile(int minSameBiomeNeighbors) {

    /** Today's status-quo growth behavior: any touching same-biome neighbor is enough. */
    public static final GrowthCohesionProfile UNRESTRICTED = new GrowthCohesionProfile(1);

    public GrowthCohesionProfile {
        if (minSameBiomeNeighbors < 0 || minSameBiomeNeighbors > 8) {
            throw new IllegalArgumentException("minSameBiomeNeighbors must be within [0, 8] (Moore/8-neighbor count), got " + minSameBiomeNeighbors);
        }
    }
}
