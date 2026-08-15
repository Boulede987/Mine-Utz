package com.mineutze.worldgen.integration;

import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeShapeHints;
import com.mineutze.worldgen.biomegraph.ModBiomeGraph;
import com.mineutze.worldgen.regionsolve.BorderBlend;
import com.mineutze.worldgen.regionsolve.MacroCellPos;
import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.MapCodec;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.slf4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Additive nudge on top of vanilla's Overworld final_density — see
 * data/minecraft/worldgen/noise_settings/overworld.json, which wraps the
 * untouched original tree as add(original, this) rather than replacing
 * it. Caves/aquifers/ore veins key off other parts of the noise router
 * and are unaffected; {@link #verticalFade} keeps this term away from
 * vanilla's own build-limit taper (see below) so it can't push a whole
 * y-band across the solid/air threshold there.
 *
 * Deliberately exaggerated for now: the +-0.05 version was too subtle to
 * confirm visually against vanilla's own continentalness/erosion noise,
 * which still independently shapes terrain everywhere right now (full
 * biome-driven shape is later Density Router work). This
 * amplitude is a "prove the wiring, make it unmistakable" value, not a
 * tuned one — dial it back down once confirmed.
 *
 * Blended across macro-cell borders via {@link BorderBlend}, using each
 * adjacent pair's own blendWidthBlocks — the biome PATCH outline is
 * still blocky (a union of square macro-cells), but the terrain no
 * longer has a hard density cliff at every cell edge.
 */
public record BiomeShapeOffsetDensityFunction() implements DensityFunction.SimpleFunction {

    public static final MapCodec<BiomeShapeOffsetDensityFunction> CODEC = MapCodec.unit(BiomeShapeOffsetDensityFunction::new);
    private static final KeyDispatchDataCodec<BiomeShapeOffsetDensityFunction> KEY_DISPATCH_CODEC = KeyDispatchDataCodec.of(CODEC);

    // Placeholders, tune once the effect is visible in-game.
    // - verticalShift: depthBias 0..1 -> shift in [-VERTICAL_SHIFT_AMPLITUDE/2, +.../2].
    //   Sign matters: higher depthBias means "should read as deeper" (DESIGN.md's
    //   "ocean -> deep"), and LESS density is what pushes terrain down/underwater,
    //   so the multiplier is (0.5 - depthBias), not (depthBias - 0.5).
    // - wave: roughness*(1-flatness) scales a simple sin*cos ripple, so flat/low-
    //   roughness biomes (desert) stay near-flat and rough ones (badlands) get
    //   visible hills. No world-seed dependence yet (same pattern every world) and
    //   no cross-macro-cell blending - both known follow-ups, not oversights.
    private static final double VERTICAL_SHIFT_AMPLITUDE = 3.0;
    private static final double WAVE_AMPLITUDE = 4.0;
    private static final double WAVELENGTH_BLOCKS = 96.0;
    private static final double WAVE_FREQUENCY = 2.0 * Math.PI / WAVELENGTH_BLOCKS;

    // Vanilla's own final_density has a y_clamped_gradient tapering density to
    // air between y=240 and y=256 (build-limit taper - see the real overworld.json
    // this wraps). Applying our offset untapered there could push a whole y-band
    // that's barely air back over the solid threshold at once - a floating slab,
    // worst for whichever biome has the biggest offset. Taper our own offset to 0
    // well before that zone, and near the y=-64 floor too.
    private static final int TOP_FADE_START_Y = 192;
    private static final int TOP_FADE_END_Y = 232;
    private static final int BOTTOM_FADE_START_Y = -64;
    private static final int BOTTOM_FADE_END_Y = -32;

    // compute() runs millions of times per chunk - log only the first few distinct
    // macro-cells so this is diagnostic (proof the offset actually varies by biome)
    // without flooding the log or costing real performance.
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Set<MacroCellPos> LOGGED_CELLS = ConcurrentHashMap.newKeySet();
    private static final int MAX_LOGGED_CELLS = 30;

    @Override
    public double compute(FunctionContext context) {
        MacroGridConfig grid = MacroGridConfig.DEFAULT;
        double wave = Math.sin(context.blockX() * WAVE_FREQUENCY) * Math.cos(context.blockZ() * WAVE_FREQUENCY);

        double baseOffset = BorderBlend.blendedValue(
            context.blockX(), context.blockZ(), grid, ModBiomeGraph.DEFAULT,
            cell -> RegionCacheHolder.get().biomeAt(cell),
            biomeId -> baseOffsetForBiome(biomeId, wave)
        );
        double offset = baseOffset * verticalFade(context.blockY());

        logIfNewCell(context, baseOffset);
        return offset;
    }

    private static double baseOffsetForBiome(BiomeId biomeId, double wave) {
        BiomeShapeHints hints = ModBiomeGraph.DEFAULT.node(biomeId).shapeHints();
        double verticalShift = (0.5 - hints.depthBias()) * VERTICAL_SHIFT_AMPLITUDE;
        double waveAmplitude = hints.roughness() * (1.0 - hints.flatness()) * WAVE_AMPLITUDE;
        return verticalShift + wave * waveAmplitude;
    }

    private static void logIfNewCell(FunctionContext context, double baseOffset) {
        MacroGridConfig grid = MacroGridConfig.DEFAULT;
        MacroCellPos cell = new MacroCellPos(
            Math.floorDiv(context.blockX(), grid.macroCellSizeBlocks()),
            Math.floorDiv(context.blockZ(), grid.macroCellSizeBlocks())
        );
        if (LOGGED_CELLS.size() < MAX_LOGGED_CELLS && LOGGED_CELLS.add(cell)) {
            BiomeId biomeId = RegionCacheHolder.get().biomeAt(cell);
            LOGGER.info("[mineutze_worldgen] macro-cell {},{} (biome {}): blended baseOffset sample={}", cell.x(), cell.z(), biomeId, baseOffset);
        }
    }

    private static double verticalFade(int blockY) {
        double topFade = Mth.clampedMap(blockY, TOP_FADE_START_Y, TOP_FADE_END_Y, 1.0, 0.0);
        double bottomFade = Mth.clampedMap(blockY, BOTTOM_FADE_START_Y, BOTTOM_FADE_END_Y, 0.0, 1.0);
        return Math.min(topFade, bottomFade);
    }

    @Override
    public double minValue() {
        return -(VERTICAL_SHIFT_AMPLITUDE / 2.0 + WAVE_AMPLITUDE);
    }

    @Override
    public double maxValue() {
        return VERTICAL_SHIFT_AMPLITUDE / 2.0 + WAVE_AMPLITUDE;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return KEY_DISPATCH_CODEC;
    }
}
