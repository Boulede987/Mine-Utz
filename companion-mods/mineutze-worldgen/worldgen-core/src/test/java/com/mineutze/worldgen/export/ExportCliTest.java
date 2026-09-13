package com.mineutze.worldgen.export;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Direct test for arg parsing only - flagged by test-coverage audit. {@code main()}
 * itself drives a real production-scale solve (ModBiomeGraph.DEFAULT +
 * MacroGridConfig.DEFAULT, ~262k cells per region), deliberately never invoked here - see
 * RegionGridSolverTest's own doc: too slow for a unit test. parseArgs promoted from
 * private to package-private specifically for this.
 */
class ExportCliTest {

    @Test
    void noArgsUsesAllDefaults() {
        ExportCli.Args args = ExportCli.parseArgs(new String[0]);

        assertEquals(0L, args.worldSeed());
        assertEquals(3, args.regionSpan());
        assertEquals(Path.of("worldgen-export"), args.outputDir());
    }

    @Test
    void onlyWorldSeedProvidedKeepsTheRestDefault() {
        ExportCli.Args args = ExportCli.parseArgs(new String[] {"99"});

        assertEquals(99L, args.worldSeed());
        assertEquals(3, args.regionSpan());
        assertEquals(Path.of("worldgen-export"), args.outputDir());
    }

    @Test
    void allThreeArgsAreParsedPositionally() {
        ExportCli.Args args = ExportCli.parseArgs(new String[] {"7", "5", "custom-dir"});

        assertEquals(7L, args.worldSeed());
        assertEquals(5, args.regionSpan());
        assertEquals(Path.of("custom-dir"), args.outputDir());
    }
}
