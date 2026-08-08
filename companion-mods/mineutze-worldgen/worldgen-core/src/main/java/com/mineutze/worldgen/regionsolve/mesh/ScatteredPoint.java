package com.mineutze.worldgen.regionsolve.mesh;

import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.regionsolve.topology.Point2D;

import java.util.Optional;

/**
 * One input point to the fine mesh triangulation. {@code germColor} is
 * present for a point that already carries an identity before any face
 * exists — an already-solved neighbor's fixed boundary cell, or a coarse
 * seed the CSP stage already colored — and empty for a purely structural
 * point (boundary ring, filler) whose identity is decided later by
 * {@code PatchMerger} growth.
 */
public record ScatteredPoint(Point2D position, Optional<BiomeId> germColor) {
}
