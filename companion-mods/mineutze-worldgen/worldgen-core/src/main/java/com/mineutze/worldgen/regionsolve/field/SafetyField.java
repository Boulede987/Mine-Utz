package com.mineutze.worldgen.regionsolve.field;

/**
 * Precomputes, over a bounded square, how much of a reference warp's own
 * strength is safe to use at each point — 1.0 where it's nowhere near
 * folding, ramping smoothly down toward 0.0 only in the specific pockets
 * where it would. Backing {@link AdaptiveDomainWarp}: rather than picking
 * one flat amplitude that must stay safe EVERYWHERE (capping how strong the
 * wobble can ever be, even where there was no risk at all), the strong
 * amplitude only gets dialed back exactly where it's actually needed.
 *
 * <p>Computed on a FINE lattice ({@link DomainWarpAnalysis#jacobianDeterminant}
 * at each corner, spacing well under the reference warp's own noise
 * wavelength — coarser sampling was measured to actively UNDERSAMPLE real
 * risk pockets between samples, making things worse, not better), then
 * MIN-FILTERED (each cell takes the worst/lowest determinant found within
 * {@code dilateRadiusCells}, not the average — averaging was tried first and
 * measured to actively HIDE real risk: a sharp danger spike gets diluted by
 * its safe neighbors' higher values, so the transition looks comfortable
 * exactly where the raw signal said otherwise) before the danger→comfortable
 * smoothstep is applied. Detection resolution and transition width are
 * deliberately decoupled: sampling fine enough to actually find every risk
 * pocket, but dilating that pocket wide enough afterward that the resulting
 * amplitude modulation's own transition stays gentle — a plain
 * bilinear-interpolated step (dilate radius 0) was measured to reintroduce a
 * fold of its own via its own amplitude-gradient term (see {@link
 * AdaptiveDomainWarp}'s doc), because a transition confined to one lattice
 * cell has to climb the FULL floor-to-strong gap in one cell width. Finally
 * bilinearly interpolated between (now-dilated) lattice corners for any
 * query point — same interpolation idiom {@link CoherentNoise2D} already
 * uses. {@link AdaptiveDomainWarp}'s own doc explains how the whole
 * combined result gets re-verified afterward regardless, so a still-too-
 * steep combination here shows up as a real test failure, not a silent gap.
 *
 * <p>Bounded, deterministic, precomputed once per region (a pure function of
 * {@code (worldSeed, region)} like everything else in this pipeline) — never
 * a per-query runtime cost proportional to how thorough the check is, and
 * never a pass over already-generated output.
 */
public final class SafetyField {

    private final double minCoordX;
    private final double minCoordZ;
    private final double latticeSpacing;
    private final int latticeSizeX;
    private final int latticeSizeZ;
    private final double[][] multiplier;

    /**
     * Square convenience overload — same bound used for both axes. Only actually correct
     * when the queried region's x-range and z-range coincide (e.g. a region at (0,0), or
     * any {@code RegionPos(n, n)} on the diagonal) — a real bug this session found: {@code
     * TriangleFieldGeometryRealization.buildField} used to call this with only the mesh's
     * X bounds for BOTH axes, which happened to work for regions ON the diagonal (min X
     * and min Z coincidentally equal there) and threw {@code IllegalArgumentException} for
     * every off-diagonal region — invisible until something actually solved a region other
     * than (0,0). Use the 5-bound rectangle constructor below for any real region.
     */
    public SafetyField(Warp2D referenceWarp, double minCoord, double maxCoord, double latticeSpacing, double dangerDeterminant, double comfortableDeterminant, int dilateRadiusCells) {
        this(referenceWarp, minCoord, maxCoord, minCoord, maxCoord, latticeSpacing, dangerDeterminant, comfortableDeterminant, dilateRadiusCells);
    }

