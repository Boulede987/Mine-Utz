package com.mineutze.worldgen.export;

import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;
import com.mineutze.worldgen.regionsolve.MacroCellPos;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Plain-text render: legend (one glyph/biome) + grid, '|'/'-' dividers mark region boundaries - shows seam bugs (e.g. PatchSizeEnforcer border-size-blindness) without image viewer. */
public final class AsciiExporter {

    private AsciiExporter() {
    }

    public static void write(Path file, ExportSnapshot snapshot, BiomeLegend legend) {
        StringBuilder out = new StringBuilder();
        writeLegend(out, snapshot, legend);
        out.append('\n');
        writeGrid(out, snapshot, legend);

        try {
            Files.writeString(file, out);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed writing ASCII export to " + file, e);
        }
    }

    private static void writeLegend(StringBuilder out, ExportSnapshot snapshot, BiomeLegend legend) {
        out.append("Legend (worldSeed=").append(snapshot.worldSeed())
            .append(", regions=").append(snapshot.regionSpan()).append('x').append(snapshot.regionSpan())
            .append(" from ").append(snapshot.minRegion()).append("):\n");
        for (BiomeNode node : snapshot.graph().nodes().stream().sorted((a, b) -> a.id().compareTo(b.id())).toList()) {
            BiomeId id = node.id();
            out.append("  ").append(legend.glyphOf(id)).append(" = ").append(id).append('\n');
        }
    }

    private static void writeGrid(StringBuilder out, ExportSnapshot snapshot, BiomeLegend legend) {
        int regionSize = snapshot.gridConfig().regionSizeCellsPerSide();
        int span = snapshot.spanCells();

        for (int localZ = 0; localZ < span; localZ++) {
            for (int localX = 0; localX < span; localX++) {
                MacroCellPos cell = snapshot.cellAt(localX, localZ);
                out.append(snapshot.cells().containsKey(cell) ? legend.glyphOf(snapshot.biomeAt(cell)) : '?');
                if (localX + 1 < span && (localX + 1) % regionSize == 0) {
                    out.append('|');
                }
            }
            out.append('\n');
            if (localZ + 1 < span && (localZ + 1) % regionSize == 0) {
                out.append("-".repeat(span + span / regionSize - 1)).append('\n');
            }
        }
    }
}
