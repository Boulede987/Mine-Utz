package com.mineutze.worldgen.biomesource;

/**
 * Tectonic's own internal terrain-shape "region" templates -- names match Tectonic's
 * real resourcepack paths verbatim (region/club.json, region/club_weak.json,
 * region/heart.json, region/spade.json, region/spade_weak.json, region/diamond.json).
 * See TERRAIN_COHERENCE_DESIGN.md Sec 4b for how these were mapped and Sec 4c for why a
 * biome declares a weighted SET of these (RegionFamilyWeight) rather than one fixed pick.
 *
 * bracketCoordinate/selectorCoordinate are the two spline-input values Phase 2's
 * RegionIntentDensityFunction (not yet built) will need to emit so an overridden
 * terrain_spline/offset/regions.json picks this exact family: bracketCoordinate sits
 * outside the real file's bracket breakpoints (roughly [-0.02, 0.04] on
 * tectonic:noise/continent/erosion) so no accidental blending happens on that axis,
 * while selectorCoordinate sits exactly on the real file's inner breakpoints
 * (-0.1 / 0 / 0.1 on tectonic:noise/region_selector) so an unblended pick lands cleanly.
 * Unused until that later stage -- defined now so BiomeConfig has a real type to
 * reference in this stage.
 */
public enum RegionFamily {
    CLUB(-0.5, -0.1),
    CLUB_WEAK(-0.5, 0.0),
    HEART(-0.5, 0.1),
    SPADE(0.5, -0.1),
    SPADE_WEAK(0.5, 0.0),
    DIAMOND(0.5, 0.1);

    private final double bracketCoordinate;
    private final double selectorCoordinate;

    RegionFamily(double bracketCoordinate, double selectorCoordinate) {
        this.bracketCoordinate = bracketCoordinate;
        this.selectorCoordinate = selectorCoordinate;
    }

    public double bracketCoordinate() {
        return bracketCoordinate;
    }

    public double selectorCoordinate() {
        return selectorCoordinate;
    }
}
