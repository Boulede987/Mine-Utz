package com.mineutze.worldgen.export.triangle;

import com.mineutze.worldgen.regionsolve.RegionPos;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Direct test for arg parsing and region-grid construction only - flagged by
 * test-coverage audit. {@code main()} itself drives a real production-scale solve
 * against ModBiomeGraph.DEFAULT, deliberately never invoked here - see
 * RegionGridSolverTest's own doc: too slow for a unit test. parseArgs and
 * buildRegionGrid promoted from private to package-private specifically for this.
 */
class TriangleFieldRegionExportCliTest {

    @Test
    void noArgsUsesAllDefaults() {
        TriangleFieldRegionExportCli.Args args = TriangleFieldRegionExportCli.parseArgs(new String[0]);

        assertEquals(42L, args.worldSeed());
        assertEquals(Path.of("triangle-field-export"), args.outputDir());
        assertEquals(1, args.regionsPerSide());
    }

    @Test
    void allThreeArgsAreParsedPositionally() {
        TriangleFieldRegionExportCli.Args args = TriangleFieldRegionExportCli.parseArgs(new String[] {"7", "custom-dir", "3"});

        assertEquals(7L, args.worldSeed());
        assertEquals(Path.of("custom-dir"), args.outputDir());
        assertEquals(3, args.regionsPerSide());
    }

    @Test
    void buildRegionGridCoversTheFullSquareFromOrigin() {
        List<RegionPos> regions = TriangleFieldRegionExportCli.buildRegionGrid(2);

        assertEquals(List.of(new RegionPos(0, 0), new RegionPos(0, 1), new RegionPos(1, 0), new RegionPos(1, 1)), regions);
    }

    @Test
    void buildRegionGridOfOneIsJustTheOrigin() {
        assertEquals(List.of(new RegionPos(0, 0)), TriangleFieldRegionExportCli.buildRegionGrid(1));
    }
}
