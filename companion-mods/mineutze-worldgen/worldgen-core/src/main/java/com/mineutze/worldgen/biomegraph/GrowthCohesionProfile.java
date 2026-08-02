package com.mineutze.worldgen.biomegraph;

/**
 * Per-biome shape identity for {@link com.mineutze.worldgen.regionsolve.GrowthEngine}'s
 * flood-fill: how tightly a growing patch must hold together, checked at
 * claim-time against the patch's own already-claimed cells (never relaxed
 * to hit a size target — same "drop, don't force" philosophy as everything
 * else in the solver). Two independent knobs, both optional:
 *
 * <ul>
 *   <li>{@code minSameBiomeNeighbors} — a candidate cell needs at least this
 *       many already-claimed neighbors, counted over the full 8-cell Moore
 *       neighborhood (orthogonal + diagonal — see
 *       {@code GrowthEngine.satisfiesCohesion}'s doc for why orthogonal-only
 *       doesn't work: a solid convex blob's exterior boundary cell only ever
 *       touches ONE orthogonal interior cell, so any threshold above 1
 *       checked orthogonal-only makes growth dead-end immediately). 1 (the
 *       default, {@link #UNRESTRICTED}) is today's behavior: any cell
 *       touching the patch anywhere can join, which allows a single-cell-wide
 *       neck to snake the patch around an obstacle. 2+ forces chunkier growth
 *       — a cell reachable only through a 1-wide corridor still touches just
 *       1 Moore-neighbor, so it never qualifies; a 2-wide-or-thicker
 *       extension does.
 *   <li>{@code directionalRunMajor}/{@code directionalRunMinor} — 0 disables
 *       this half of the check. Above 0, a candidate cell needs a
 *       contiguous same-biome run (through itself) of at least
 *       {@code directionalRunMajor} cells along one grid axis AND at least
 *       {@code directionalRunMinor} along the other — checked for both axis
 *       assignments, so it doesn't care which of x/z is the long one.
 *       Encourages a biome to grow elongated along whichever axis it
 *       started reaching along (rivers, coastlines), while
 *       {@code directionalRunMinor} still requires some cross-section
 *       thickness so it can't degenerate into an infinitely thin line.
 * </ul>
 *
 * Both checks apply only once a patch has grown past its own seed's
 * immediate ring — see {@code GrowthEngine.satisfiesCohesion}'s bootstrap
 * exemption. A brand-new seed has zero same-biome neighbors by definition,
 * so any {@code minSameBiomeNeighbors} above 1 would make growth
 * impossible from cell one without that exemption.
 *
 * Instances are plain values — two {@link BiomeNode}s wanting the same
 * shape identity just reference the same constant, no registry needed
 * (same trick {@link AdjacencyRule}'s blend width already relies on via
 * {@code ModBiomeGraph}'s shared constant).
 */
public record GrowthCohesionProfile(int minSameBiomeNeighbors, int directionalRunMajor, int directionalRunMinor) {

    /** Today's status-quo growth behavior: any touching same-biome neighbor is enough, no directional preference. */
    public static final GrowthCohesionProfile UNRESTRICTED = new GrowthCohesionProfile(1, 0, 0);

    public GrowthCohesionProfile {
        if (minSameBiomeNeighbors < 0 || minSameBiomeNeighbors > 8) {
            throw new IllegalArgumentException("minSameBiomeNeighbors must be within [0, 8] (Moore/8-neighbor count), got " + minSameBiomeNeighbors);
        }
        if (directionalRunMajor < 0 || directionalRunMinor < 0) {
            throw new IllegalArgumentException("directionalRunMajor/Minor must not be negative, got " + directionalRunMajor + "/" + directionalRunMinor);
        }
        if (directionalRunMajor == 0 && directionalRunMinor != 0) {
            throw new IllegalArgumentException("directionalRunMinor must be 0 when directionalRunMajor is 0 (disabled together), got minor=" + directionalRunMinor);
        }
        if (directionalRunMajor > 0 && (directionalRunMinor < 1 || directionalRunMinor > directionalRunMajor)) {
            throw new IllegalArgumentException(
                "directionalRunMinor must be within [1, directionalRunMajor] when enabled, got major=" + directionalRunMajor + " minor=" + directionalRunMinor);
        }
    }
}
