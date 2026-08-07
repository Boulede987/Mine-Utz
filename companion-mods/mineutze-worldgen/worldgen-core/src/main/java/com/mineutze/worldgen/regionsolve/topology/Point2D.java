package com.mineutze.worldgen.regionsolve.topology;

/**
 * A 2D point in macro-cell space, using doubles rather than
 * {@link com.mineutze.worldgen.regionsolve.MacroCellPos}'s ints because
 * Delaunay triangulation's circumcircle test needs the precision a
 * macro-cell grid position can't give.
 */
public record Point2D(double x, double z) {
}
