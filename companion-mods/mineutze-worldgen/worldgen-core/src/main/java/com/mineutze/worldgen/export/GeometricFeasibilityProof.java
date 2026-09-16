package com.mineutze.worldgen.export;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeSizeRange;
import com.mineutze.worldgen.biomegraph.ModBiomeGraph;
import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mineutze.worldgen.regionsolve.livepath.TriangleFieldGeometryRealization;
import com.mineutze.worldgen.regionsolve.topology.Point2D;
import com.mineutze.worldgen.regionsolve.topology.identity.TriangleIdentitySizeAssignment;
import com.mineutze.worldgen.regionsolve.topology.mesh.FaceAdjacency;
import com.mineutze.worldgen.regionsolve.topology.mesh.HexGrid;
import com.mineutze.worldgen.regionsolve.topology.mesh.HexMeshBuilder;
import com.mineutze.worldgen.regionsolve.topology.placement.WeightedVertexPlacement;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exhaustive (not sampled) geometric-feasibility proof, one level beyond {@link
 * BiomeGraphCompletenessReport}'s identity-only check. That report already proves,
 * exhaustively, which partial neighbor subsets have ZERO legal common candidate (a pure
 * identity impossibility - geometry can never rescue it, so those subsets are excluded
 * here as already-known). The open, harder question this class answers: among subsets
 * that DO have a legal common candidate abstractly, is there a real, reachable
 * combination of neighbor SIZES for which none of those candidates actually fits,
 * geometrically? {@code BiomeGraphCompletenessReport} is blind to this entirely - it
 * never consults a width, a mesh, or the real solver.
 *
 * <p><b>Exact width reduction, not a sampled approximation.</b> {@link
 * WeightedVertexPlacement}'s own class doc states a proven theorem: increasing one face's
 * weight (target width) strictly increases its own realized area and strictly decreases
 * every neighbor's, for any fixed set of others. Since every neighbor's effect on the
 * center's achievable area points the same direction, the center's true achievable-area
 * envelope over EVERY possible combination of real neighbor widths (each independently
 * anywhere in its own biome's legal range) is exactly bounded by two corners: every
 * neighbor simultaneously at its OWN minimum legal width (loosest squeeze on the center),
 * and every neighbor simultaneously at its OWN maximum (tightest). Testing only those two
 * corners is therefore a real proof over the full continuous width space, not a bounded
 * heuristic.
 *
 * <p><b>Reuses the exact production solver.</b> Every candidate is checked via the same
 * {@link TriangleIdentitySizeAssignment#solve} call {@code
 * TriangleFieldGeometryRealization#realizeCluster} makes on a live crash, against a real
 * mesh built by {@link HexMeshBuilder} and real geometry from {@link
 * WeightedVertexPlacement} - never a simplified re-implementation. A recorded failure
 * here is exactly as real, reproducible, and non-probabilistic as an actual in-game dead
 * end - the same {@code SearchBudget}-backed complete backtracking search either finds an
 * assignment or exhausts its domain, no third outcome (exceeding budget throws a
 * distinctly-worded exception instead of a false "no solution" - see {@code
 * SearchBudget}'s own doc).
 *
 * <p><b>Why the full 6-ring isn't exhaustively enumerated.</b> The real adjacency graph is
 * dense (~78% of pairs legal), so raw 6-cycle ring colorings number in the hundreds of
 * millions - checking each against the real geometric solver isn't a today-sized job. The
 * real risk isn't spread evenly across that space: a ring with 15 legal common candidates
 * will essentially always find one that fits geometrically too. Risk concentrates exactly
 * where {@link BiomeGraph#findMinimalUnsolvablePartialNeighborhoods} already looks - low
 * legal-candidate-count neighborhoods - so this class enumerates every real, physically-
 * placeable partial neighborhood (subset size 2..{@link #MAX_SUBSET_SIZE}, same {@link
 * BiomeGraph#isPlaceableAmongNeighbors} test) whose abstract candidate count is nonzero
 * but at or below {@link #MAX_CANDIDATE_COUNT_TO_CHECK} - the genuinely at-risk set, not
 * the whole combinatorial space.
 */
public final class GeometricFeasibilityProof {

    // Matches TriangleFieldGeometryRealization.HEX_SIZE_BLOCKS - kept in sync manually,
    // same established convention as DeadEndFrequencySimulation's own copy.
    private static final double HEX_SIZE_BLOCKS = 325;
    private static final MacroGridConfig GRID_CONFIG = MacroGridConfig.DEFAULT;
    private static final int MIN_EDGE_LENGTH_MACRO_CELLS = 6;
    private static final double MIN_EDGE_LENGTH_BLOCKS = MIN_EDGE_LENGTH_MACRO_CELLS * GRID_CONFIG.macroCellSizeBlocks();

    // Fixed, arbitrary, deterministic - worldSeed only affects the CSP's own tie-break
    // ordering (see DECISIONS.md 2026-08-23 "Candidate biomes tried in deterministic
    // jittered order"), never completeness, so a proof doesn't need to sweep it.
    private static final long PROOF_WORLD_SEED = 20260913L;

    // Real crash data this session (DeadEndFrequencySimulation's own captured failures)
    // showed attempt2FixedFaceCount ranging 3-9, i.e. a partial, not-always-full ring -
    // covers that real shape without the full 6-cycle's intractable combinatorics (see
    // this class's own doc).
    private static final int MAX_SUBSET_SIZE = 5;

    // Subsets with 0 legal candidates are already proven dead by
    // BiomeGraphCompletenessReport - excluded here as redundant. Subsets above this
    // candidate count are treated as low real risk (geometry needs to rule out EVERY
    // candidate to matter, increasingly unlikely as the candidate pool grows) and skipped
    // for tractability - a real, named scope limit, not a silent one.
    private static final int MAX_CANDIDATE_COUNT_TO_CHECK = 6;

    // Real pointy-top axial neighbor deltas, matches HexGrid's own convention - see
    // DeadEndFrequencySimulation's identical copy.
    private static final int[][] AXIAL_NEIGHBOR_DELTAS = {
        {1, 0}, {1, -1}, {0, -1}, {-1, 0}, {-1, 1}, {0, 1}
    };

    // Generous margin so the center and its ring are comfortably non-hull-touching - same
    // constant production's own realizeCluster mesh uses.
    private static final int MESH_PAD_BLOCKS = TriangleFieldGeometryRealization.TRIANGULATION_MARGIN_BLOCKS;

    private static final int THREAD_COUNT = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);

    private static final Path REPORT_PATH = Path.of("doc", "geometric-feasibility-proof-report.txt");

    private GeometricFeasibilityProof() {
    }

    /** One partial-neighborhood subset, checked at one width extreme, that the real production solver could not complete - a genuine, reproducible geometric dead end, not a sample. */
    record GeometricFailure(List<BiomeId> subset, String widthExtreme, List<BiomeId> abstractCandidates) {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        runProof(ModBiomeGraph.OVERWORLD, REPORT_PATH, "REAL graph (today's actual ModBiomeGraph.OVERWORLD)");
    }

    /** Public (not package-private) so sibling one-off experiments (see {@code UniversalSelfAdjacencyGeometricExperiment}) AND the graph editor's own "Deep check" ({@code com.mineutze.worldgen.tooling.graphedit.GraphEditorDeepCheck}) can run this exact same real solver check against a hypothetical graph, without duplicating any of the geometry-solving logic below. */
    public static void runProof(BiomeGraph graph, Path reportPath, String label) throws IOException, InterruptedException {
        List<BiomeId> allBiomes = graph.nodes().stream().map(com.mineutze.worldgen.biomegraph.BiomeNode::id).sorted().toList();

        System.out.println(label + ": enumerating real, physically-placeable partial neighborhoods (subset size 2.." + MAX_SUBSET_SIZE
            + ") with 1.." + MAX_CANDIDATE_COUNT_TO_CHECK + " abstract legal candidates...");
        List<AtRiskSubset> atRisk = findAtRiskSubsets(graph, allBiomes);
        System.out.println(label + ": found " + atRisk.size() + " at-risk subset(s) - checking each against the real geometric solver "
            + "at both width extremes (" + (atRisk.size() * 2) + " real solves total)...");

        List<GeometricFailure> failures = checkAllGeometrically(graph, atRisk);

        writeReport(reportPath, atRisk.size(), failures);
        System.out.println(label + ": done. " + failures.size() + " genuine geometric dead end(s) found among " + atRisk.size()
            + " at-risk subsets. Report: " + reportPath.toAbsolutePath());
    }

    /** One real, physically-placeable partial neighborhood with few enough abstract candidates to be worth a real geometric check. */
    record AtRiskSubset(List<BiomeId> subset, List<BiomeId> candidates) {
    }

    static List<AtRiskSubset> findAtRiskSubsets(BiomeGraph graph, List<BiomeId> allBiomes) {
        List<AtRiskSubset> atRisk = new ArrayList<>();
        for (int subsetSize = 2; subsetSize <= MAX_SUBSET_SIZE; subsetSize++) {
            forEachCombination(allBiomes, subsetSize, subset -> {
                if (!graph.isPlaceableAmongNeighbors(subset, 6)) {
                    return; // couldn't physically occur as a real face's neighbors at all
                }
                List<BiomeId> candidates = allBiomes.stream()
                    .filter(candidate -> subset.stream().allMatch(member -> graph.isAllowed(candidate, member)))
                    .toList();
                if (candidates.isEmpty() || candidates.size() > MAX_CANDIDATE_COUNT_TO_CHECK) {
                    return; // 0 candidates: already proven dead by BiomeGraphCompletenessReport. Too many: low real risk, out of scope.
                }
                atRisk.add(new AtRiskSubset(subset, candidates));
            });
        }
        return atRisk;
    }

    private static void forEachCombination(List<BiomeId> ids, int size, java.util.function.Consumer<List<BiomeId>> action) {
        forEachCombinationFrom(ids, size, 0, new ArrayList<>(), action);
    }

    private static void forEachCombinationFrom(List<BiomeId> ids, int size, int startIndex, List<BiomeId> current, java.util.function.Consumer<List<BiomeId>> action) {
        if (current.size() == size) {
            action.accept(List.copyOf(current));
            return;
        }
        for (int index = startIndex; index < ids.size(); index++) {
            current.add(ids.get(index));
            forEachCombinationFrom(ids, size, index + 1, current, action);
            current.remove(current.size() - 1);
        }
    }

    static List<GeometricFailure> checkAllGeometrically(BiomeGraph graph, List<AtRiskSubset> atRisk) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        ExecutorCompletionService<List<GeometricFailure>> completionService = new ExecutorCompletionService<>(executor);
        AtomicInteger completed = new AtomicInteger();
        try {
            for (AtRiskSubset candidate : atRisk) {
                completionService.submit(() -> {
                    List<GeometricFailure> result = checkOneSubsetGeometrically(graph, candidate);
                    int done = completed.incrementAndGet();
                    if (done % 200 == 0) {
                        System.out.println("  " + done + "/" + atRisk.size());
                    }
                    return result;
                });
            }
            List<GeometricFailure> failures = new ArrayList<>();
            for (int i = 0; i < atRisk.size(); i++) {
                try {
                    failures.addAll(completionService.take().get());
                } catch (Exception e) {
                    throw new IllegalStateException("GeometricFeasibilityProof: a check task itself failed unexpectedly", e);
                }
            }
            return failures;
        } finally {
            executor.shutdown();
            executor.awaitTermination(1, TimeUnit.HOURS);
        }
    }

    /** Real ring ordering minimizing mutually-illegal consecutive pairs, mirroring {@code BiomeGraph}'s own private placement logic (same real-topology reasoning, small subset sizes so brute permutation is cheap here too). */
    private static List<BiomeId> realizableOrderOf(BiomeGraph graph, List<BiomeId> subset) {
        List<List<BiomeId>> permutations = new ArrayList<>();
        permute(new ArrayList<>(subset), new ArrayList<>(), permutations);
        List<BiomeId> best = null;
        int bestIllegalCount = Integer.MAX_VALUE;
        for (List<BiomeId> candidateOrder : permutations) {
            int illegalCount = 0;
            for (int i = 0; i < candidateOrder.size() - 1; i++) {
                if (!graph.isAllowed(candidateOrder.get(i), candidateOrder.get(i + 1))) {
                    illegalCount++;
                }
            }
            if (illegalCount < bestIllegalCount) {
                bestIllegalCount = illegalCount;
                best = candidateOrder;
            }
        }
        return best;
    }

    private static void permute(List<BiomeId> remaining, List<BiomeId> current, List<List<BiomeId>> out) {
        if (remaining.isEmpty()) {
            out.add(List.copyOf(current));
            return;
        }
        for (int i = 0; i < remaining.size(); i++) {
            BiomeId next = remaining.get(i);
            List<BiomeId> nextRemaining = new ArrayList<>(remaining);
            nextRemaining.remove(i);
            current.add(next);
            permute(nextRemaining, current, out);
            current.remove(current.size() - 1);
        }
    }

    private static List<GeometricFailure> checkOneSubsetGeometrically(BiomeGraph graph, AtRiskSubset atRisk) {
        List<BiomeId> ring = realizableOrderOf(graph, atRisk.subset());
        List<GeometricFailure> failures = new ArrayList<>();
        for (WidthExtreme extreme : WidthExtreme.values()) {
            if (!hasGeometricSolution(graph, ring, extreme)) {
                failures.add(new GeometricFailure(atRisk.subset(), extreme.name(), atRisk.candidates()));
            }
        }
        return failures;
    }

    private enum WidthExtreme {
        ALL_MINIMUM, ALL_MAXIMUM
    }

    /** Builds a real synthetic mesh, places {@code ring} as consecutive permanent neighbors of a free center face at {@code extreme}'s width, then runs the real production identity+size solver on the center. */
    private static boolean hasGeometricSolution(BiomeGraph graph, List<BiomeId> ring, WidthExtreme extreme) {
        double pad = MESH_PAD_BLOCKS;
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(HEX_SIZE_BLOCKS, (int) -pad, (int) -pad, (int) pad, (int) pad);
        FaceAdjacency.Adjacency adjacency = FaceAdjacency.of(mesh.faces());

        HexGrid.AxialPos centerAxial = new HexGrid.AxialPos(0, 0);
        int centerIndex = findFaceIndex(mesh, centerAxial);

        Map<Integer, TriangleIdentitySizeAssignment.Assignment> ringAssignments = new java.util.LinkedHashMap<>();
        for (int i = 0; i < ring.size(); i++) {
            HexGrid.AxialPos ringAxial = new HexGrid.AxialPos(AXIAL_NEIGHBOR_DELTAS[i][0], AXIAL_NEIGHBOR_DELTAS[i][1]);
            int ringIndex = findFaceIndex(mesh, ringAxial);
            BiomeSizeRange range = graph.node(ring.get(i)).sizeRange();
            int width = extreme == WidthExtreme.ALL_MINIMUM ? range.minWidthBlocks() : range.maxWidthBlocks();
            ringAssignments.put(ringIndex, new TriangleIdentitySizeAssignment.Assignment(ring.get(i), width));
        }

        Map<Integer, Point2D> ringVertices = WeightedVertexPlacement.sequentiallyRealizedVertexPositions(
            mesh, adjacency, ringAssignments, HEX_SIZE_BLOCKS, MIN_EDGE_LENGTH_BLOCKS, Map.of(), Map.of(), PROOF_WORLD_SEED
        );

        Map<Integer, BiomeId> fixedFaceBiomes = new java.util.LinkedHashMap<>();
        Map<Integer, Integer> committedWidthBlocksByFace = new java.util.LinkedHashMap<>();
        ringAssignments.forEach((faceIndex, assignment) -> {
            fixedFaceBiomes.put(faceIndex, assignment.biome());
            committedWidthBlocksByFace.put(faceIndex, assignment.widthBlocks());
        });

        Optional<Map<Integer, TriangleIdentitySizeAssignment.Assignment>> solved = TriangleIdentitySizeAssignment.solve(
            adjacency, Set.of(centerIndex), fixedFaceBiomes, graph, PROOF_WORLD_SEED, 1e15, committedWidthBlocksByFace,
            mesh, HEX_SIZE_BLOCKS, MIN_EDGE_LENGTH_BLOCKS, ringVertices
        );
        return solved.isPresent();
    }

    private static int findFaceIndex(HexMeshBuilder.HexMesh mesh, HexGrid.AxialPos axialPos) {
        for (int faceIndex = 0; faceIndex < mesh.faces().size(); faceIndex++) {
            if (mesh.faces().get(faceIndex).axialPos().equals(axialPos)) {
                return faceIndex;
            }
        }
        throw new IllegalStateException("GeometricFeasibilityProof: mesh pad too small to contain axial position " + axialPos + " - real bug");
    }

    private static void writeReport(Path reportPath, int atRiskCount, List<GeometricFailure> failures) throws IOException {
        StringBuilder report = new StringBuilder();
        report.append("GeometricFeasibilityProof - exhaustive geometric check of abstractly-legal-but-scarce partial neighborhoods\n");
        report.append("Subset sizes checked: 2..").append(MAX_SUBSET_SIZE).append(", candidate counts 1..").append(MAX_CANDIDATE_COUNT_TO_CHECK).append("\n");
        report.append("At-risk subsets checked: ").append(atRiskCount).append(" (x2 width extremes = ").append(atRiskCount * 2).append(" real solves)\n");
        report.append("Genuine geometric dead ends found: ").append(failures.size()).append("\n\n");
        if (failures.isEmpty()) {
            report.append("PROVEN: every abstractly-legal, real, physically-placeable partial neighborhood up to size ")
                .append(MAX_SUBSET_SIZE).append(" with <= ").append(MAX_CANDIDATE_COUNT_TO_CHECK)
                .append(" abstract candidates has at least one candidate that also fits geometrically, at both real width extremes.\n");
        } else {
            for (GeometricFailure failure : failures) {
                report.append("DEAD END: neighbors=").append(failure.subset()).append(", widthExtreme=").append(failure.widthExtreme())
                    .append(", abstractCandidates=").append(failure.abstractCandidates())
                    .append(" - every abstract candidate is illegal-sized against this real neighbor configuration.\n");
            }
        }
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, report.toString());
    }
}
