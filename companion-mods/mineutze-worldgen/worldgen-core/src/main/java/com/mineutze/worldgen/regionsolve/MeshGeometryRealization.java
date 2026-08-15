package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.regionsolve.mesh.EdgeDisplacement;
import com.mineutze.worldgen.regionsolve.mesh.FineMesh;
import com.mineutze.worldgen.regionsolve.mesh.FineMeshPointScatter;
import com.mineutze.worldgen.regionsolve.mesh.MeshAssignment;
import com.mineutze.worldgen.regionsolve.mesh.MeshGapFiller;
import com.mineutze.worldgen.regionsolve.mesh.MeshPatchSizeEnforcer;
import com.mineutze.worldgen.regionsolve.mesh.MeshRasterizer;
import com.mineutze.worldgen.regionsolve.mesh.PatchMerger;
import com.mineutze.worldgen.regionsolve.mesh.ScatteredPoint;
import com.mineutze.worldgen.regionsolve.topology.Point2D;
import com.mineutze.worldgen.regionsolve.topology.SeedGraph;
import com.mineutze.worldgen.regionsolve.topology.SeedGraphNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds region geometry as a real triangulated mesh, merges triangle faces
 * into patches, and displaces shared boundaries with noise, so a required
 * separator between two mutually-incompatible biomes doesn't read as a
 * ruler-straight corridor — tracing the full shared border between two
 * biomes is inherently elongated (geometry, not something displacement can
 * fully undo), but the noise stops it reading as a "cheat."
 *
 * <p>Live path: {@link SeedRegionCache}'s default {@code geometryRealizer}
 * calls this class directly.
 *
 * <p>Six stages, each its own class under {@code regionsolve.mesh}:
 * {@link FineMeshPointScatter} (fine point scatter: fixed/CSP-colored germ
 * points, a boundary ring, uncolored filler) → {@link FineMesh} (Delaunay
 * triangulation + adjacency structures) → {@link PatchMerger} (area-based
 * priority growth, reusing {@link GrowthEngine}'s legality/cohesion checks) →
 * {@link MeshGapFiller} (3-tier dead-end resolver, plus a target-aware fill
 * priority — see its own doc) → {@link MeshPatchSizeEnforcer} (absorbs any
 * component that's still under its biome's own declared minimum size) →
 * {@link EdgeDisplacement} (the actual visual payoff) →
 * {@link MeshRasterizer} (back to {@code Map<MacroCellPos,BiomeId>}) →
 * {@link PixelFragmentRepair#repair} (called with an empty fixed-context —
 * see its own doc for why that's still safe here, not the same as the
 * cross-region-aware use that was tried and reverted on the live path) →
 * {@link #enforceFinalPixelPatchSize} (mesh-level size enforcement doesn't
 * survive rasterization/repair intact — see its own doc).
 *
 * <p><b>{@code MeshLegality}/{@code MeshCohesion} guarantee legality and
 * shape at the MESH level — neither says anything about what a face's
 * winning macro-cells look like once discretized.</b> Two distinct gaps
 * confirmed via direct trace on the seed-42 comparison, both closed by
 * {@link PixelFragmentRepair#repair}:
 * <ul>
 *   <li>Two faces neither edge-adjacent nor diagonal partners anywhere in
 *   the mesh (so {@code MeshLegality} never checked them against each
 *   other) rasterized to two orthogonally-touching macro-cells — an
 *   illegal adjacency invisible at the mesh level.
 *   <li>A face that was legitimately part of a large, properly-connected
 *   26-face mesh patch (~91 macro-cells' worth) still rasterized to one
 *   macro-cell spatially stranded from every other cell of its own
 *   patch — nearest-centroid point-sampling doesn't guarantee
 *   mesh-adjacency implies PIXEL-adjacency in the output, especially
 *   near a patch's own turns/corners. Originally handled here with a
 *   homegrown repair scoped to exactly-size-1 components; switched to
 *   {@link PixelFragmentRepair#repair} instead once a same-shape 2-4 cell
 *   speck was found slipping through that narrower check — same
 *   discretization artifact, just landing on more than one cell at once.
 * </ul>
 */
public final class MeshGeometryRealization {

    private MeshGeometryRealization() {
    }

    public static Map<MacroCellPos, BiomeId> realize(SeedGraph seedGraph, Map<Integer, BiomeId> assignedIdentities, RegionSolveInput input) {
        List<ScatteredPoint> scatter = FineMeshPointScatter.scatter(input, seedGraph, assignedIdentities);
        FineMesh mesh = FineMesh.build(scatter);

        MeshAssignment assignment = PatchMerger.merge(mesh, input, seedGraph, assignedIdentities);
        Map<BiomeId, Double> targetAreaByBiome = aggregateTargetAreaByBiome(seedGraph, assignedIdentities, input);
        MeshGapFiller.fillGaps(mesh, assignment, input.graph(), input.gridConfig(), input.worldSeed(), targetAreaByBiome);
        MeshPatchSizeEnforcer.enforceMinimumPatchSize(mesh, assignment, input.graph(), input.gridConfig());
        Map<Integer, Point2D> displaced = EdgeDisplacement.displace(mesh, assignment, input.graph(), input.gridConfig(), input.worldSeed());
        Map<MacroCellPos, BiomeId> rasterized = MeshRasterizer.rasterize(mesh, assignment, displaced, input);
        Map<MacroCellPos, BiomeId> repaired = PixelFragmentRepair.repair(rasterized, Map.of(), input.graph());

        return enforceFinalPixelPatchSize(repaired, input);
    }

    /**
     * {@link MeshPatchSizeEnforcer} only sees MESH-face area, before {@link
     * EdgeDisplacement}/{@link MeshRasterizer}/{@link
     * #repairRasterizationArtifacts} run — none of those guarantee the final
     * PIXEL component stays at or above that same size, and the last of them
     * can actively shrink one (reassigning a cell to fix an illegal
     * adjacency or isolated-cell violation pulls it out of whatever
     * component it belonged to). Confirmed via direct user report: seed 42's
     * plains patch at macro cell (10,-14) rasterized to an 18-cell, 3-cell-
     * wide (192 blocks) component against a declared 300-block minimum
     * width (~22-cell minimum) — legal-adjacency-wise fine, but genuinely
     * undersized by the same standard {@link MeshPatchSizeEnforcer} already
     * enforces at the mesh level. Reuses the pixel-level {@link
     * PatchSizeEnforcer} rather than re-deriving the same merge-or-widen
     * logic a second time; assembles the same fixed-boundary context as
     * every other pixel-level pass here, since a component's true bordering
     * neighbors can include already-solved territory outside this region.
     */
    private static Map<MacroCellPos, BiomeId> enforceFinalPixelPatchSize(Map<MacroCellPos, BiomeId> repaired, RegionSolveInput input) {
        Set<MacroCellPos> regionCells = Set.copyOf(input.gridConfig().cellsInRegion(input.region(), input.worldSeed()));
        Map<MacroCellPos, BiomeId> withBoundaryContext = new LinkedHashMap<>(input.fixedBoundaryCells());
        withBoundaryContext.putAll(repaired);

        PatchSizeEnforcer.enforceMinimumPatchSize(withBoundaryContext, regionCells, input.gridConfig(), input.graph());

        return withBoundaryContext.entrySet().stream()
            .filter(entry -> regionCells.contains(entry.getKey()))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Each free germ's own target area (same formula {@link PatchMerger}'s
     * own growth phase uses), summed per biome across every germ that biome
     * has — the real per-biome demand signal {@link MeshGapFiller}'s fill
     * priority needs (see that class's own doc for why a flat distance-only
     * priority systematically starved restrictively-legal, sparse-germ
     * biomes on the seed-42 comparison). A biome absent here (e.g. only
     * present via fixed boundary context, never a free germ this solve)
     * simply has no further demand — correct, not an oversight.
     */
    private static Map<BiomeId, Double> aggregateTargetAreaByBiome(SeedGraph seedGraph, Map<Integer, BiomeId> assignedIdentities, RegionSolveInput input) {
        Map<BiomeId, Double> targets = new LinkedHashMap<>();
        for (SeedGraphNode node : seedGraph.nodes()) {
            if (node.fixed()) {
                continue;
            }
            BiomeId biome = assignedIdentities.get(node.id());
            double sideInCells = (double) input.graph().node(biome).sizeRange().maxWidthBlocks() / input.gridConfig().macroCellSizeBlocks();
            targets.merge(biome, sideInCells * sideInCells, Double::sum);
        }
        return targets;
    }
}