    /**
     * @param dangerDeterminant at or below this Jacobian determinant, the multiplier is 0 (full damping)
     * @param comfortableDeterminant at or above this, the multiplier is 1 (no damping) — must be > dangerDeterminant
     * @param dilateRadiusCells min-filter radius, in lattice cells, applied to the raw sampled
     *                          determinant field before smoothstep — widens each risk pocket
     *                          (using its WORST nearby value, never averaged away) so the
     *                          transition band spans more than one lattice cell without
     *                          needing coarser (and therefore risk-blind) sampling. 0 disables it.
     */
    public SafetyField(
        Warp2D referenceWarp, double minCoordX, double maxCoordX, double minCoordZ, double maxCoordZ,
        double latticeSpacing, double dangerDeterminant, double comfortableDeterminant, int dilateRadiusCells
    ) {
        if (comfortableDeterminant <= dangerDeterminant) {
            throw new IllegalArgumentException("comfortableDeterminant (" + comfortableDeterminant + ") must exceed dangerDeterminant (" + dangerDeterminant + ")");
        }
        this.minCoordX = minCoordX;
        this.minCoordZ = minCoordZ;
        this.latticeSpacing = latticeSpacing;
        this.latticeSizeX = (int) Math.ceil((maxCoordX - minCoordX) / latticeSpacing) + 1;
        this.latticeSizeZ = (int) Math.ceil((maxCoordZ - minCoordZ) / latticeSpacing) + 1;
        double[][] rawDeterminant = new double[latticeSizeX][latticeSizeZ];

        for (int ix = 0; ix < latticeSizeX; ix++) {
            for (int iz = 0; iz < latticeSizeZ; iz++) {
                double x = minCoordX + ix * latticeSpacing;
                double z = minCoordZ + iz * latticeSpacing;
                rawDeterminant[ix][iz] = DomainWarpAnalysis.jacobianDeterminant(referenceWarp, x, z);
            }
        }

        double[][] dilated = dilateRadiusCells > 0 ? minFilter(rawDeterminant, dilateRadiusCells) : rawDeterminant;
        this.multiplier = new double[latticeSizeX][latticeSizeZ];
        for (int ix = 0; ix < latticeSizeX; ix++) {
            for (int iz = 0; iz < latticeSizeZ; iz++) {
                multiplier[ix][iz] = smoothstep(dilated[ix][iz], dangerDeterminant, comfortableDeterminant);
            }
        }
    }

    /** Separable (min-filter rows, then columns) — O(latticeSizeX*latticeSizeZ) total instead of O(latticeSizeX*latticeSizeZ*radiusCells^2); identical result for a rectangular structuring element since min over a 2D box equals min-of-mins over its rows then columns. */
    private double[][] minFilter(double[][] source, int radiusCells) {
        double[][] rowMin = new double[latticeSizeX][latticeSizeZ];
        for (int ix = 0; ix < latticeSizeX; ix++) {
            minFilter1D(source[ix], rowMin[ix], radiusCells);
        }
        double[][] result = new double[latticeSizeX][latticeSizeZ];
        double[] column = new double[latticeSizeX];
        double[] columnMin = new double[latticeSizeX];
        for (int iz = 0; iz < latticeSizeZ; iz++) {
            for (int ix = 0; ix < latticeSizeX; ix++) {
                column[ix] = rowMin[ix][iz];
            }
            minFilter1D(column, columnMin, radiusCells);
            for (int ix = 0; ix < latticeSizeX; ix++) {
                result[ix][iz] = columnMin[ix];
            }
        }
        return result;
    }

    /** Sliding-window minimum, edge-clamped (out-of-bounds neighbors read as the nearest in-bounds value). */
    private void minFilter1D(double[] source, double[] destination, int radiusCells) {
        for (int i = 0; i < source.length; i++) {
            double min = Double.MAX_VALUE;
            for (int offset = -radiusCells; offset <= radiusCells; offset++) {
                min = Math.min(min, source[clampIndex(i + offset, source.length)]);
            }
            destination[i] = min;
        }
    }

    private int clampIndex(int index, int length) {
        return Math.max(0, Math.min(length - 1, index));
    }

    /** @throws IllegalArgumentException outside the precomputed rectangle — this class never extrapolates or silently clamps, same "own the bounds you were given, never guess past them" discipline as the rest of this codebase's per-region computations. */
    public double safetyAt(double x, double z) {
        double gridX = (x - minCoordX) / latticeSpacing;
        double gridZ = (z - minCoordZ) / latticeSpacing;
        int ix0 = (int) Math.floor(gridX);
        int iz0 = (int) Math.floor(gridZ);
        if (ix0 < 0 || iz0 < 0 || ix0 + 1 >= latticeSizeX || iz0 + 1 >= latticeSizeZ) {
            throw new IllegalArgumentException("SafetyField: (" + x + ", " + z + ") is outside the precomputed rectangle");
        }

        double fx = gridX - ix0;
        double fz = gridZ - iz0;
        double n00 = multiplier[ix0][iz0];
        double n10 = multiplier[ix0 + 1][iz0];
        double n01 = multiplier[ix0][iz0 + 1];
        double n11 = multiplier[ix0 + 1][iz0 + 1];

        double ix = lerp(n00, n10, fx);
        double iz = lerp(n01, n11, fx);
        return lerp(ix, iz, fz);
    }

    private static double smoothstep(double value, double edge0, double edge1) {
        double t = clamp((value - edge0) / (edge1 - edge0), 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
