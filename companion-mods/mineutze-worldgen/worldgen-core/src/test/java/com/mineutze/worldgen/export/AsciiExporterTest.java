package com.mineutze.worldgen.export;

import com.mineutze.worldgen.biomegraph.AdjacencyRule;
import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;
import com.mineutze.worldgen.biomegraph.BiomeSizeRange;
import com.mineutze.worldgen.regionsolve.MacroCellPos;
import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mineutze.worldgen.regionsolve.RegionPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct test against small hand-built {@link ExportSnapshot} - flagged by test-coverage
 * audit as having zero dedicated tests. No real solve involved (see RegionGridSolverTest's
 * own doc: real multi-region solve too slow for a unit test); snapshot's cell map
 * populated by hand instead.
 */
class AsciiExporterTest {

    private static final BiomeId DESERT = new BiomeId("test", "desert");
    private static final BiomeId SAVANNA = new BiomeId("test", "savanna");

    private static ExportSnapshot snapshotWithOneGapCell() {
        BiomeGraph graph = new BiomeGraph(
            List.of(new BiomeNode(DESERT, new BiomeSizeRange(100, 200)), new BiomeNode(SAVANNA, new BiomeSizeRange(100, 200))),
            List.of(AdjacencyRule.allow(DESERT, SAVANNA, 10))
        );
        MacroGridConfig grid = new MacroGridConfig(4, 2); // 2x2 cells per region
        Map<MacroCellPos, BiomeId> cells = new HashMap<>();
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) {
                if (x == 3 && z == 3) {
                    continue; // deliberate gap cell
                }
                cells.put(new MacroCellPos(x, z), x < 2 ? DESERT : SAVANNA);
            }
        }
        return new ExportSnapshot(graph, grid, 1234L, new RegionPos(0, 0), 2, cells, Map.of());
    }

    @Test
    void writesAFileContainingTheLegendAndTheWorldSeed(@TempDir Path tempDir) throws IOException {
        ExportSnapshot snapshot = snapshotWithOneGapCell();
        BiomeLegend legend = new BiomeLegend(snapshot.graph());
        Path file = tempDir.resolve("out.txt");

        AsciiExporter.write(file, snapshot, legend);
        String content = Files.readString(file);

        assertTrue(content.contains("worldSeed=1234"));
        assertTrue(content.contains(legend.glyphOf(DESERT) + " = " + DESERT));
        assertTrue(content.contains(legend.glyphOf(SAVANNA) + " = " + SAVANNA));
    }

    @Test
    void rendersAMissingCellAsAQuestionMark(@TempDir Path tempDir) throws IOException {
        ExportSnapshot snapshot = snapshotWithOneGapCell();
        BiomeLegend legend = new BiomeLegend(snapshot.graph());
        Path file = tempDir.resolve("out.txt");

        AsciiExporter.write(file, snapshot, legend);
        String content = Files.readString(file);

        assertTrue(content.contains("?"), "the deliberately-omitted cell must render as a gap marker, not silently vanish or crash");
    }

    @Test
    void marksRegionBoundariesWithDividers(@TempDir Path tempDir) throws IOException {
        // 2x2 regions of 2x2 cells each - real boundary sits between local rows/columns 1 and 2.
        ExportSnapshot snapshot = snapshotWithOneGapCell();
        BiomeLegend legend = new BiomeLegend(snapshot.graph());
        Path file = tempDir.resolve("out.txt");

        AsciiExporter.write(file, snapshot, legend);
        String content = Files.readString(file);

        assertTrue(content.contains("|"), "a vertical region boundary must be visible");
        assertTrue(content.contains("-"), "a horizontal region boundary divider row must be visible");
    }
}
