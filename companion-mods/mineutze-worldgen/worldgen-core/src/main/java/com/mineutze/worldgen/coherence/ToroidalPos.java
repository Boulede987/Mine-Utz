package com.mineutze.worldgen.coherence;

/**
 * A position in a FINITE, PERIODIC (toroidal) hex-like grid - own copy of the axial
 * convention already used elsewhere in this project ({@code reachability.AxialPos}), not a
 * cross-package dependency (this package stays self-contained on purpose, same discipline
 * as everywhere else in {@code coherence} - see its own package doc). Wrapping (modulo the
 * grid's own width/height) is {@link ToroidalGrid}'s job, not this record's - a bare
 * (q,r) pair here is not yet normalized into any particular grid's own bounds.
 */
record ToroidalPos(int q, int r) {
}
