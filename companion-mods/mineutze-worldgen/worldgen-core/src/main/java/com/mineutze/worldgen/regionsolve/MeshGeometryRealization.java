package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeId;
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

import java.util.List;
import java.util.Map;

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
 * {@link MeshRasterizer} (back to {@code Map<MacroCellPos,BiomeId>}, the
 * only thing anything outside this geometry stage ever sees).
 */
public final class MeshGeometryRealization {

    private MeshGeometryRealization() {
    }

    public static Map<MacroCellPos, BiomeId> realize(SeedGraph seedGraph, Map<Integer, BiomeId> assignedIdentities, RegionSolveInput input) {
        List<ScatteredPoint> scatter = FineMeshPointScatter.scatter(input, seedGraph, assignedIdentities);
        FineMesh mesh = FineMesh.build(scatter);

        MeshAssignment assignment = PatchMerger.merge(mesh, input, seedGraph, assignedIdentities);
        MeshGapFiller.fillGaps(mesh, assignment, input.graph(), input.gridConfig(), input.worldSeed());
        Map<Integer, Point2D> displaced = EdgeDisplacement.displace(mesh, assignment, input.graph(), input.gridConfig(), input.worldSeed());

        return MeshRasterizer.rasterize(mesh, assignment, displaced, input);
    }
}
