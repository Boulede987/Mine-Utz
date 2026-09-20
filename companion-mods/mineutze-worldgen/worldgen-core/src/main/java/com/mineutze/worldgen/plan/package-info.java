/**
 * Per-world biome plan: solves a biome pattern on a hex torus, warps it into natural borders,
 * renders and verifies a raster the game reads. Entry point {@link
 * com.mineutze.worldgen.plan.WorldBiomePlan}.
 *
 * <p>Standing rule: same world seed gives same map on any JVM, machine, day. No {@code Math.sin/
 * cos/exp/log/pow/hypot}, no unseeded random, no clock in production code - enforced by {@code
 * ReproducibilityRulesTest}.
 */
package com.mineutze.worldgen.plan;
