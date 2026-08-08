package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;
import com.mineutze.worldgen.regionsolve.mesh.EdgeDisplacement;
import com.mineutze.worldgen.regionsolve.mesh.FineMesh;
import com.mineutze.worldgen.regionsolve.mesh.FineMeshPointScatter;
import com.mineutze.worldgen.regionsolve.mesh.MeshAssignment;
import com.mineutze.worldgen.regionsolve.mesh.MeshGapFiller;
import com.mineutze.worldgen.regionsolve.mesh.MeshRasterizer;
import com.mineutze.worldgen.regionsolve.mesh.PatchMerger;
import com.mineutze.worldgen.regionsolve.mesh.ScatteredPoint;
import com.mineutze.worldgen.regionsolve.topology.Point2D;
import com.mineutze.worldgen.regionsolve.topology.SeedGraph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The mesh-based replacement for {@link GeometryRealization}, same public
 * contract ({@code realize(SeedGraph, Map<Integer,BiomeId>, RegionSolveInput)
 * : Map<MacroCellPos,BiomeId>}) so {@link TopologyFirstRegionSolver}'s call
 * site is a one-line swap once this pipeline is reviewed and accepted — see
 * doc/fix_dieas.md and this rewrite's own design plan for why: a required
 * separator between two mutually-incompatible biomes reads as a
 * straight-edged corridor even after every pixel-grid cleanup pass
 * {@link GeometryRealization} runs, because tracing the full shared border
 * between two biomes is inherently elongated — geometry, not a bug any of
 * those passes could fix. Building a real triangulated mesh, merging
 * triangle faces into patches, and displacing the shared boundary with
 * noise doesn't make that border circular (still geometrically impossible
 * for something that must trace a long shared contact), but it stops it
 * reading as a ruler-straight "cheat."
 *
 * <p>{@link GeometryRealization} itself is untouched and stays in the
 * codebase as the rollback path, exactly like {@link RegionSolver} was kept
 * after the topology-first redesign — {@code TopologyFirstRegionSolver}
 * still calls {@link GeometryRealization#realize} until that swap is made.
 *
 * <p>Five stages, each its own class under {@code regionsolve.mesh}:
 * {@link FineMeshPointScatter} (fine point scatter: fixed/CSP-colored germ
 * points, a boundary ring, uncolored filler) → {@link FineMesh} (Delaunay
 * triangulation + adjacency structures) → {@link PatchMerger} (area-based
 * priority growth, reusing the same legality/cohesion discipline
 * {@code GeometryRealization} itself uses) → {@link MeshGapFiller} (the
 * same 3-tier dead-end resolver {@link GapFiller} already proved) →
 * {@link EdgeDisplacement} (the actual visual payoff) →
 * {@link MeshRasterizer} (back to {@code Map<MacroCellPos,BiomeId>}) →
 * {@link #repairPixelLevelIllegalAdjacencies} (see its own doc — a real gap
 * found via the seed-42 comparison this rewrite's plan calls for, not
 * something anticipated up front).
 *
 * <p><b>{@code MeshLegality} guarantees legality between faces that are
 * actually adjacent IN THE MESH — it says nothing about two faces that
 * become pixel-adjacent only after rasterization despite never sharing an
 * edge or vertex.</b> Confirmed via direct trace on the seed-42 comparison:
 * a savanna face and a desert face, neither edge-adjacent nor diagonal
 * partners anywhere in the mesh, rasterized to two orthogonally-touching
 * macro-cells — {@code MeshLegality} never had a reason to check that pair
 * at all. {@link #repairPixelLevelIllegalAdjacencies} is the safety net for
 * exactly that discretization gap, applied once, after rasterization —
 * see its own doc for why a bounded local repair here is safe and why it
 * doesn't reopen the shape-quality problem this whole rewrite exists to
 * fix.
 */
public final class MeshGeometryRealization {

    // Sanity cap on repair passes, same role as PatchOpener/PinchResolver's own
    // fixed-point loop caps in the pixel pipeline - bounds the search, not a
    // correctness mechanism. A cell left illegal after this many passes is logged
    // and accepted, same "small residual, log it" precedent GapFiller's own force
    // tier already established (0-58/9216 cells historically, never silently hidden).
    private static final int MAX_PIXEL_REPAIR_PASSES = 20;

    private MeshGeometryRealization() {
    }

    public static Map<MacroCellPos, BiomeId> realize(SeedGraph seedGraph, Map<Integer, BiomeId> assignedIdentities, RegionSolveInput input) {
        List<ScatteredPoint> scatter = FineMeshPointScatter.scatter(input, seedGraph, assignedIdentities);
        FineMesh mesh = FineMesh.build(scatter);

        MeshAssignment assignment = PatchMerger.merge(mesh, input, seedGraph, assignedIdentities);
        MeshGapFiller.fillGaps(mesh, assignment, input.graph(), input.gridConfig(), input.worldSeed());
        Map<Integer, Point2D> displaced = EdgeDisplacement.displace(mesh, assignment, input.graph(), input.gridConfig(), input.worldSeed());
        Map<MacroCellPos, BiomeId> rasterized = MeshRasterizer.rasterize(mesh, assignment, displaced, input);

        return repairPixelLevelIllegalAdjacencies(rasterized, input.graph());
    }

    /**
     * A cell whose rasterized biome is illegal against one of its own
     * orthogonal pixel-neighbors gets reassigned to whichever biome IS
     * legal against every current neighbor and has the most matching
     * neighbors among those — same tie-break {@code GapFiller.bestLegalChoice}
     * already uses. Iterates to a fixed point (a repair can itself become
     * illegal against a neighbor that hasn't been visited yet this pass),
     * capped at {@link #MAX_PIXEL_REPAIR_PASSES}. Reuses {@link GrowthEngine#isLegal}
     * directly rather than re-deriving the same check — this class lives in
     * the same package on purpose.
     *
     * <p>Scoped narrowly on purpose: only ever reassigns a cell that's
     * already illegal, using the graph's own real bridge paths (every
     * non-adjacent pair is guaranteed bridgeable within {@code BiomeGraph}'s
     * own hop budget, checked eagerly at construction) — never touches a
     * cell that's already legal, so it can't introduce a NEW violation
     * while fixing another, and it can't degrade the shape-quality work
     * {@code EdgeDisplacement} just did (a 14-cell, ~0.15%-of-region repair
     * is far too small to visibly affect a patch's read as organic).
     */
    private static Map<MacroCellPos, BiomeId> repairPixelLevelIllegalAdjacencies(Map<MacroCellPos, BiomeId> rasterized, BiomeGraph graph) {
        Map<MacroCellPos, BiomeId> repaired = new LinkedHashMap<>(rasterized);
        for (int pass = 0; pass < MAX_PIXEL_REPAIR_PASSES; pass++) {
            List<MacroCellPos> violating = findIllegalCells(repaired, graph);
            if (violating.isEmpty()) {
                break;
            }
            for (MacroCellPos cell : violating) {
                bestLegalReplacement(cell, repaired, graph).ifPresent(replacement -> {
                    repaired.put(cell, replacement);
                    WorldgenTrace.log("MESH PIXELREPAIR reassign " + cell + " = " + replacement);
                });
            }
        }
        return repaired;
    }

    private static List<MacroCellPos> findIllegalCells(Map<MacroCellPos, BiomeId> assignment, BiomeGraph graph) {
        List<MacroCellPos> violating = new ArrayList<>();
        for (Map.Entry<MacroCellPos, BiomeId> entry : assignment.entrySet()) {
            if (!isLegalIncludingDiagonals(entry.getKey(), entry.getValue(), assignment, graph)) {
                violating.add(entry.getKey());
            }
        }
        return violating;
    }

    private static Optional<BiomeId> bestLegalReplacement(MacroCellPos cell, Map<MacroCellPos, BiomeId> assignment, BiomeGraph graph) {
        List<BiomeId> legalCandidates = graph.nodes().stream()
            .map(BiomeNode::id)
            .filter(candidate -> isLegalIncludingDiagonals(cell, candidate, assignment, graph))
            .toList();
        if (legalCandidates.isEmpty()) {
            return Optional.empty();
        }
        Map<BiomeId, Long> neighborCounts = cell.orthogonalNeighbors().stream()
            .map(assignment::get)
            .filter(Objects::nonNull)
            .collect(Collectors.groupingBy(biome -> biome, Collectors.counting()));
        return legalCandidates.stream()
            .max(Comparator.<BiomeId>comparingLong(id -> neighborCounts.getOrDefault(id, 0L)).thenComparing(Comparator.naturalOrder()));
    }

    /** {@link GrowthEngine#isLegal} only checks the 4 orthogonal neighbors (same as the rest of this pipeline) — this repair pass also checks diagonals, since a discretization-born violation has no reason to prefer one over the other. */
    private static boolean isLegalIncludingDiagonals(MacroCellPos cell, BiomeId candidate, Map<MacroCellPos, BiomeId> assignment, BiomeGraph graph) {
        if (!GrowthEngine.isLegal(cell, candidate, assignment, graph)) {
            return false;
        }
        for (MacroCellPos diagonal : cell.diagonalNeighbors()) {
            BiomeId diagonalBiome = assignment.get(diagonal);
            if (diagonalBiome != null && !graph.isAllowed(candidate, diagonalBiome)) {
                return false;
            }
        }
        return true;
    }
}
