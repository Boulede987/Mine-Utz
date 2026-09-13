package com.mineutze.worldgen.export.hex;

import com.mineutze.worldgen.regionsolve.topology.mesh.HexMeshBuilder;

import com.mineutze.worldgen.regionsolve.topology.placement.WeightedVertexPlacement;

import com.mineutze.worldgen.regionsolve.topology.identity.TriangleIdentitySizeAssignment;

import com.mineutze.worldgen.regionsolve.topology.mesh.FaceAdjacency;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.ModBiomeGraph;
import com.mineutze.worldgen.regionsolve.topology.Point2D;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Real realized-vs-target area ratio per biome, 10-seed sample against real catalog
 * (fresh, unconstrained solve - no fixed context). Rerun after any geometry/size-fidelity
 * change to confirm real before/after numbers - same role {@link
 * DeadEndFrequencySimulation} plays for dead-end frequency, a repeatable diagnostic, not
 * a one-off script.
 */
public final class SizeFidelityMeasurement {

    private static final double HEX_SIZE_BLOCKS = 325;
    private static final int MIN_EDGE_LENGTH_BLOCKS = 24; // matches production: MIN_EDGE_LENGTH_MACRO_CELLS(6) * macroCellSizeBlocks(4)
    private static final int HALF_WIDTH_BLOCKS = 3000;
    private static final int SEED_COUNT = 10;
    private static final long SEED_BASE = 2026L;
    // Mirrors WeightedVertexPlacement.MAX_VERTEX_DISPLACEMENT_FRACTION - duplicated here
    // (private there) to diagnose, from OUTSIDE via public API only, whether displacement
    // clamp actually binds for an oversized face (Direction 2 investigation, size-fidelity
    // plan): maxDisplacementFraction near 1.0 = clamp IS the limiting factor (real geometry
    // problem); well below 1.0 = clamp never engaged, oversizing comes from calibration's
    // own convergence instead (different problem, different fix).
    private static final double MAX_VERTEX_DISPLACEMENT_FRACTION = 0.8; // keep in sync with WeightedVertexPlacement's own value
    private static final double OVERSIZED_RATIO_THRESHOLD_FOR_DIAGNOSTIC = 1.3;

    /** One connected same-biome cluster (flood-filled over mutually-adjacent free faces holding identical biome) - {@code size == 1} is ordinary no-self-adjacency case, already covered by per-hex ratio above. */
    private record Cluster(BiomeId biome, int size, double totalRealizedArea, double averageOwnTargetArea) {
        double totalVsSingleTargetRatio() {
            return totalRealizedArea / averageOwnTargetArea;
        }
    }

    /** Direction 2 diagnostic: does displacement clamp actually bind for this oversized face, or did geometry layer never even try to constrain it? */
    private record OversizedFaceDiagnostic(BiomeId biome, long worldSeed, int faceIndex, double ratio, double maxDisplacementFraction) {
    }

    private static final double UNDERSIZED_RATIO_THRESHOLD_FOR_DIAGNOSTIC = 0.5;

    /** Symmetric to {@link OversizedFaceDiagnostic} - does clamp bind for a badly UNDERsized face too, or is undersizing purely calibration-convergence? */
    private record UndersizedFaceDiagnostic(BiomeId biome, long worldSeed, int faceIndex, double ratio, double maxDisplacementFraction) {
    }

