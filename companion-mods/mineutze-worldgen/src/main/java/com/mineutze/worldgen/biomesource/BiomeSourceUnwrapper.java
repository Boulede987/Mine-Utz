package com.mineutze.worldgen.biomesource;

import com.mojang.logging.LogUtils;
import net.minecraft.world.level.biome.BiomeSource;
import org.slf4j.Logger;

import java.lang.reflect.Field;

/**
 * Unwraps known BiomeSource wrapper mods so debug commands can find our
 * CustomOverworldBiomeSource underneath, extracted out of PatchDebugCommand so every
 * /dump_* debug command shares one fix, not several drifting copies.
 *
 * Team Abnormals' Blueprint (cross-mod biome-injection compat library, common in
 * kitchen-sink packs) wraps WHATEVER biome source a dimension configures in its own
 * ModdedBiomeSource, breaking a plain instanceof check even when our source is still
 * running underneath as its private "originalSource" field. No compile-time dependency
 * on Blueprint here on purpose -- this mod should work whether or not Blueprint happens
 * to be installed, so the class is looked up by name and the field read via reflection,
 * both guarded to silently no-op if Blueprint isn't present or its internals ever change
 * shape.
 */
public final class BiomeSourceUnwrapper {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String BLUEPRINT_MODDED_BIOME_SOURCE_CLASS =
        "com.teamabnormals.blueprint.common.world.modification.ModdedBiomeSource";
    private static final String BLUEPRINT_ORIGINAL_SOURCE_FIELD = "originalSource";

    private BiomeSourceUnwrapper() {
    }

    /** Follows known wrappers' inner-source field one or more levels deep in case of
     * nested wrappers. Returns the input unchanged if it isn't a recognized wrapper, or
     * if reflection fails for any reason (Blueprint absent, internals changed shape,
     * etc.) -- never throws, this is a debug convenience, not load-bearing logic. */
    public static BiomeSource unwrapKnownWrappers(BiomeSource biomeSource) {
        BiomeSource current = biomeSource;
        while (BLUEPRINT_MODDED_BIOME_SOURCE_CLASS.equals(current.getClass().getName())) {
            try {
                Field field = current.getClass().getDeclaredField(BLUEPRINT_ORIGINAL_SOURCE_FIELD);
                field.setAccessible(true);
                Object inner = field.get(current);
                if (!(inner instanceof BiomeSource innerBiomeSource)) {
                    break;
                }
                current = innerBiomeSource;
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOGGER.warn("[patch-debug] failed to unwrap {} for debug purposes -- reporting the wrapper as-is",
                    BLUEPRINT_MODDED_BIOME_SOURCE_CLASS, e);
                break;
            }
        }
        return current;
    }
}
