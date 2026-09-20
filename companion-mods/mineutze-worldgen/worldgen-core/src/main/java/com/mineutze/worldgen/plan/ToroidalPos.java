package com.mineutze.worldgen.plan;

/**
 * A position in a FINITE, PERIODIC (toroidal) hex-like grid, axial (q,r). Wrapping (modulo the
 * grid's own width/height) is {@link ToroidalGrid}'s job, not this record's - a bare
 * (q,r) pair here is not yet normalized into any particular grid's own bounds.
 */
record ToroidalPos(int q, int r) {
}
