package com.mineutze.worldgen.coherence;

import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.coherence.AdjacencyDerivation.Edge;
import com.mineutze.worldgen.coherence.PeriodicRowLayout.Cell;
import com.mineutze.worldgen.coherence.PeriodicRowLayout.Row;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks a fully-built {@link PeriodicRowLayout} against every real constraint: adjacency
 * legality, required-neighbor minimums, and full-catalog coverage. Pure checking, no
 * search/repair - {@link PeriodicCoherenceProof} owns the search; a layout this class
 * accepts is a complete, definitive proof (see this package's own doc), not a score.
 *
 * <p>Cells are matched by reference identity ({@link IdentityHashMap}), not value equality
 * - two different physical cells (different rows, different positions) can legitimately
 * share the same biome/width/start by coincidence and must never be merged.
 */
final class ConstraintChecker {

    private ConstraintChecker() {
    }

    record Result(List<String> illegalAdjacencies, List<String> unmetRequiredNeighbors, List<BiomeId> missingBiomes) {
        boolean isValid() {
            return illegalAdjacencies.isEmpty() && unmetRequiredNeighbors.isEmpty() && missingBiomes.isEmpty();
        }
    }

    static Result check(PeriodicRowLayout layout, RawRuleTable table) {
        List<Edge> edges = AdjacencyDerivation.deriveAll(layout);

        List<String> illegalAdjacencies = new ArrayList<>();
        Map<Cell, List<Cell>> neighborsByCell = new IdentityHashMap<>();
        for (Edge edge : edges) {
            if (!table.isLegal(edge.a().biomeId(), edge.b().biomeId())) {
                illegalAdjacencies.add(
                    edge.a().biomeId() + " <-> " + edge.b().biomeId() + " (starts " + edge.a().startBlocks() + "/" + edge.b().startBlocks() + ")"
                );
            }
            neighborsByCell.computeIfAbsent(edge.a(), unused -> new ArrayList<>()).add(edge.b());
            neighborsByCell.computeIfAbsent(edge.b(), unused -> new ArrayList<>()).add(edge.a());
        }

        List<String> unmetRequiredNeighbors = new ArrayList<>();
        Set<BiomeId> usedBiomes = new LinkedHashSet<>();
        for (Row row : layout.rows()) {
            for (Cell cell : row.cells()) {
                usedBiomes.add(cell.biomeId());
                Map<BiomeId, Integer> required = table.requiredNeighborsOf(cell.biomeId());
                if (required.isEmpty()) {
                    continue;
                }
                List<Cell> neighbors = neighborsByCell.getOrDefault(cell, List.of());
                for (Map.Entry<BiomeId, Integer> requirement : required.entrySet()) {
                    long actualCount = neighbors.stream().filter(neighbor -> neighbor.biomeId().equals(requirement.getKey())).count();
                    if (actualCount < requirement.getValue()) {
                        unmetRequiredNeighbors.add(
                            cell.biomeId() + " at " + cell.startBlocks() + " needs >=" + requirement.getValue() + " " + requirement.getKey()
                                + " neighbor(s), has " + actualCount
                        );
                    }
                }
            }
        }

        List<BiomeId> missingBiomes = table.allBiomeIds().stream().filter(id -> !usedBiomes.contains(id)).toList();

        return new Result(illegalAdjacencies, unmetRequiredNeighbors, missingBiomes);
    }
}
