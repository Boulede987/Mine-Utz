package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeId;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** The world a FEASIBLE verdict built: rendered pattern, its map, and how many attempts it took to get there. */
public record BuiltWorld(Map<ToroidalPos, BiomeId> pattern, BiomeMap map, int attemptsUntilBuilt) {

    private static final int PIXELS_PER_SAMPLE = 1;

    /** Writes the map as a PNG, creating parent folders. */
    public void writePicture(Path file) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            Files.createDirectories(parent);
        } catch (IOException failure) {
            throw new UncheckedIOException("BuiltWorld: cannot create folder for " + file.toAbsolutePath(), failure);
        }
        BiomeMapPngExporter.exportFull(map, file, PIXELS_PER_SAMPLE);
    }
}
