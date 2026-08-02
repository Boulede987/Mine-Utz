package com.mineutze.worldgen.integration;

import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.ModBiomeGraph;
import com.mineutze.worldgen.export.AsciiExporter;
import com.mineutze.worldgen.export.BiomeLegend;
import com.mineutze.worldgen.export.ExportSnapshot;
import com.mineutze.worldgen.export.JsonExporter;
import com.mineutze.worldgen.export.PngExporter;
import com.mineutze.worldgen.regionsolve.MacroCellPos;
import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mineutze.worldgen.regionsolve.RegionPos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exports whatever biome ACTUALLY generated around a position — not a fresh
 * solve, not the solver's cached prediction, the real generated world —
 * reusing the exact same {@code com.mineutze.worldgen.export} writers
 * (PNG/ASCII/JSON) the standalone CLI uses. Exists because the standalone
 * export CLI and the live game can legitimately diverge (cross-region
 * solve is order-dependent — doc/KNOWN_ISSUES.md #4) and there was
 * previously no way to see what the game actually produced, only what the
 * solver would produce in isolation.
 *
 * One block sample per macro-cell (its center) is enough: our own biome
 * source is uniform across a whole macro-cell by construction, so a single
 * sample is exactly as informative as sampling every block in it.
 */
final class WorldBiomeExporter {

    private static final String OUTPUT_DIR_NAME = "mineutze_worldgen_export";

    private WorldBiomeExporter() {
    }

    record Result(Path outputDir, int cellsExported) {
    }

    static Result exportAroundPosition(ServerLevel level, Vec3 position, int regionSpan) {
        MacroGridConfig grid = MacroGridConfig.DEFAULT;
        MacroCellPos centerCell = new MacroCellPos(
            Math.floorDiv((int) position.x, grid.macroCellSizeBlocks()),
            Math.floorDiv((int) position.z, grid.macroCellSizeBlocks())
        );
        RegionPos centerRegion = grid.regionOf(centerCell);
        RegionPos minRegion = new RegionPos(centerRegion.x() - regionSpan / 2, centerRegion.z() - regionSpan / 2);

        Map<MacroCellPos, BiomeId> cells = new LinkedHashMap<>();
        for (int dx = 0; dx < regionSpan; dx++) {
            for (int dz = 0; dz < regionSpan; dz++) {
                RegionPos region = new RegionPos(minRegion.x() + dx, minRegion.z() + dz);
                for (MacroCellPos cell : grid.cellsInRegion(region)) {
                    cells.put(cell, sampleBiomeAt(level, grid, cell));
                }
            }
        }

        ExportSnapshot snapshot = new ExportSnapshot(
            ModBiomeGraph.DEFAULT, grid, level.getSeed(), minRegion, regionSpan, Map.copyOf(cells), Map.of()
        );

        Path outputDir = Path.of(OUTPUT_DIR_NAME);
        createOutputDir(outputDir);
        BiomeLegend legend = new BiomeLegend(ModBiomeGraph.DEFAULT);
        PngExporter.write(outputDir.resolve("regions.png"), snapshot, legend);
        AsciiExporter.write(outputDir.resolve("regions.txt"), snapshot, legend);
        JsonExporter.write(outputDir.resolve("regions.json"), snapshot, legend);

        return new Result(outputDir.toAbsolutePath(), cells.size());
    }

    /** Center block of the macro-cell — samples an already-generated chunk if present, generates it (like any other biome query) if not. */
    private static BiomeId sampleBiomeAt(ServerLevel level, MacroGridConfig grid, MacroCellPos cell) {
        int blockX = cell.x() * grid.macroCellSizeBlocks() + grid.macroCellSizeBlocks() / 2;
        int blockZ = cell.z() * grid.macroCellSizeBlocks() + grid.macroCellSizeBlocks() / 2;
        Holder<Biome> biome = level.getBiome(new BlockPos(blockX, level.getSeaLevel(), blockZ));
        return biome.unwrapKey()
            .map(key -> BiomeIdAdapter.toBiomeId(key.location()))
            .orElseThrow(() -> new IllegalStateException("Biome at " + blockX + "," + blockZ + " has no registry key (unregistered/dynamic biome?)"));
    }

    private static void createOutputDir(Path outputDir) {
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create export output directory " + outputDir, e);
        }
    }
}
