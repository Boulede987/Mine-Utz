package com.mineutze.worldgen.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Direct test - flagged by this session's test-coverage audit: the entire export/ package had zero tests despite being this session's own repeated manual CLI-verification target. */
class ExportDirsTest {

    @Test
    void createsNestedDirectoriesThatDoNotYetExist(@TempDir Path tempDir) {
        Path nested = tempDir.resolve("a/b/c");

        ExportDirs.createOutputDir(nested);

        assertTrue(Files.isDirectory(nested));
    }

    @Test
    void isANoOpWhenTheDirectoryAlreadyExists(@TempDir Path tempDir) {
        Path existing = tempDir.resolve("already-here");
        ExportDirs.createOutputDir(existing);

        assertDoesNotThrow(() -> ExportDirs.createOutputDir(existing));
    }
}
