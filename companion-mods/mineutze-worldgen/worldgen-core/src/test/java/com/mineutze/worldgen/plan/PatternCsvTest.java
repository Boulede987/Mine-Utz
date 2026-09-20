package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static com.mineutze.worldgen.plan.ToroidalTestTables.A;
import static com.mineutze.worldgen.plan.ToroidalTestTables.B;
import static com.mineutze.worldgen.plan.ToroidalTestTables.C;
import static com.mineutze.worldgen.plan.ToroidalTestTables.threeColoring;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PatternCsvTest {

    private static final ToroidalGrid GRID = new ToroidalGrid(6, 6);

    @Test
    void aWrittenPatternReadsBackIdentically(@TempDir Path directory) {
        Path file = directory.resolve("pattern.csv");
        Map<ToroidalPos, BiomeId> pattern = threeColoring(GRID, A, B, C);

        PatternCsv.write(file, pattern);

        assertEquals(pattern, PatternCsv.read(file, GRID));
    }

    @Test
    void writingCreatesMissingParentDirectories(@TempDir Path directory) {
        Path file = directory.resolve("a").resolve("b").resolve("pattern.csv");

        PatternCsv.write(file, threeColoring(GRID, A, B, C));

        assertEquals(36, PatternCsv.read(file, GRID).size());
    }

    @Test
    void aFileMissingCellsIsRejectedInsteadOfHalfLoaded(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("short.csv");
        Files.writeString(file, "0,0,test:a\n1,0,test:b\n");

        assertThrows(IllegalStateException.class, () -> PatternCsv.read(file, GRID));
    }

    @Test
    void aCellOutsideTheGridIsRejected(@TempDir Path directory) {
        Path file = directory.resolve("wide.csv");
        Map<ToroidalPos, BiomeId> pattern = threeColoring(GRID, A, B, C);
        PatternCsv.write(file, pattern);

        assertThrows(IllegalStateException.class, () -> PatternCsv.read(file, new ToroidalGrid(3, 3)));
    }
}