    public static void main(String[] args) {
        BiomeGraph graph = ModBiomeGraph.OVERWORLD;
        Map<BiomeId, List<Double>> ratiosByBiome = new HashMap<>();
        List<Cluster> multiFaceClusters = new ArrayList<>();
        List<OversizedFaceDiagnostic> oversizedDiagnostics = new ArrayList<>();
        List<UndersizedFaceDiagnostic> undersizedDiagnostics = new ArrayList<>();

        for (int seedOffset = 0; seedOffset < SEED_COUNT; seedOffset++) {
            long worldSeed = SEED_BASE + seedOffset;
            HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(HEX_SIZE_BLOCKS, -HALF_WIDTH_BLOCKS, -HALF_WIDTH_BLOCKS, HALF_WIDTH_BLOCKS, HALF_WIDTH_BLOCKS);
            FaceAdjacency.Adjacency adjacency = FaceAdjacency.of(mesh.faces());

            java.util.Set<Integer> freeFaces = new java.util.LinkedHashSet<>();
            for (int i = 0; i < mesh.faces().size(); i++) {
                if (!adjacency.hullTouchingFaceIndices().contains(i)) {
                    freeFaces.add(i);
                }
            }

            double areaBlocks2 = (double) (2 * HALF_WIDTH_BLOCKS) * (2 * HALF_WIDTH_BLOCKS);
            Optional<Map<Integer, TriangleIdentitySizeAssignment.Assignment>> solved =
                TriangleIdentitySizeAssignment.solve(adjacency, freeFaces, Map.of(), graph, worldSeed, areaBlocks2);
            if (solved.isEmpty()) {
                System.out.println("worldSeed=" + worldSeed + ": no legal assignment (skipping this seed)");
                continue;
            }
            Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments = solved.get();
            Map<Integer, Point2D> displaced = WeightedVertexPlacement.displacedVertexPositions(mesh, adjacency, assignments, HEX_SIZE_BLOCKS, MIN_EDGE_LENGTH_BLOCKS);

            for (int faceIndex : freeFaces) {
                TriangleIdentitySizeAssignment.Assignment assignment = assignments.get(faceIndex);
                double realized = shoelaceArea(mesh.faces().get(faceIndex).vertexIndices(), displaced);
                double target = targetArea(assignment.widthBlocks());
                double ratio = realized / target;
                ratiosByBiome.computeIfAbsent(assignment.biome(), b -> new ArrayList<>()).add(ratio);
                if (ratio >= OVERSIZED_RATIO_THRESHOLD_FOR_DIAGNOSTIC) {
                    double maxDisplacementFraction = maxDisplacementFraction(mesh, faceIndex, displaced);
                    oversizedDiagnostics.add(new OversizedFaceDiagnostic(assignment.biome(), worldSeed, faceIndex, ratio, maxDisplacementFraction));
                }
                if (ratio <= UNDERSIZED_RATIO_THRESHOLD_FOR_DIAGNOSTIC) {
                    double maxDisplacementFraction = maxDisplacementFraction(mesh, faceIndex, displaced);
                    undersizedDiagnostics.add(new UndersizedFaceDiagnostic(assignment.biome(), worldSeed, faceIndex, ratio, maxDisplacementFraction));
                }
            }
            multiFaceClusters.addAll(sameBiomeClusters(mesh, adjacency, freeFaces, assignments, displaced));
            System.out.println("worldSeed=" + worldSeed + ": solved " + freeFaces.size() + " faces");
        }

        System.out.println();
        System.out.println("=== per-biome realized/target area ratio (all seeds combined), sorted worst-undersized first ===");
        List<Double> allRatios = new ArrayList<>();
        Map<BiomeId, Double> medianByBiome = new TreeMap<>();
        for (Map.Entry<BiomeId, List<Double>> entry : ratiosByBiome.entrySet()) {
            List<Double> ratios = entry.getValue();
            allRatios.addAll(ratios);
            medianByBiome.put(entry.getKey(), median(ratios));
        }
        medianByBiome.entrySet().stream()
            .sorted(Map.Entry.comparingByValue())
            .forEach(e -> {
                int n = ratiosByBiome.get(e.getKey()).size();
                System.out.printf("%-32s median=%.3f  n=%d%n", e.getKey(), e.getValue(), n);
            });

        System.out.println();
        System.out.printf("OVERALL median ratio: %.3f (n=%d faces)%n", median(allRatios), allRatios.size());

        System.out.println();
        System.out.println("=== self-adjacent clusters (size > 1) - does the biome's FELT area actually grow? ===");
        Map<BiomeId, List<Cluster>> clustersByBiome = new TreeMap<>();
        for (Cluster cluster : multiFaceClusters) {
            clustersByBiome.computeIfAbsent(cluster.biome(), b -> new ArrayList<>()).add(cluster);
        }
        if (clustersByBiome.isEmpty()) {
            System.out.println("(none formed across these seeds)");
        }
        for (Map.Entry<BiomeId, List<Cluster>> entry : clustersByBiome.entrySet()) {
            List<Cluster> clusters = entry.getValue();
            double avgSize = clusters.stream().mapToInt(Cluster::size).average().orElse(0);
            double avgTotalVsSingleRatio = clusters.stream().mapToDouble(Cluster::totalVsSingleTargetRatio).average().orElse(0);
            System.out.printf("%-32s clusters=%d  avgClusterSize=%.2f  avgTotalArea/singleTarget=%.2fx%n",
                entry.getKey(), clusters.size(), avgSize, avgTotalVsSingleRatio);
        }

        System.out.println();
        System.out.printf("=== Direction 2 diagnostic: is the displacement clamp binding for faces >= %.1fx oversized? ===%n", OVERSIZED_RATIO_THRESHOLD_FOR_DIAGNOSTIC);
        if (oversizedDiagnostics.isEmpty()) {
            System.out.println("(no faces reached the threshold this sample)");
        }
        oversizedDiagnostics.stream()
            .sorted((a, b) -> Double.compare(b.ratio(), a.ratio()))
            .forEach(d -> System.out.printf(
                "%-32s ratio=%.2f  maxDisplacementFraction=%.3f  (worldSeed=%d, faceIndex=%d)%n",
                d.biome(), d.ratio(), d.maxDisplacementFraction(), d.worldSeed(), d.faceIndex()
            ));
        double avgMaxDisplacementFraction = oversizedDiagnostics.stream().mapToDouble(OversizedFaceDiagnostic::maxDisplacementFraction).average().orElse(Double.NaN);
        System.out.printf("average maxDisplacementFraction across %d oversized faces: %.3f%n", oversizedDiagnostics.size(), avgMaxDisplacementFraction);

        System.out.println();
        System.out.printf("=== Direction 1 diagnostic: is the displacement clamp binding for faces <= %.1fx undersized? ===%n", UNDERSIZED_RATIO_THRESHOLD_FOR_DIAGNOSTIC);
        if (undersizedDiagnostics.isEmpty()) {
            System.out.println("(no faces reached the threshold this sample)");
        }
        undersizedDiagnostics.stream()
            .sorted(Comparator.comparingDouble(UndersizedFaceDiagnostic::ratio))
            .forEach(d -> System.out.printf(
                "%-32s ratio=%.2f  maxDisplacementFraction=%.3f  (worldSeed=%d, faceIndex=%d)%n",
                d.biome(), d.ratio(), d.maxDisplacementFraction(), d.worldSeed(), d.faceIndex()
            ));
        double avgMaxDisplacementFractionUndersized = undersizedDiagnostics.stream().mapToDouble(UndersizedFaceDiagnostic::maxDisplacementFraction).average().orElse(Double.NaN);
        System.out.printf("average maxDisplacementFraction across %d undersized faces: %.3f%n", undersizedDiagnostics.size(), avgMaxDisplacementFractionUndersized);
    }

