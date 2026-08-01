package com.mineutze.worldgen.integration;

import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeShapeHints;
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
 * Small additive nudge on top of vanilla's Overworld final_density — see
 * data/minecraft/worldgen/noise_settings/overworld.json, which wraps the
 * untouched original tree as add(original, this) rather than replacing
 * it. Caves/aquifers/ore veins key off other parts of the noise router
 * and are unaffected.
 *
 * First step of doc/ARCHITECTURE.md layer 3 (Density Router): proves
 * biome shape hints actually reach terrain before attempting full
 * biome-driven shape. Deliberately modest amplitude and no cross-
 * macro-cell blending yet (visible steps at 128-block cell borders) —
 * both known simplifications for this first pass, not oversights.
 */
public record BiomeShapeOffsetDensityFunction() implements DensityFunction.SimpleFunction {

    public static final MapCodec<BiomeShapeOffsetDensityFunction> CODEC = MapCodec.unit(BiomeShapeOffsetDensityFunction::new);
    private static final KeyDispatchDataCodec<BiomeShapeOffsetDensityFunction> KEY_DISPATCH_CODEC = KeyDispatchDataCodec.of(CODEC);

    // Placeholder, same as the grid constants in doc/ARCHITECTURE.md - tune once
    // the effect is visible in-game. depthBias in [0,1] maps to [-HALF, HALF].
    private static final double MAX_OFFSET = 0.1;

    // Vanilla's own final_density has a y_clamped_gradient tapering density to
    // air between y=240 and y=256 (build-limit taper - see the real overworld.json
    // this wraps). Applying our offset untapered there pushed cells that were
    // barely air back over the solid threshold across a whole y-band at once -
    // a floating slab, worst for ocean (highest depthBias -> biggest offset).
    // Taper our own offset to 0 well before that zone, and near the y=-64 floor too.
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
        MacroCellPos cell = new MacroCellPos(
            Math.floorDiv(context.blockX(), grid.macroCellSizeBlocks()),
            Math.floorDiv(context.blockZ(), grid.macroCellSizeBlocks())
        );

        BiomeId biomeId = RegionCacheHolder.get().biomeAt(cell);
        BiomeShapeHints hints = ModBiomeGraph.DEFAULT.node(biomeId).shapeHints();
        double baseOffset = (hints.depthBias() - 0.5) * MAX_OFFSET;
        double offset = baseOffset * verticalFade(context.blockY());

        if (LOGGED_CELLS.size() < MAX_LOGGED_CELLS && LOGGED_CELLS.add(cell)) {
            LOGGER.info("[mineutze_worldgen] density offset at macro-cell {},{} (biome {}): {}", cell.x(), cell.z(), biomeId, offset);
        }

        return offset;
    }

    private static double verticalFade(int blockY) {
        double topFade = Mth.clampedMap(blockY, TOP_FADE_START_Y, TOP_FADE_END_Y, 1.0, 0.0);
        double bottomFade = Mth.clampedMap(blockY, BOTTOM_FADE_START_Y, BOTTOM_FADE_END_Y, 0.0, 1.0);
        return Math.min(topFade, bottomFade);
    }

    @Override
    public double minValue() {
        return -MAX_OFFSET / 2;
    }

    @Override
    public double maxValue() {
        return MAX_OFFSET / 2;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return KEY_DISPATCH_CODEC;
    }
}
