package com.mineutze.worldgen.regionsolve.mesh;

/** An unordered pair of face ids, canonicalized the same way {@link com.mineutze.worldgen.regionsolve.topology.NodeEdge} canonicalizes vertex pairs. */
public record FacePair(int a, int b) {

    public static FacePair of(int a, int b) {
        if (a == b) {
            throw new IllegalArgumentException("A face cannot be paired with itself: " + a);
        }
        return a <= b ? new FacePair(a, b) : new FacePair(b, a);
    }
}
