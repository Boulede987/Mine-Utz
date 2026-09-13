package com.mineutze.worldgen.regionsolve.topology;

/**
 * Regular hexagon area/circumradius conversions - one shared formula both {@link
 * WeightedVertexPlacement} and {@link TriangleIdentitySizeAssignment} need, instead of two
 * independently-maintained copies (what this codebase had before this class was extracted).
 */
public final class HexGeometry {

    /** Regular hexagon area = this coefficient times circumradius squared. */
    private static final double AREA_COEFFICIENT = 3.0 * Math.sqrt(3.0) / 2.0;

    private HexGeometry() {
    }

    public static double areaForCircumradius(double circumradius) {
        return AREA_COEFFICIENT * circumradius * circumradius;
    }

    public static double circumradiusForArea(double areaBlocks2) {
        return Math.sqrt(areaBlocks2 / AREA_COEFFICIENT);
    }

    /** A hex's own corner-to-corner width, halved - the standard width-to-circumradius convention this codebase uses. */
    public static double circumradiusForWidth(double widthBlocks) {
        return widthBlocks / 2.0;
    }

    public static double widthForCircumradius(double circumradius) {
        return 2 * circumradius;
    }
}
