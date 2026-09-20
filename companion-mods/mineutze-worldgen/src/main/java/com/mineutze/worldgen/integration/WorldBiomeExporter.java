package com.mineutze.worldgen.integration;

import com.mineutze.worldgen.plan.WorldBiomePlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws whatever biome the game ACTUALLY reports around a position - {@code level.getBiome}, the
 * real generated world, not the plan's own answer - as a PNG plus a legend file (2026-09-20). It exists
 * so what the game produces can be looked at and compared with what the plan says; the two agreeing
 * everywhere is the integration's own acceptance test.
 *
 * <p>Each pixel is one sample of {@code stepBlocks} blocks; north (negative z) is at the top. A biome's
 * color comes from the order biomes are first met while sampling, spread around the hue wheel by the
 * golden ratio so neighbors differ; the legend lists every color with its biome and sample count.
 */
final class WorldBiomeExporter {

    private static final String OUTPUT_DIR_NAME = "mineutze_worldgen_export";
    private static final double GOLDEN_RATIO_CONJUGATE = 0.61803398875;
    private static final int STORED_BIOME_CHECK_MAX_SAMPLES = 300_000;
    private static final int QUART_BLOCKS = 4;
    private static final int QUART_CENTER_OFFSET = 2;

    private WorldBiomeExporter() {
    }

    /**
     * Two acceptance numbers, both of which must be zero. {@code unexplainedDifferences}: samples where the
     * game's blended lookup ({@code level.getBiome}) is neither the plan's biome for its own quart nor for
     * any of the 8 neighboring quarts - vanilla deliberately jitters a block's lookup between neighboring
     * quarts, so a difference that a neighbor explains is expected and one it does not is a bug.
     * {@code storedBiomeMismatches}: samples whose biome in the actually generated chunk data differs from the
     * plan at that quart, out of {@code storedBiomeChecks} (only done for small exports, since it generates chunks).
     */
    record Result(Path imageFile, Path legendFile, int samples, int differentFromOwnQuart, int unexplainedDifferences, int storedBiomeMismatches, int storedBiomeChecks) {
    }

    static Result exportAroundPosition(ServerLevel level, Vec3 position, int radiusBlocks, int stepBlocks) {
        int centerX = (int) Math.floor(position.x);
        int centerZ = (int) Math.floor(position.z);
        int samplesPerSide = 2 * (radiusBlocks / stepBlocks) + 1;
        int[][] biomeIndexAt = new int[samplesPerSide][samplesPerSide];
        Map<String, Integer> indexOf = new LinkedHashMap<>();
        List<Integer> counts = new ArrayList<>();
        WorldBiomePlan plan = WorldBiomePlanHolder.get();
        boolean checkStoredBiomes = (long) samplesPerSide * samplesPerSide <= STORED_BIOME_CHECK_MAX_SAMPLES;
        int differentFromOwnQuart = 0;
        int unexplained = 0;
        int storedMismatches = 0;
        int storedChecks = 0;
        // one lookup per chunk, not per sample: a chunk holds 64 samples at 4-block steps, and asking the chunk system
        // that often on the server thread once ran past the 60 s watchdog and killed the server
        Map<Long, ChunkAccess> chunks = new HashMap<>();

        for (int row = 0; row < samplesPerSide; row++) {
            for (int column = 0; column < samplesPerSide; column++) {
                int x = centerX + (column - samplesPerSide / 2) * stepBlocks;
                int z = centerZ + (row - samplesPerSide / 2) * stepBlocks;
                String name = nameOf(level.getBiome(new BlockPos(x, level.getSeaLevel(), z)));
                int quartX = Math.floorDiv(x, QUART_BLOCKS);
                int quartZ = Math.floorDiv(z, QUART_BLOCKS);
                String expected = planBiomeAtQuart(plan, quartX, quartZ);
                if (!expected.equals(name)) {
                    differentFromOwnQuart++;
                    if (!explainedByANeighboringQuart(plan, quartX, quartZ, name)) {
                        unexplained++;
                    }
                }
                if (checkStoredBiomes) {
                    int chunkX = Math.floorDiv(x, 16);
                    int chunkZ = Math.floorDiv(z, 16);
                    ChunkAccess chunk = chunks.computeIfAbsent(ChunkPos.asLong(chunkX, chunkZ), key -> level.getChunk(chunkX, chunkZ, ChunkStatus.BIOMES));
                    String stored = nameOf(chunk.getNoiseBiome(quartX, level.getSeaLevel() >> 2, quartZ));
                    storedChecks++;
                    if (!expected.equals(stored)) {
                        storedMismatches++;
                    }
                }
                int index = indexOf.computeIfAbsent(name, key -> {
                    counts.add(0);
                    return counts.size() - 1;
                });
                counts.set(index, counts.get(index) + 1);
                biomeIndexAt[row][column] = index;
            }
        }

        BufferedImage image = new BufferedImage(samplesPerSide, samplesPerSide, BufferedImage.TYPE_INT_RGB);
        for (int row = 0; row < samplesPerSide; row++) {
            for (int column = 0; column < samplesPerSide; column++) {
                image.setRGB(column, row, colorOf(biomeIndexAt[row][column]).getRGB());
            }
        }

        Path directory = level.getServer().getServerDirectory().resolve(OUTPUT_DIR_NAME);
        Path imageFile = directory.resolve("biomes-" + centerX + "_" + centerZ + ".png");
        Path legendFile = directory.resolve("biomes-" + centerX + "_" + centerZ + "-legend.txt");
        StringBuilder legend = new StringBuilder("center " + centerX + "," + centerZ + ", radius " + radiusBlocks + " blocks, " + stepBlocks + " blocks per pixel, north at the top\n");
        indexOf.forEach((name, index) -> {
            Color color = colorOf(index);
            legend.append(String.format("#%02x%02x%02x  %-45s %d samples%n", color.getRed(), color.getGreen(), color.getBlue(), name, counts.get(index)));
        });
        try {
            Files.createDirectories(directory);
            ImageIO.write(image, "png", imageFile.toFile());
            Files.writeString(legendFile, legend);
        } catch (IOException failure) {
            throw new UncheckedIOException("WorldBiomeExporter: could not write to " + directory, failure);
        }
        return new Result(imageFile, legendFile, samplesPerSide * samplesPerSide, differentFromOwnQuart, unexplained, storedMismatches, storedChecks);
    }

    /** The game stores one biome per 4x4-block quart and asks the biome source about the quart's center. */
    private static String planBiomeAtQuart(WorldBiomePlan plan, int quartX, int quartZ) {
        return plan.biomeAt(quartX * QUART_BLOCKS + QUART_CENTER_OFFSET, quartZ * QUART_BLOCKS + QUART_CENTER_OFFSET).toString();
    }

    private static boolean explainedByANeighboringQuart(WorldBiomePlan plan, int quartX, int quartZ, String gameBiome) {
        for (int deltaX = -1; deltaX <= 1; deltaX++) {
            for (int deltaZ = -1; deltaZ <= 1; deltaZ++) {
                if (planBiomeAtQuart(plan, quartX + deltaX, quartZ + deltaZ).equals(gameBiome)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String nameOf(Holder<Biome> biome) {
        return biome.unwrapKey().map(key -> key.location().toString()).orElse("<unregistered>");
    }

    private static Color colorOf(int index) {
        float hue = (float) ((index * GOLDEN_RATIO_CONJUGATE) % 1.0);
        float brightness = index % 2 == 0 ? 0.95f : 0.75f;
        return Color.getHSBColor(hue, 0.55f, brightness);
    }
}