    /** Flood-fills mutually-adjacent free faces sharing an identity into clusters, keeps only clusters bigger than 1 face - ordinary size==1 case already covered by per-hex ratio above. */
    private static List<Cluster> sameBiomeClusters(
        HexMeshBuilder.HexMesh mesh, FaceAdjacency.Adjacency adjacency, java.util.Set<Integer> freeFaces,
        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments, Map<Integer, Point2D> displaced
    ) {
        List<Cluster> clusters = new ArrayList<>();
        java.util.Set<Integer> visited = new java.util.HashSet<>();
        for (int start : freeFaces) {
            if (!visited.add(start)) {
                continue;
            }
            BiomeId biome = assignments.get(start).biome();
            List<Integer> members = new ArrayList<>();
            java.util.Deque<Integer> frontier = new java.util.ArrayDeque<>();
            frontier.add(start);
            while (!frontier.isEmpty()) {
                int current = frontier.poll();
                members.add(current);
                for (int neighbor : adjacency.neighborFaceIndices().getOrDefault(current, java.util.Set.of())) {
                    if (!freeFaces.contains(neighbor) || visited.contains(neighbor)) {
                        continue;
                    }
                    if (biome.equals(assignments.get(neighbor).biome())) {
                        visited.add(neighbor);
                        frontier.add(neighbor);
                    }
                }
            }
            if (members.size() > 1) {
                double totalArea = 0;
                double totalTarget = 0;
                for (int member : members) {
                    totalArea += shoelaceArea(mesh.faces().get(member).vertexIndices(), displaced);
                    totalTarget += targetArea(assignments.get(member).widthBlocks());
                }
                clusters.add(new Cluster(biome, members.size(), totalArea, totalTarget / members.size()));
            }
        }
        return clusters;
    }

    /** Largest per-vertex displacement (from raw undisplaced hex lattice position) among this face's own 6 corners, as fraction of max clamp would allow - near 1.0 = clamp actively binding, well below = never engaged. */
    private static double maxDisplacementFraction(HexMeshBuilder.HexMesh mesh, int faceIndex, Map<Integer, Point2D> displaced) {
        double maxAllowedDisplacementBlocks = MAX_VERTEX_DISPLACEMENT_FRACTION * HEX_SIZE_BLOCKS;
        double maxFraction = 0;
        for (int vertexIndex : mesh.faces().get(faceIndex).vertexIndices()) {
            Point2D original = mesh.vertices().get(vertexIndex);
            Point2D moved = displaced.getOrDefault(vertexIndex, original);
            double displacementBlocks = Math.hypot(moved.x() - original.x(), moved.z() - original.z());
            maxFraction = Math.max(maxFraction, displacementBlocks / maxAllowedDisplacementBlocks);
        }
        return maxFraction;
    }

    private static double shoelaceArea(List<Integer> vertexIndices, Map<Integer, Point2D> positions) {
        double sum = 0;
        int n = vertexIndices.size();
        for (int i = 0; i < n; i++) {
            Point2D a = positions.get(vertexIndices.get(i));
            Point2D b = positions.get(vertexIndices.get((i + 1) % n));
            sum += a.x() * b.z() - b.x() * a.z();
        }
        return Math.abs(sum) / 2.0;
    }

    private static double targetArea(int widthBlocks) {
        double circumradius = widthBlocks / 2.0;
        return (3.0 * Math.sqrt(3.0) / 2.0) * circumradius * circumradius;
    }

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        if (n == 0) {
            return Double.NaN;
        }
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }
}
