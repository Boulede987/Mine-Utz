/**
 * Investigates a real question the {@code coherence} package deliberately does NOT answer
 * (see that package's own doc): given the abstract rule graph is provably coherent, does the
 * LIVE generator's own promotion/commitment mechanics (undecided -> tentative -> permanent,
 * two-attempt cluster-growth CSP - see {@code TriangleFieldGeometryRealization.realizeCluster}'s
 * own doc) ever actually drive a real joint free-cluster into a state with no legal solution -
 * and if so, under what conditions.
 *
 * <p><b>Different independence rule than {@code coherence}, on purpose:</b> that package
 * stays independent of live-algorithm ASSUMPTIONS because it's proving a property of the
 * abstract rule graph alone. This package is explicitly modeling the live algorithm's own
 * MECHANICS, so it must be FAITHFUL to them, not independent - {@link
 * com.mineutze.worldgen.reachability.HexPromotionSimulator}'s doc states exactly which real
 * mechanics are reproduced and which are deliberately left out (geometry, size/area layer,
 * vertex-triple promotion - a different, still-open concern). Still independent of the real
 * CODE (no dependency on {@code regionsolve}, {@code HexMeshBuilder}, {@code
 * TriangleIdentitySizeAssignment}, {@code SeedMeshCache}) - reuses only {@code
 * coherence.RawRuleTable} for adjacency-legality data, same discipline as everywhere else in
 * this project: reused rule DATA, never live algorithm CODE.
 *
 * <p><b>Real motivating finding (2026-09-16):</b> {@code DeadEndFrequencySimulation}'s
 * RING_PROBE (single origin, direct 6-neighbor-then-center worst case) found 0/100,000 real
 * dead ends; its new STRUCTURE_RING_SWEEP (many origins sharing one cache, dense concentric
 * rings) found 11/12. Every one of those 11 real failures shows a JOINT free-cluster of
 * 46-57 faces failing together, not a single face boxed in by up to 6 pre-decided neighbors
 * (the shape {@code BiomeGraphCompletenessReport}'s 8,139 abstract gaps describe) - a
 * genuinely different, larger-scale mechanism this package investigates directly.
 */
package com.mineutze.worldgen.reachability;
