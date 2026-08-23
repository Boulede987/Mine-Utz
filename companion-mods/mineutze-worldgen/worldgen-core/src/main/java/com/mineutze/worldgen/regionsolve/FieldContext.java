package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.regionsolve.field.AdaptiveDomainWarp;
import com.mineutze.worldgen.regionsolve.topology.Point2D;

import java.util.Map;

/**
 * The warp field plus every relevant face's centroid and current radius — the shared
 * per-solve state both {@link TriangleFieldGeometryRealization} (radius correction) and
 * {@link LegalityConstrainedRasterizer} (final rasterization) score candidates against.
 * Package-private: assembled once per region solve by {@code
 * TriangleFieldGeometryRealization.buildField}, never constructed anywhere else.
 */
record FieldContext(AdaptiveDomainWarp warp, Map<Integer, Point2D> centroids, Map<Integer, Double> radii) {
}
