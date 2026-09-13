package com.mineutze.worldgen.regionsolve.topology.mesh;

import java.util.Comparator;
import java.util.List;

/**
 * A mesh vertex's stable, GLOBAL identity: sorted triple of its 3 owning faces' {@link
 * HexGrid.AxialPos}. Exactly 3 faces meet at every interior hex-mesh vertex (see {@code
 * HexMeshBuilderTest.everyFullyInteriorVertexHasExactlyThreeIncidentFaces} for the
 * by-construction proof), and that triple is unique to one physical point regardless of
 * which bounding box discovered it — unlike a raw {@code Point2D} coordinate (floating-
 * point drift across independently computed copies) or a build-local vertex array index
 * (not stable across bounding boxes, same reason {@link HexFace} moved off a build-local
 * {@code seedIndex}). Sorted (not constructor-argument-ordered) so two faces discovering
 * this vertex via different traversal orders still produce the identical key - same
 * order-independence discipline {@link NodeEdge} established for edges, one level up.
 */
public record VertexKey(HexGrid.AxialPos a, HexGrid.AxialPos b, HexGrid.AxialPos c) {

    private static final Comparator<HexGrid.AxialPos> ORDER =
        Comparator.comparingInt(HexGrid.AxialPos::q).thenComparingInt(HexGrid.AxialPos::r);

    public VertexKey {
        List<HexGrid.AxialPos> sorted = List.of(a, b, c).stream().sorted(ORDER).toList();
        a = sorted.get(0);
        b = sorted.get(1);
        c = sorted.get(2);
    }

    /** Convenience constructor from the 3 owning {@link HexFace}s directly, sourcing each one's own {@link HexFace#axialPos()}. */
    public static VertexKey of(HexFace faceA, HexFace faceB, HexFace faceC) {
        return new VertexKey(faceA.axialPos(), faceB.axialPos(), faceC.axialPos());
    }
}
