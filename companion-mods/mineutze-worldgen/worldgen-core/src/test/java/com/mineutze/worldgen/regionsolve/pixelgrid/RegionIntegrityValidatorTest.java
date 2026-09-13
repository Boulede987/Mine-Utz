package com.mineutze.worldgen.regionsolve.pixelgrid;

import com.mineutze.worldgen.regionsolve.MacroCellPos;

import com.mineutze.worldgen.biomegraph.AdjacencyRule;
import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.mineutze.worldgen.regionsolve.TestBiomeNodes.id;
import static com.mineutze.worldgen.regionsolve.TestBiomeNodes.node;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionIntegrityValidatorTest {

    private static final BiomeId A = id("a");
    private static final BiomeId B = id("b");
    private static final BiomeId C = id("c");

    private static BiomeGraph graph(AdjacencyRule... rules) {
        return new BiomeGraph(List.of(node("a", 100, 200), node("b", 100, 200), node("c", 100, 200)), List.of(rules));
    }

    @Test
    void cleanUniformGridReportsNoViolations() {
        BiomeGraph graph = graph(AdjacencyRule.allow(A, B, 10), AdjacencyRule.allow(A, C, 10), AdjacencyRule.allow(B, C, 10));
        Map<MacroCellPos, BiomeId> assignment = new LinkedHashMap<>();
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                assignment.put(new MacroCellPos(x, z), A);
            }
        }

        RegionIntegrityValidator.Report report = RegionIntegrityValidator.validate(assignment, graph);

        assertTrue(report.isClean(), report.describe());
    }

    @Test
    void illegalOrthogonalAdjacencyDetected() {
        BiomeGraph graph = graph(AdjacencyRule.allow(A, C, 10), AdjacencyRule.allow(B, C, 10));
        Map<MacroCellPos, BiomeId> assignment = Map.of(
            new MacroCellPos(0, 0), A,
            new MacroCellPos(1, 0), B
        );

        RegionIntegrityValidator.Report report = RegionIntegrityValidator.validate(assignment, graph);

        assertEquals(1, report.illegalAdjacencies().size(), report.describe());
        assertTrue(report.diagonalIllegalAdjacencies().isEmpty());
    }

    /**
     * A and B touch ONLY at corner (0,0)-(1,1) - elbows (1,0) and (0,1) are both C, which
     * is legal next to both A and B, so nothing here caught by plain orthogonal check.
     */
    @Test
    void diagonalIllegalAdjacencyDetectedWhenNoOrthogonalContact() {
        BiomeGraph graph = graph(AdjacencyRule.allow(A, C, 10), AdjacencyRule.allow(B, C, 10));
        Map<MacroCellPos, BiomeId> assignment = Map.of(
            new MacroCellPos(0, 0), A,
            new MacroCellPos(1, 0), C,
            new MacroCellPos(0, 1), C,
            new MacroCellPos(1, 1), B
        );

        RegionIntegrityValidator.Report report = RegionIntegrityValidator.validate(assignment, graph);

        assertTrue(report.illegalAdjacencies().isEmpty(), report.describe());
        assertEquals(1, report.diagonalIllegalAdjacencies().size(), report.describe());
    }

    @Test
    void smallFragmentDetected() {
        BiomeGraph graph = graph(AdjacencyRule.allow(A, B, 10), AdjacencyRule.allow(A, C, 10), AdjacencyRule.allow(B, C, 10));
        Map<MacroCellPos, BiomeId> assignment = new LinkedHashMap<>();
        assignment.put(new MacroCellPos(0, 0), A); // isolated single cell, legal next to its surroundings
        // Full 8-cell ring of B around A (not just 4 orthogonal neighbors, which would be
        // 4 mutually-disconnected 1-cell fragments, not one real surrounding patch) -
        // connected, size 8, well above SMALL_FRAGMENT_MAX_SIZE.
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x != 0 || z != 0) {
                    assignment.put(new MacroCellPos(x, z), B);
                }
            }
        }

        RegionIntegrityValidator.Report report = RegionIntegrityValidator.validate(assignment, graph);

        assertTrue(report.illegalAdjacencies().isEmpty(), report.describe());
        assertEquals(1, report.smallFragments().size(), report.describe());
        assertEquals(A, report.smallFragments().get(0).biome());
    }

    /**
     * 6-cell-wide strip of A, sandwiched between B to north and south everywhere - every
     * middle cell has same-biome east/west neighbors (not pinched horizontally) but
     * different biome on both north and south (pinched vertically). Strip is 6 cells,
     * above SMALL_FRAGMENT_MAX_SIZE, so not also flagged as small fragment.
     */
    @Test
    void pinchPointDetectedAlongCorrectAxis() {
        BiomeGraph graph = graph(AdjacencyRule.allow(A, B, 10), AdjacencyRule.allow(A, C, 10), AdjacencyRule.allow(B, C, 10));
        Map<MacroCellPos, BiomeId> assignment = new LinkedHashMap<>();
        for (int x = 0; x < 6; x++) {
            assignment.put(new MacroCellPos(x, 0), A);
            assignment.put(new MacroCellPos(x, -1), B);
            assignment.put(new MacroCellPos(x, 1), B);
        }

        RegionIntegrityValidator.Report report = RegionIntegrityValidator.validate(assignment, graph);

        assertTrue(report.illegalAdjacencies().isEmpty(), report.describe());
        assertTrue(report.smallFragments().isEmpty(), report.describe());
        assertTrue(report.pinchPoints().size() >= 4, report.describe());
        for (RegionIntegrityValidator.PinchPoint pinch : report.pinchPoints()) {
            assertEquals("north-south", pinch.axis());
        }
    }

    /**
     * 2-wide diagonal staircase band of A: (i,i) and (i+1,i) for i=0..5, orthogonally
     * connected end-to-end (real 12-cell component, not fragment). Outer-edge cells (e.g.
     * (0,0)) fail vertical orthogonal axis check (north AND south both B/missing) despite
     * having same-biome DIAGONAL neighbor ((1,1)) that shows band is genuinely 2 cells
     * thick there, not real 1-cell pinch. None of it should be flagged.
     */
    @Test
    void diagonalBandNotFlaggedAsPinch() {
        BiomeGraph graph = graph(AdjacencyRule.allow(A, B, 10), AdjacencyRule.allow(A, C, 10), AdjacencyRule.allow(B, C, 10));
        Map<MacroCellPos, BiomeId> assignment = new LinkedHashMap<>();
        for (int i = 0; i < 6; i++) {
            assignment.put(new MacroCellPos(i, i), A);
            assignment.put(new MacroCellPos(i + 1, i), A);
        }
        for (int i = -1; i <= 7; i++) {
            for (int j = -1; j <= 6; j++) {
                MacroCellPos pos = new MacroCellPos(i, j);
                if (!assignment.containsKey(pos)) {
                    assignment.put(pos, B);
                }
            }
        }

        RegionIntegrityValidator.Report report = RegionIntegrityValidator.validate(assignment, graph);

        assertTrue(report.illegalAdjacencies().isEmpty(), report.describe());
        for (RegionIntegrityValidator.PinchPoint pinch : report.pinchPoints()) {
            assertTrue(!pinch.biome().equals(A), "diagonal band cell wrongly flagged: " + pinch);
        }
    }
}
