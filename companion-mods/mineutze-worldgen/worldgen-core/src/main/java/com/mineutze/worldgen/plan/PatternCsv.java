package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeId;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A solved pattern as a text file, one {@code q,r,namespace:path} line per cell (2026-09-20), so a pattern that took a minute to solve can be reloaded and inspected. */
final class PatternCsv {

    private PatternCsv() {
    }

    static void write(Path file, Map<ToroidalPos, BiomeId> pattern) {
        List<String> lines = new ArrayList<>();
        pattern.forEach((pos, biome) -> lines.add(pos.q() + "," + pos.r() + "," + biome));
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(file, lines);
        } catch (IOException failure) {
            throw new UncheckedIOException("PatternCsv: could not write " + file, failure);
        }
    }

    /** Reads a pattern for {@code grid}; a cell outside the grid or a file that does not cover every cell is an error, never a partial pattern. */
    static Map<ToroidalPos, BiomeId> read(Path file, ToroidalGrid grid) {
        Map<ToroidalPos, BiomeId> pattern = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(file)) {
                String[] parts = line.split(",");
                String[] name = parts[2].split(":");
                int q = Integer.parseInt(parts[0]);
                int r = Integer.parseInt(parts[1]);
                if (q < 0 || q >= grid.width() || r < 0 || r >= grid.height()) {
                    throw new IllegalStateException("PatternCsv: " + file + " has cell (" + q + "," + r + ") outside the " + grid.width() + "x" + grid.height() + " grid");
                }
                pattern.put(new ToroidalPos(q, r), new BiomeId(name[0], name[1]));
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("PatternCsv: could not read " + file, failure);
        }
        if (pattern.size() != grid.cellCount()) {
            throw new IllegalStateException("PatternCsv: " + file + " holds " + pattern.size() + " cells, expected " + grid.cellCount());
        }
        return pattern;
    }
}
