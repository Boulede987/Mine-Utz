package com.mineutze.worldgen.integration;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.Vec3;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

    private WorldBiomeExporter() {
    }

    record Result(Path imageFile, Path legendFile, int samples) {
    }

    static Result exportAroundPosition(ServerLevel level, Vec3 position, int radiusBlocks, int stepBlocks) {
        int centerX = (int) Math.floor(position.x);
        int centerZ = (int) Math.floor(position.z);
        int samplesPerSide = 2 * (radiusBlocks / stepBlocks) + 1;
        int[][] biomeIndexAt = new int[samplesPerSide][samplesPerSide];
        Map<String, Integer> indexOf = new LinkedHashMap<>();
        List<Integer> counts = new ArrayList<>();

        for (int row = 0; row < samplesPerSide; row++) {
            for (int column = 0; column < samplesPerSide; column++) {
                int x = centerX + (column - samplesPerSide / 2) * stepBlocks;
                int z = centerZ + (row - samplesPerSide / 2) * stepBlocks;
                String name = nameOf(level.getBiome(new BlockPos(x, level.getSeaLevel(), z)));
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
        return new Result(imageFile, legendFile, samplesPerSide * samplesPerSide);
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
