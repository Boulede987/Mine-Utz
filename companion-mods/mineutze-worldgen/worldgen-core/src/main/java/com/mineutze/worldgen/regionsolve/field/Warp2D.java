package com.mineutze.worldgen.regionsolve.field;

/**
 * Anything that bends {@code (x, z)} into a warped {@code (x, z)} —
 * implemented by both {@link DomainWarp} (flat amplitude) and {@link
 * AdaptiveDomainWarp} (spatially-varying amplitude), so {@link
 * DomainWarpAnalysis}'s fold check works identically on either: it only
 * ever finite-differences this one method, never assumes how the warp was
 * built.
 */
@FunctionalInterface
public interface Warp2D {
    double[] warp(double x, double z);
}
