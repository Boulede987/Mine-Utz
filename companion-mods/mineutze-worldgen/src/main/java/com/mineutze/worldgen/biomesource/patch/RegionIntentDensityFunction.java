package com.mineutze.worldgen.biomesource.patch;

import com.mineutze.worldgen.biomesource.RegionFamily;
import com.mineutze.worldgen.biomesource.SurfacePatchGridHolder;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.levelgen.DensityFunction;

import java.util.function.ToDoubleFunction;

/**
 * Phase 2 Stage 3 (TERRAIN_COHERENCE_DESIGN.md Sec 4b): the custom density-function node
 * that lets a datapack-overridden tectonic:terrain_spline/offset/regions.json (Stage 4,
 * not built yet) read biome shape intent instead of Tectonic's own erosion/region
 * noise. NOT wired into that override yet in this stage -- registered and evaluable, but
 * every real Tectonic column still computes byte-identically to unmodified Tectonic,
 * since nothing currently references "mineutze_filter:region_intent" from a live
 * density-function graph. Two datapack-facing instances of this type exist (bracket vs.
 * selector axis, see the region_intent/{bracket,selector}.json wrapper resources) since
 * regions.json needs both a family's bracketCoordinate and its selectorCoordinate as
 * separate inputs (Sec 4b's nested-spline structure).
 *
 * The "Climate.Sampler access problem" (see the implementation plan): this is
 * codec-constructed from JSON with no reference to the level, chunk generator, or a
 * Climate.Sampler -- only FunctionContext's block coordinates. It cannot itself trigger
 * a fresh SurfacePatchGrid resolution. Instead it reads SurfacePatchGridHolder's
 * non-triggering peekRegionIntent -- on any cache miss (tile not yet resolved, or this
 * column's biome declared no regionFamilyWeights and so never rolled a family), it falls
 * back to the real Tectonic node it's replacing, verbatim. Real chunk generation's
 * BIOMES step (which populates the cache via CustomOverworldBiomeSource.getNoiseBiome)
 * runs before the NOISE step evaluates density functions for the same chunk, so this
 * should normally be a cache hit -- but that ordering assumption is verified empirically
 * with /dump_region_intent (Stage 2), not trusted blind.
 */
public record RegionIntentDensityFunction(RegionIntentDensityFunction.Axis axis, DensityFunction fallback)
    implements DensityFunction.SimpleFunction {

    public static final MapCodec<RegionIntentDensityFunction> DATA_CODEC = RecordCodecBuilder.mapCodec(instance ->
        instance.group(
            Axis.CODEC.fieldOf("axis").forGetter(RegionIntentDensityFunction::axis),
            DensityFunction.HOLDER_HELPER_CODEC.fieldOf("fallback").forGetter(RegionIntentDensityFunction::fallback)
        ).apply(instance, RegionIntentDensityFunction::new)
    );
    public static final KeyDispatchDataCodec<RegionIntentDensityFunction> CODEC = KeyDispatchDataCodec.of(DATA_CODEC);

    @Override
    public double compute(FunctionContext context) {
        SurfacePatchGrid grid = SurfacePatchGridHolder.overworldSurfacePatchGrid();
        if (grid == null) {
            return fallback.compute(context); // no world loaded yet, or not the overworld -- stay reactive
        }

        int blockX = context.blockX();
        int blockZ = context.blockZ();
        RegionFamily hereFamily = grid.peekRegionIntent(blockX, blockZ);
        if (hereFamily == null) {
            return fallback.compute(context); // cache miss or no shape intent here -- stay reactive
        }

        // Blend the 4 surrounding 16-block cell lattice corners (Fork 3: a hard per-cell
        // switch between adjacent, uncorrelated Tectonic region noise fields would be a
        // one-column cliff). A corner that itself misses the peek (a real patch boundary,
        // or simply unresolved yet) contributes THIS cell's own family rather than an
        // undefined value -- extends outward smoothly instead of pulling toward nothing.
        return DomainWarp.bilinearSmoothstep(blockX, blockZ, SurfacePatchGrid.CELL_SIZE_BLOCKS,
            (latticeX, latticeZ) -> axis.coordinateOf(familyAt(grid, latticeX, latticeZ, hereFamily)));
    }

    private static RegionFamily familyAt(SurfacePatchGrid grid, int blockX, int blockZ, RegionFamily whenMissing) {
        RegionFamily family = grid.peekRegionIntent(blockX, blockZ);
        return family != null ? family : whenMissing;
    }

    /** Rebuilds with fallback's own children visited too -- SimpleFunction's default
     * mapAll (visitor.apply(this)) would skip fallback entirely, which is wrong for any
     * function that wraps a nested DensityFunction (see e.g. vanilla's own
     * DensityFunctions.RangeChoice, which recurses into every DensityFunction field
     * before wrapping). Overriding SimpleFunction's default here on purpose. */
    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return visitor.apply(new RegionIntentDensityFunction(axis, fallback.mapAll(visitor)));
    }

    @Override
    public double minValue() {
        return Math.min(axis.minFamilyCoordinate(), fallback.minValue());
    }

    @Override
    public double maxValue() {
        return Math.max(axis.maxFamilyCoordinate(), fallback.maxValue());
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC;
    }

    public enum Axis implements StringRepresentable {
        BRACKET("bracket", RegionFamily::bracketCoordinate),
        SELECTOR("selector", RegionFamily::selectorCoordinate);

        public static final Codec<Axis> CODEC = StringRepresentable.fromEnum(Axis::values);

        private final String name;
        private final ToDoubleFunction<RegionFamily> coordinateExtractor;
        private final double minFamilyCoordinate;
        private final double maxFamilyCoordinate;

        Axis(String name, ToDoubleFunction<RegionFamily> coordinateExtractor) {
            this.name = name;
            this.coordinateExtractor = coordinateExtractor;
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            for (RegionFamily family : RegionFamily.values()) {
                double coordinate = coordinateExtractor.applyAsDouble(family);
                min = Math.min(min, coordinate);
                max = Math.max(max, coordinate);
            }
            this.minFamilyCoordinate = min;
            this.maxFamilyCoordinate = max;
        }

        double coordinateOf(RegionFamily family) {
            return coordinateExtractor.applyAsDouble(family);
        }

        double minFamilyCoordinate() {
            return minFamilyCoordinate;
        }

        double maxFamilyCoordinate() {
            return maxFamilyCoordinate;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }
}
