/**
 * Answers a different question than the rest of this codebase: not "does the live
 * generator produce a valid world," but "does SOME algorithm exist that can, given this
 * exact rule set, forever" - a real existence proof, independent of whatever the live
 * generator does or doesn't do (2026-09 user directive: "our proof algorithm MUST NOT
 * rely on the actual algorithm at all, even if it does not seem so").
 *
 * <p><b>Standing rule for this package, binding on any future change here:</b> nothing in
 * {@code coherence} may depend on {@code regionsolve} (the live solver/geometry path),
 * {@code HexMeshBuilder}/{@code TriangleAdjacency} (the live mesh), or construct a real
 * {@code biomegraph.BiomeGraph} (its own constructor runs bridgeability/connectivity
 * assumptions that are themselves part of the live algorithm's worldview, not neutral data
 * access). The only production dependency allowed is {@code biomegraph.BiomeGraphData}/
 * {@code BiomeGraphJson}/{@code BiomeId} treated as plain rule DATA - the input under
 * test, not an algorithm.
 *
 * <p><b>The technique:</b> reduce "infinite" to "periodic" - a real, standard construction
 * (subshift-of-finite-type / Wang-tile style), not hand-waved: if a finite, toroidal
 * (wraparound) patch satisfies every constraint including across its own wraparound seam,
 * repeating that patch forever in every direction is a valid infinite world, because every
 * cell's neighborhood in the infinite repeat is identical to its neighborhood on the torus.
 * A success is therefore a complete, definitive proof. A failure at whatever sizes were
 * tried is NOT a proof of impossibility - only bounded search, stated honestly in every
 * report this package writes, never glossed over.
 *
 * <p><b>The substrate:</b> deliberately NOT the live hex mesh (see {@link
 * com.mineutze.worldgen.coherence.PeriodicRowLayout}'s own doc) - a fixed-degree lattice
 * with self-adjacency-as-clustering is a hex-mesh-specific workaround for its own fixed
 * cell size, not a property of the abstract problem. Here, one biome instance is exactly
 * one region, sized directly to whatever it needs - no clustering concept, no self-
 * adjacency exception (a biome is never legally adjacent to itself, unconditionally - see
 * {@link com.mineutze.worldgen.coherence.RawRuleTable#isLegal}).
 */
package com.mineutze.worldgen.coherence;
