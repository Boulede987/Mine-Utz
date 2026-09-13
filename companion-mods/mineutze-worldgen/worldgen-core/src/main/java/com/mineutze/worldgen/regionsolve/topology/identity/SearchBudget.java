package com.mineutze.worldgen.regionsolve.topology.identity;

/**
 * Hard ceiling on {@link TriangleIdentitySizeAssignment#backtrack} node visits — safety
 * net independent of {@link TriangleIdentitySizeAssignment}'s own propagation quality, not
 * a replacement for it. {@code NODES_PER_FACE} calibrated by measurement, not guessed: a
 * real 238-free-face mesh's worst KNOWN-solvable instance (post-propagation-upgrade)
 * visits a few hundred nodes; {@code worldSeed=2034} against {@code ModBiomeGraph.OVERWORLD}
 * at {@code HEX_SIZE_BLOCKS=400} - the case that motivated this class - is the one real
 * pre-propagation pathological case on record, now solves well under the ceiling. 5,000
 * nodes/face leaves generous headroom above every measured legit solve while still failing
 * loud in under a second wall-clock for a mesh this size, not hanging indefinitely.
 */
final class SearchBudget {
    private static final long NODES_PER_FACE = 5_000L;

    private final long maxNodes;
    private long visitedNodes;

    private SearchBudget(long maxNodes) {
        this.maxNodes = maxNodes;
    }

    static SearchBudget forFaceCount(int freeFaceCount) {
        return new SearchBudget(Math.max(1, freeFaceCount) * NODES_PER_FACE);
    }

    void visitNode(int freeFaceCount) {
        visitedNodes++;
        if (visitedNodes > maxNodes) {
            throw new IllegalStateException(
                "TriangleIdentitySizeAssignment: backtracking exceeded " + maxNodes + " nodes ("
                    + NODES_PER_FACE + "/face x " + freeFaceCount + " free faces) without finding or ruling out "
                    + "a legal assignment - a genuinely pathological instance (or a real algorithmic regression), "
                    + "not something to wait out. Fails loud instead of hanging indefinitely."
            );
        }
    }
}
