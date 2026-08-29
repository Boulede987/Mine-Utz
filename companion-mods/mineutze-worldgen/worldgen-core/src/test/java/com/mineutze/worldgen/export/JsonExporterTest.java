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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Direct test against a small hand-built {@link ExportSnapshot} - flagged by this session's test-coverage audit as having zero dedicated tests for this hand-rolled JSON writer. */
class JsonExporterTest {

    private static final BiomeId DESERT = new BiomeId("test", "desert");
    private static final BiomeId SAVANNA = new BiomeId("test", "savanna");

    private static ExportSnapshot snapshotWithOneGapCell() {
        BiomeGraph graph = new BiomeGraph(
            List.of(new BiomeNode(DESERT, new BiomeSizeRange(100, 200)), new BiomeNode(SAVANNA, new BiomeSizeRange(100, 200))),
            List.of(AdjacencyRule.allow(DESERT, SAVANNA, 10))
        );
        MacroGridConfig grid = new MacroGridConfig(4, 2);
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
    void writesTheWorldSeedGridAndRegionSpan(@TempDir Path tempDir) throws IOException {
        ExportSnapshot snapshot = snapshotWithOneGapCell();
        Path file = tempDir.resolve("out.json");

        JsonExporter.write(file, snapshot, new BiomeLegend(snapshot.graph()));
        String content = Files.readString(file);

        assertTrue(content.contains("\"worldSeed\": 1234"));
        assertTrue(content.contains("\"regionSpan\": 2"));
        assertTrue(content.contains("\"macroCellSizeBlocks\": 4"));
    }

    @Test
    void writesEveryGraphNodeAndTheRuleBetweenThem(@TempDir Path tempDir) throws IOException {
        ExportSnapshot snapshot = snapshotWithOneGapCell();
        Path file = tempDir.resolve("out.json");

        JsonExporter.write(file, snapshot, new BiomeLegend(snapshot.graph()));
        String content = Files.readString(file);

        assertTrue(content.contains("\"id\": \"" + DESERT + "\""));
        assertTrue(content.contains("\"id\": \"" + SAVANNA + "\""));
        assertTrue(content.contains("\"minWidthBlocks\": 100"));
        assertTrue(content.contains("\"a\": \"" + DESERT + "\", \"b\": \"" + SAVANNA + "\""));
    }

    @Test
    void writesFourRegionsForATwoByTwoSpan(@TempDir Path tempDir) throws IOException {
        ExportSnapshot snapshot = snapshotWithOneGapCell();
        Path file = tempDir.resolve("out.json");

        JsonExporter.write(file, snapshot, new BiomeLegend(snapshot.graph()));
        String content = Files.readString(file);

        long regionCount = content.lines().filter(line -> line.trim().startsWith("\"droppedDemandCount\"")).count();
        assertTrue(regionCount == 4, "regionSpan=2 must produce exactly 4 regions, got " + regionCount);
    }

    @Test
    void omitsTheGapCellButIncludesEveryRealCell(@TempDir Path tempDir) throws IOException {
        ExportSnapshot snapshot = snapshotWithOneGapCell();
        Path file = tempDir.resolve("out.json");

        JsonExporter.write(file, snapshot, new BiomeLegend(snapshot.graph()));
        String content = Files.readString(file);

        assertTrue(content.contains("\"x\": 0, \"z\": 0, \"biome\": \"" + DESERT + "\""));
        assertFalse(content.contains("\"x\": 3, \"z\": 3"), "the deliberately-omitted gap cell must never appear - a real-world scan can have real gaps, unlike a solved region");
    }
}
