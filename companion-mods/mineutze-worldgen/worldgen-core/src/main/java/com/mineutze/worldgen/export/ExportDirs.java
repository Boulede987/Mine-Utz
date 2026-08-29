package com.mineutze.worldgen.export;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Shared by every export CLI's own main() - was independently duplicated verbatim in each before this. */
public final class ExportDirs {

    private ExportDirs() {
    }

    public static void createOutputDir(Path outputDir) {
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create output directory " + outputDir, e);
        }
    }
}
