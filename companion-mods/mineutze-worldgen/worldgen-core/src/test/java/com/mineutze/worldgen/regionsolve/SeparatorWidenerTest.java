package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.AdjacencyRule;
import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.mineutze.worldgen.regionsolve.TestBiomeNodes.id;
import static com.mineutze.worldgen.regionsolve.TestBiomeNodes.node;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeparatorWidenerTest {

    private static final BiomeId BADLANDS = id("badlands");

    @Test
    void stealingATipCellNeverCountsAsDisconnecting() {
        // A single 1-cell blob: its only cell has zero same-biome neighbors.
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>(Map.of(new MacroCellPos(0, 0), BADLANDS));
        Set<MacroCellPos> regionCellSet = Set.of(new MacroCellPos(0, 0), new MacroCellPos(1, 0));

        assertTrue(SeparatorWidener.stealingPreservesDonorConnectivity(new MacroCellPos(0, 0), assignment, regionCellSet, Set.of()));
    }

    @Test
    void stealingANeckCellThatBridgesTwoLobesIsRejected() {
        // Two 3x3 badlands blobs joined by a single-cell neck at (3,1).
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        Set<MacroCellPos> regionCellSet = new HashSet<>();
        for (int x = 0; x <= 2; x++) {
            for (int z = 0; z <= 2; z++) {
                place(assignment, regionCellSet, x, z);
            }
        }
        place(assignment, regionCellSet, 3, 1); // the neck
        for (int x = 4; x <= 6; x++) {
            for (int z = 0; z <= 2; z++) {
                place(assignment, regionCellSet, x, z);
            }
        }

        assertFalse(
            SeparatorWidener.stealingPreservesDonorConnectivity(new MacroCellPos(3, 1), assignment, regionCellSet, Set.of()),
            "removing the neck would split the two lobes into disconnected islands"
        );
    }

    @Test
    void stealingACellWithAnAlternatePathIsAllowed() {
        // A ring/loop of badlands: every cell has two same-biome neighbors, but
        // removing any single one still leaves the rest connected the other way around.
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        Set<MacroCellPos> regionCellSet = new HashSet<>();
        List<MacroCellPos> ring = List.of(
            new MacroCellPos(0, 0), new MacroCellPos(1, 0), new MacroCellPos(2, 0),
            new MacroCellPos(2, 1), new MacroCellPos(2, 2), new MacroCellPos(1, 2),
            new MacroCellPos(0, 2), new MacroCellPos(0, 1)
        );
        ring.forEach(cell -> place(assignment, regionCellSet, cell.x(), cell.z()));

        assertTrue(
            SeparatorWidener.stealingPreservesDonorConnectivity(new MacroCellPos(1, 0), assignment, regionCellSet, Set.of()),
            "the ring stays connected the other way around even with one cell removed"
        );
    }

    @Test
    void widenNeverDisconnectsTheDonorPatchEvenWhenItStaysUndersized() {
        BiomeId savanna = id("savanna");
        BiomeId ocean = id("ocean");
        BiomeId filler = id("filler");
        BiomeGraph graph = new BiomeGraph(
            List.of(
                node("badlands", 100, 200, false),
                node("savanna", 100, 200, false),
                node("ocean", 100, 200, false),
                node("filler", 5000, 5000, true)
            ),
            List.of(
                AdjacencyRule.allow(BADLANDS, filler, 10),
                AdjacencyRule.allow(savanna, filler, 10),
                AdjacencyRule.allow(ocean, filler, 10)
            )
        );

        // Same dumbbell, but this time run it through the real widen() entry point with an
        // enormous target (impossible to satisfy) so it's forced to keep eating everything
        // legally available, including attempting the neck.
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        Set<MacroCellPos> regionCellSet = new HashSet<>();
        for (int x = 0; x <= 2; x++) {
            for (int z = 0; z <= 2; z++) {
                place(assignment, regionCellSet, x, z);
            }
        }
        place(assignment, regionCellSet, 3, 1);
        for (int x = 4; x <= 6; x++) {
            for (int z = 0; z <= 2; z++) {
                place(assignment, regionCellSet, x, z);
            }
        }
        assignment.put(new MacroCellPos(3, 0), filler);
        regionCellSet.add(new MacroCellPos(3, 0));
        for (MacroCellPos cell : regionCellSet) {
            assignment.putIfAbsent(cell, savanna);
        }

        Set<MacroCellPos> fillerComponent = Set.of(new MacroCellPos(3, 0));
        SeparatorWidener.widen(fillerComponent, filler, 5000, assignment, regionCellSet, graph, Set.of());

        long badlandsComponents = countComponents(assignment, regionCellSet, BADLANDS);
        assertTrue(badlandsComponents <= 1, "badlands must stay in one connected piece (or be fully consumed), never split into islands");
    }

    private static void place(Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet, int x, int z) {
        MacroCellPos cell = new MacroCellPos(x, z);
        assignment.put(cell, BADLANDS);
        regionCellSet.add(cell);
    }

    private static long countComponents(Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet, BiomeId target) {
        Set<MacroCellPos> visited = new HashSet<>();
        long count = 0;
        for (MacroCellPos start : regionCellSet) {
            if (visited.contains(start) || !target.equals(assignment.get(start))) {
                continue;
            }
            count++;
            Deque<MacroCellPos> stack = new ArrayDeque<>(List.of(start));
            visited.add(start);
            while (!stack.isEmpty()) {
                MacroCellPos current = stack.pop();
                for (MacroCellPos neighbor : current.orthogonalNeighbors()) {
                    if (regionCellSet.contains(neighbor) && !visited.contains(neighbor) && target.equals(assignment.get(neighbor))) {
                        visited.add(neighbor);
                        stack.push(neighbor);
                    }
                }
            }
        }
        return count;
    }
}
