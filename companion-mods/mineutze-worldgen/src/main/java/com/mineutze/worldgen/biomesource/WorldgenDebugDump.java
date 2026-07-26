package com.mineutze.worldgen.biomesource;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import org.slf4j.Logger;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bulk ground-truth debug dump: writes every REAL resolved column (deduped by x,z, first
 * resolution wins) to a CSV file, using the exact same code path real gameplay uses
 * (CustomOverworldBiomeSource.getNoiseBiome calls record() below directly) -- not a
 * separate bulk-scan command that could behave subtly differently from what the actual
 * world does. Built to debug persistent "patches of chaos" that survived two real
 * algorithm fixes (growth-blend score penalty, sizeMax-based spacing) -- a single-point
 * /dump_terrain_class or /dump_patch_debug reading isn't enough data to see the actual
 * spatial PATTERN of what's going wrong; this lets a whole explored region's real
 * resolution get read back and analyzed directly, not eyeballed from pasted chat samples.
 *
 * Code-level toggle (ENABLED), not a config file or command -- per explicit request, to
 * avoid needing a config system for what's a temporary investigation tool. Flip to false
 * and redeploy to disable; this is a real per-call hook, not free when enabled (see
 * record()'s own cost note).
 */
public final class WorldgenDebugDump {

    // Flip to false and redeploy to disable. Left true is fine to ship temporarily since
    // record() costs almost nothing once a column's already been logged (a single
    // ConcurrentHashMap lookup) -- but this is a debug tool, not meant to stay on forever.
    public static final boolean ENABLED = true;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String OUTPUT_DIRECTORY = "debug_dumps";
    private static final String OUTPUT_FILE_NAME = "biome_resolution_dump.csv";
    private static final String CSV_HEADER =
        "x,z,continentalness,erosion,weirdness,depth,terrainClass,biome,regionFamily,source";

    // Dedup by packed (x,z) -- getNoiseBiome is invoked once per quart cell at MANY
    // Y-levels per column during real chunk generation (the BIOMES step's 3D quart
    // grid), but real placement only ever depends on (x,z) for the surface tier (Y only
    // decides surface-vs-underground routing, see CustomOverworldBiomeSource's own
    // SURFACE_DEPTH_MAX comment) -- logging every Y-repeat would produce mostly
    // duplicate rows for the same real answer. First resolution for a given (x,z) wins
    // and gets logged; every later call for that same column is a cheap map lookup, no
    // write. Session-lifetime, never cleared -- fine for a debug tool, not meant to run
    // indefinitely.
    private static final Set<Long> LOGGED_COLUMNS = ConcurrentHashMap.newKeySet();

    private static volatile PrintWriter writer;

    private WorldgenDebugDump() {
    }

    /** Records one real resolution -- called directly from
     * CustomOverworldBiomeSource.getNoiseBiome at every branch (true surface, warped
     * surface, underground, both fallbacks) with a `source` tag identifying which. No-op
     * entirely if ENABLED is false (checked by the caller too, so this method body never
     * even runs when disabled -- not just an early return, avoiding the dedup-map lookup
     * cost as well). */
    public static void record(
        int blockX, int blockZ, Climate.Sampler sampler, TerrainClass terrainClass,
        ResourceKey<Biome> biome, RegionFamily regionFamily, String source
    ) {
        long columnKey = (((long) blockX) << 32) ^ (blockZ & 0xFFFFFFFFL);
        if (!LOGGED_COLUMNS.add(columnKey)) {
            return; // already logged this exact column from an earlier call
        }

        Climate.TargetPoint sample = sampler.sample(blockX >> 2, 0, blockZ >> 2);
        String row = String.format(
            "%d,%d,%.4f,%.4f,%.4f,%.4f,%s,%s,%s,%s",
            blockX, blockZ,
            Climate.unquantizeCoord(sample.continentalness()),
            Climate.unquantizeCoord(sample.erosion()),
            Climate.unquantizeCoord(sample.weirdness()),
            Climate.unquantizeCoord(sample.depth()),
            terrainClass,
            biome.location(),
            regionFamily == null ? "none" : regionFamily.toString(),
            source
        );
        writeLine(row);
    }

    private static synchronized void writeLine(String row) {
        PrintWriter activeWriter = writer;
        if (activeWriter == null) {
            activeWriter = openWriter();
            if (activeWriter == null) {
                return; // failed to open, already logged -- give up silently rather than crash worldgen
            }
            writer = activeWriter;
        }
        activeWriter.println(row);
        activeWriter.flush(); // correctness over throughput -- see class doc, this is a debug tool
    }

    /** Truncates fresh on first write each JVM run, matching logs/latest.log's own
     * rotate-per-session behavior -- a dump always reflects "this session" cleanly, not
     * stale data appended across multiple play sessions with different fix attempts. */
    private static PrintWriter openWriter() {
        try {
            File directory = new File(OUTPUT_DIRECTORY);
            if (!directory.exists() && !directory.mkdirs()) {
                LOGGER.error("[worldgen-debug-dump] failed to create output directory {}", directory.getAbsolutePath());
                return null;
            }
            File outputFile = new File(directory, OUTPUT_FILE_NAME);
            PrintWriter newWriter = new PrintWriter(new FileWriter(outputFile, false));
            newWriter.println(CSV_HEADER);
            newWriter.flush();
            LOGGER.info("[worldgen-debug-dump] writing to {}", outputFile.getAbsolutePath());
            return newWriter;
        } catch (IOException e) {
            LOGGER.error("[worldgen-debug-dump] failed to open output file", e);
            return null;
        }
    }
}
