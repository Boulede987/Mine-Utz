package com.mineutze.worldgen.regionsolve.topology.placement;

import com.mineutze.worldgen.regionsolve.topology.EdgeDisplacement;

import com.mineutze.worldgen.regionsolve.topology.HexGeometry;

import com.mineutze.worldgen.regionsolve.topology.Point2D;

/**
 * The pure power-diagram math {@link WeightedVertexPlacement} is built on - a face's
 * weight from its target width, and the radical center of 3 weighted face centers. Zero
 * mesh/face-index concept here, only points and weights - see {@link
 * WeightedVertexPlacement} class doc for the geometric theory this implements.
 */
final class PowerDiagramMath {

    /** Below this magnitude, {@link #radicalCenter}'s 2x2 system determinant treated as singular (3 face centers collinear) not divided-by-near-zero - should never happen for a real hex mesh vertex (see that method's doc). */
    private static final double COLLINEAR_DETERMINANT_EPSILON = 1e-9;

    private PowerDiagramMath() {
    }

    /**
     * Power-diagram weight for a face targeting {@code widthBlocks} — hex's assigned width
     * treated as a regular hexagon's corner-to-corner span (same convention this codebase
     * uses throughout): circumradius = width/2, weight = circumradius² — standard power-
     * diagram weight for a "circle" of that radius.
     */
    static double powerWeight(int widthBlocks) {
        double circumradius = HexGeometry.circumradiusForWidth(widthBlocks);
        return circumradius * circumradius;
    }

    /**
     * Radical center of 3 weighted points — the unique point X with equal "power distance"
     * ({@code |X-P|² - weight}) to all three, found by solving the 2x2 linear system that
     * falls out of subtracting pairs of the power-distance equalities (cancels the
     * quadratic {@code |X|²} term, same structure as an ordinary circumcenter, generalized
     * with weights). When all three weights are equal, reduces exactly to the ordinary
     * circumcenter — see {@link WeightedVertexPlacement} class doc for why that's the
     * correct "nothing to correct" baseline, not coincidence.
     */
    static Point2D radicalCenter(Point2D centerA, double weightA, Point2D centerB, double weightB, Point2D centerC, double weightC) {
        Point2D bRelative = vectorFrom(centerA, centerB);
        Point2D cRelative = vectorFrom(centerA, centerC);
        double squaredMagnitudeA = centerA.x() * centerA.x() + centerA.z() * centerA.z();
        double squaredMagnitudeB = centerB.x() * centerB.x() + centerB.z() * centerB.z();
        double squaredMagnitudeC = centerC.x() * centerC.x() + centerC.z() * centerC.z();
        double rightHandSideB = (weightA - weightB + squaredMagnitudeB - squaredMagnitudeA) / 2.0;
        double rightHandSideC = (weightA - weightC + squaredMagnitudeC - squaredMagnitudeA) / 2.0;

        double determinant = bRelative.x() * cRelative.z() - bRelative.z() * cRelative.x();
        if (Math.abs(determinant) < COLLINEAR_DETERMINANT_EPSILON) {
            throw new IllegalStateException(
                "PowerDiagramMath: face centers " + centerA + ", " + centerB + ", " + centerC + " are collinear - not a "
                    + "valid hex mesh vertex (every real vertex has 3 non-collinear face centers around it)"
            );
        }
        double x = (rightHandSideB * cRelative.z() - bRelative.z() * rightHandSideC) / determinant;
        double z = (bRelative.x() * rightHandSideC - rightHandSideB * cRelative.x()) / determinant;
        return new Point2D(x, z);
    }

    /** {@code to - from}, as a {@link Point2D} used purely as a 2D vector here - see {@code EdgeDisplacement.vectorFrom} for why this class reuses {@link Point2D} instead of a separate vector type. */
    private static Point2D vectorFrom(Point2D from, Point2D to) {
        return new Point2D(to.x() - from.x(), to.z() - from.z());
    }
}
