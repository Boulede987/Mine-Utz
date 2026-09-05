package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.regionsolve.topology.HexFace;
import com.mineutze.worldgen.regionsolve.topology.HexGrid;
import com.mineutze.worldgen.regionsolve.topology.HexMeshBuilder;
import com.mineutze.worldgen.regionsolve.topology.Point2D;
import com.mineutze.worldgen.regionsolve.topology.TriangleAdjacency;
import com.mineutze.worldgen.regionsolve.topology.TriangleIdentitySizeAssignment;
import com.mineutze.worldgen.regionsolve.topology.VertexKey;
import com.mineutze.worldgen.regionsolve.topology.WeightedVertexPlacement;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The live region-solve path: {@link HexMeshBuilder} (regular hex lattice, exactly 3
 * faces meeting at every vertex — see doc/decisions/2026-08-29-hex-mesh-edge-
 * displacement.md) → {@link TriangleAdjacency} (real ≤6-neighbor mesh topology) → {@link
 * TriangleIdentitySizeAssignment} (fused identity+size CSP, legality fully decided
 * here) → {@link WeightedVertexPlacement} (power-diagram vertex placement, size baked
 * directly into WHERE each corner sits) → {@link EdgeDisplacementRasterizer}
 * (independent per-face polygon rasterization, no competition, no live legality
 * re-check) → {@code Map<MacroCellPos,BiomeId>}.
 *
 * <p>Supersedes the earlier Delaunay-triangle-mesh + competitive-growth path (deleted
 * alongside {@link DelaunayTriangulation}, {@code SeamGrowthRasterizer}, {@code
 * GrowthGapFiller}): a triangle's vertex degree averages ~6, so two triangles can share
 * only a vertex (not an edge) and still end up pixel-adjacent, a case the CSP's edge-only
 * legality graph never checked — that gap was what forced the old rasterizer to re-check
 * legality live, per cell, during growth. A hex mesh's vertex-degree-3 property makes
 * edge-adjacency and vertex-adjacency the same relation, always, so CSP-time legality
 * checking is provably complete and the rasterizer needs no live check at all.
 *
 * <p><b>The mesh's raw point positions matter only up to identity+size assignment.</b>
 * After that, a face's actual SHAPE comes from its own corners' weighted positions
 * ({@link WeightedVertexPlacement}) plus organic wiggle ({@link
 * com.mineutze.worldgen.regionsolve.topology.EdgeDisplacement}), never from growth
 * competing for territory. {@link RegionSolveInput#fixedBoundaryCells} is still
 * consulted for IDENTITY (a face whose centroid falls in an already-solved neighbor is
 * fixed to that neighbor's own decision, via {@link TriangleIdentitySizeAssignment}'s
 * existing {@code fixedFaceBiomes} mechanism — see {@link #buildFixedFaceBiomes}); a
 * cross-region vertex falls back to a baseline (unweighted) position in {@link
 * WeightedVertexPlacement} (only real, in-region assignments carry a real weight — see
 * its own doc), so a region boundary displaces with wiggle only, not size-driven
 * precision — matches the same "legality guaranteed, seam smoothness a separate
 * concern" split the old path already had.
 */
public final class TriangleFieldGeometryRealization {

    // 650-block undisplaced width (circumradius 325), not the naive 800 (circumradius
    // 400) doc/DESIGN.md's raw 300-1000 range midpoint would suggest - chosen to minimize
    // the REAL 48-biome catalog's total starting size deficit instead: ModBiomeGraph.
    // OVERWORLD's own size ranges aren't evenly spread across that 300-1000 range (14
    // biomes in a 300-600 tier, 24 in 500-800, only 10 in 700-1000), and 800 sits at
    // tier-2's own ceiling but far above the ENTIRE tier-1 range (max 600). 650 was picked
    // by minimizing total |targetArea(biome's own midpoint width) - targetArea(650)|
    // summed across all 48 real biomes, weighted by how many biomes actually fall in each
    // tier - see WeightedVertexPlacement's own doc for the area/weight formula this
    // reuses. Still not a full fix, even under the current power-diagram size mechanism
    // (this constant was originally justified against the OLDER, deleted per-edge
    // additive-bias mechanism, whose "same-tier neighbors trade zero area" degeneracy no
    // longer exists as a hard rule - but a real, measured, softer version of the same
    // problem remains: a face wanting to be much smaller than its actual surrounding
    // weights still lands systematically oversized, since weight-equals-target-area is
    // only a one-shot proxy for realized area, not an exact one - see
    // WeightedVertexPlacement's own doc). This constant reduces every face's OWN starting
    // gap regardless of which size mechanism sits downstream of it.
    //
    // This EXACT value was tried once already and reverted: it originally exposed two real
    // dead ends in TriangleIdentitySizeAssignment's own backtracking search that plain
    // forward checking (and later, binary AC-3 alone) couldn't see - worldSeed=2034's
    // 13s-at-400-but-non-terminating-at-325 case, then worldSeed=2060's genuine
    // zero-valid-triple mesh-vertex dead end. Both are fixed now (real AC-3 + mesh-vertex
    // triple consistency + a hard node-budget safety net - see that class's own doc for
    // the full story). Swept all 50 real starvation-test seeds at this exact value
    // post-fix: 100% solve clean. Re-applying it is not just re-trying the same broken
    // thing again - the actual blocker was fixed at its own root, in the CSP, not here.
    private static final double HEX_SIZE_BLOCKS = 325;
    // Generous margin (matches the old triangle mesh's own empirically-trusted value) -
    // guarantees every face touching the actual region is non-hull-touching (real
    // neighbor context on every side for the CSP's own legality solving).
    //
    // Package-private, not private: RegionBoundaryCollector.cellsBorderingRegion reuses
    // this SAME constant as its own collection depth - it's the exact bound past which no
    // face centroid in a region's own mesh can ever exist, so the two need a single shared
    // source of truth, not two independently-tuned numbers that could quietly drift apart.
    static final int TRIANGULATION_MARGIN_BLOCKS = 1500;

    // A face's own edge is a real physical boundary only if it's wide enough for the
    // macro-cell pixel grid to actually sample it - a face squeezed thinner than this
    // contains zero cell centers along that edge, so the rasterizer's point-in-polygon
    // test silently skips it there and whichever two faces sit on either side end up
    // pixel-adjacent with nothing sampled between them. Found as a REAL, live defect
    // (not a hypothetical): a 30-seed sweep of the real 48-biome catalog, before this
    // existed, found realized edge lengths as low as 7e-12 blocks and 95 real illegal
    // biome adjacencies via RegionIntegrityValidator. 3 macro cells, not 1 - a single
    // cell-width margin still leaves almost no room for the macro grid's own region
    // warp (MacroGridConfig's own boundary wobble) to shift which cell actually samples
    // a given point from one solve to the next.
    private static final int MIN_EDGE_LENGTH_MACRO_CELLS = 6;

    // Generous but small relative to TRIANGULATION_MARGIN_BLOCKS (1500) - this is a
    // pure-read lookup mesh (no CSP), only needs to comfortably contain the nearest
    // lattice candidate's own immediate ring with real (non-hull-touching) adjacency, not
    // a whole legality-solving neighborhood. See #tryFastPathLookup's own doc.
    private static final double FAST_PATH_LOOKUP_PAD_BLOCKS = 3 * HEX_SIZE_BLOCKS;

    // How big a mesh to build for one organic-cluster-growth solve attempt (see this
    // whole redesign's own plan doc: query-centered clusters, no fixed grid) - NOT a
    // fail-loud limit, just the mesh's own literal size. Comfortably larger than the real
    // catalog's largest biome width (~1000 blocks) so ordinary INCREMENTAL closure (next
    // to already-decided territory) finishes long before reaching it; on genuinely fresh,
    // never-touched territory, growth legitimately uses the whole thing (see
    // #realizeCluster's own doc for why that's expected, not exceptional) - this value is
    // therefore also the worst-case first-touch solve size, mirroring the old window
    // design's own first-touch cost. Tune from real deployment once one exists, not
    // guessed further here - same discipline already established for RegionCacheHolder's
    // own MAX_CACHED_WINDOWS.
    private static final double CLUSTER_GROWTH_CAP_RADIUS_BLOCKS = 2500;

    private TriangleFieldGeometryRealization() {
    }

    /** This region's own mesh cells' bounding box, in block coordinates — the input to both mesh seeding (padded by {@link #TRIANGULATION_MARGIN_BLOCKS}) and the CSP's own area-fairness math. */
    private record BlockBounds(int minX, int maxX, int minZ, int maxZ) {
        double areaBlocks2() {
            return (double) (maxX - minX) * (maxZ - minZ);
        }
    }

    public static RegionSolveResult realize(RegionSolveInput input) {
        MacroGridConfig gridConfig = input.gridConfig();
        List<MacroCellPos> cells = gridConfig.cellsInRegion(input.region(), input.worldSeed());
        BlockBounds bounds = blockBoundsOf(cells, gridConfig.macroCellSizeBlocks());

        HexMeshBuilder.HexMesh mesh = buildMesh(bounds);
        TriangleAdjacency.Adjacency adjacency = TriangleAdjacency.of(mesh.faces());
        Map<Integer, Point2D> centroids = centroidsOf(mesh);

        Map<Integer, BiomeId> fixedFaceBiomes = buildFixedFaceBiomes(centroids, gridConfig, input.fixedBoundaryCells());
        Set<Integer> freeFaceIndices = freeFaceIndices(mesh.faces().size(), adjacency, fixedFaceBiomes);

        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments = solveIdentities(
            adjacency, freeFaceIndices, fixedFaceBiomes, input.graph(), input.worldSeed(), bounds.areaBlocks2(), input.region()
        );

        double minEdgeLengthBlocks = MIN_EDGE_LENGTH_MACRO_CELLS * gridConfig.macroCellSizeBlocks();
        Map<Integer, Point2D> displacedVertices = WeightedVertexPlacement.displacedVertexPositions(mesh, adjacency, assignments, HEX_SIZE_BLOCKS, minEdgeLengthBlocks);
        Map<MacroCellPos, BiomeId> result = EdgeDisplacementRasterizer.rasterize(
            cells, assignments, mesh, adjacency, displacedVertices, fixedFaceBiomes, input.fixedBoundaryCells(), gridConfig, input.worldSeed()
        );
        return new RegionSolveResult(result, List.of(), 0);
    }

    /** Every vertex in this mesh already committed by an earlier call, looked up by its stable {@link VertexKey}. */
    private static Map<Integer, Point2D> alreadyDecidedVerticesOf(HexMeshBuilder.HexMesh mesh, TriangleAdjacency.Adjacency adjacency, DecidedVertexStore vertexStore) {
        Map<Integer, Point2D> alreadyDecided = new LinkedHashMap<>();
        for (int[] triangle : adjacency.mutuallyAdjacentTriples(allFaceIndicesOf(mesh))) {
            VertexKey key = vertexKeyOf(mesh, triangle);
            vertexStore.get(key).ifPresent(position -> alreadyDecided.put(TriangleAdjacency.sharedVertexOf(mesh, triangle), position));
        }
        return alreadyDecided;
    }

    /**
     * Called by {@link #realizeCluster} — commits every face in {@code clusterFaceIndices}
     * as TENTATIVE (never directly permanent — see {@link DecidedFaceStore}'s own doc for
     * why only the actual query-target face, promoted separately by {@link
     * #promoteFaceToPermanent}, ever becomes irrevocable). A cluster's own boundary IS
     * exactly "everything this solve decided," by construction — there is no leftover
     * "margin-only" subset to exclude the way the old window-based design had to carve out.
     */
    private static void commitClusterFacesTentatively(
        HexMeshBuilder.HexMesh mesh, Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments, Set<Integer> clusterFaceIndices, DecidedFaceStore faceStore
    ) {
        Map<HexGrid.AxialPos, DecidedFaceStore.DecidedFace> newlyDecided = new LinkedHashMap<>();
        for (int faceIndex : clusterFaceIndices) {
            TriangleIdentitySizeAssignment.Assignment assignment = assignments.get(faceIndex);
            newlyDecided.put(mesh.faces().get(faceIndex).axialPos(), new DecidedFaceStore.DecidedFace(assignment.biome(), assignment.widthBlocks()));
        }
        faceStore.commitTentative(newlyDecided);
    }

    /**
     * A vertex becomes eligible to TENTATIVELY commit the moment all 3 of its owning faces
     * have SOME value (tentative or permanent) — either already-decided context ({@code
     * fixedFaceBiomes}, which may itself be merely tentative when this cluster's own
     * growth treated tentative neighbors as fixed — see {@link #realizeCluster}'s own doc
     * on the two-attempt strategy) or newly committed THIS call ({@code
     * clusterFaceIndices} — the whole cluster).
     */
    private static void commitClusterVerticesTentatively(
        HexMeshBuilder.HexMesh mesh, TriangleAdjacency.Adjacency adjacency, Set<Integer> clusterFaceIndices,
        Map<Integer, BiomeId> fixedFaceBiomes, Map<Integer, Point2D> alreadyDecidedVertices, Map<Integer, Point2D> displacedVertices, DecidedVertexStore vertexStore
    ) {
        Map<VertexKey, Point2D> newlyDecided = new LinkedHashMap<>();
        for (int[] triangle : adjacency.mutuallyAdjacentTriples(allFaceIndicesOf(mesh))) {
            int vertexIndex = TriangleAdjacency.sharedVertexOf(mesh, triangle);
            if (alreadyDecidedVertices.containsKey(vertexIndex)) {
                continue; // already available (tentative or permanent) from an earlier call - recomputed fresh above regardless, but nothing new to commit
            }
            boolean allThreeFacesHaveSomeValue = true;
            for (int faceIndex : triangle) {
                if (!clusterFaceIndices.contains(faceIndex) && !fixedFaceBiomes.containsKey(faceIndex)) {
                    allThreeFacesHaveSomeValue = false;
                    break;
                }
            }
            if (allThreeFacesHaveSomeValue) {
                newlyDecided.put(vertexKeyOf(mesh, triangle), displacedVertices.get(vertexIndex));
            }
        }
        vertexStore.commitTentative(newlyDecided);
    }

    /**
     * Promotes ONE specific, already-valued (tentative or permanent) face to PERMANENT,
     * forever — the direct trigger being "this face's biome was just handed to a REAL
     * caller" (see {@code SeedMeshCache}'s own doc: Minecraft already used it to place real
     * terrain, so it can never change again). Also promotes any of the face's own vertex
     * triples whose all 3 owning faces are NOW permanent (including this one, just
     * promoted) — the moment a vertex's last remaining non-permanent owner locks in, the
     * vertex itself becomes irrevocable too, same "commit once, forever" guarantee one
     * level down.
     *
     * <p>Never assigns a NEW identity — the face must already have a real value (from an
     * earlier tentative cluster solve, or already permanent from an even earlier query);
     * this only ever locks in whatever's already there.
     */
    private static void promoteFaceToPermanent(
        HexMeshBuilder.HexMesh mesh, TriangleAdjacency.Adjacency adjacency, int faceIndex, DecidedFaceStore faceStore, DecidedVertexStore vertexStore
    ) {
        HexGrid.AxialPos axialPos = mesh.faces().get(faceIndex).axialPos();
        DecidedFaceStore.DecidedFace current = faceStore.get(axialPos).orElseThrow(() -> new IllegalStateException(
            "TriangleFieldGeometryRealization.promoteFaceToPermanent: face " + axialPos + " has no value to promote - "
                + "real bug, the caller must ensure a value already exists before promoting it"
        ));
        faceStore.commitPermanent(Map.of(axialPos, current));

        Set<Integer> localGroup = new LinkedHashSet<>();
        localGroup.add(faceIndex);
        localGroup.addAll(adjacency.neighborFaceIndices().getOrDefault(faceIndex, Set.of()));
        for (int[] triangle : adjacency.mutuallyAdjacentTriples(localGroup)) {
            boolean allThreeNowPermanent = true;
            for (int member : triangle) {
                if (faceStore.getPermanent(mesh.faces().get(member).axialPos()).isEmpty()) {
                    allThreeNowPermanent = false;
                    break;
                }
            }
            if (!allThreeNowPermanent) {
                continue;
            }
            VertexKey key = vertexKeyOf(mesh, triangle);
            Optional<Point2D> position = vertexStore.get(key);
            if (position.isEmpty()) {
                // All 3 owning faces are NOW globally permanent, but no solve ever had all 3
                // simultaneously in view to actually COMPUTE this vertex - a real, expected
                // gap (they could have become permanent via 3 separate, non-overlapping
                // solves), not a bug: "all 3 permanent" and "this vertex was ever computed"
                // are genuinely different questions. Skip it - a future solve that actually
                // needs this vertex (its own mesh reaching all 3 owning faces at once) will
                // compute and commit it fresh then, using their now-permanent real widths.
                continue;
            }
            vertexStore.commitPermanent(Map.of(key, position.get()));
        }
    }

    private static VertexKey vertexKeyOf(HexMeshBuilder.HexMesh mesh, int[] triangle) {
        return VertexKey.of(mesh.faces().get(triangle[0]), mesh.faces().get(triangle[1]), mesh.faces().get(triangle[2]));
    }

    private static Set<Integer> allFaceIndicesOf(HexMeshBuilder.HexMesh mesh) {
        Set<Integer> allFaceIndices = new HashSet<>();
        for (int faceIndex = 0; faceIndex < mesh.faces().size(); faceIndex++) {
            allFaceIndices.add(faceIndex);
        }
        return allFaceIndices;
    }

    /** One resolved fast-path match: which mesh/adjacency found it (needed to promote it later) and which face/biome it resolved to. */
    private record FastPathMatch(HexMeshBuilder.HexMesh mesh, TriangleAdjacency.Adjacency adjacency, int faceIndex, BiomeId biome) {
    }

    /**
     * Heuristic pre-check, no lock needed (pure reads against {@link DecidedFaceStore}/
     * {@link DecidedVertexStore}'s own thread-safe stores): builds a SMALL local mesh
     * around {@code (queryX, queryZ)}, and checks whether the nearest hex-lattice
     * candidate face (see {@link HexGrid#nearestAxialPos}) OR one of its immediate
     * mesh-adjacency neighbors has SOME value already (tentative or permanent — either is
     * a real, currently-standing answer, see {@link DecidedFaceStore}'s own doc) with all
     * 6 of its own corners also committed, and its REAL displaced boundary actually
     * contains the point.
     *
     * <p>Safe to under-match, never over-matches: a returned value is always a genuine
     * point-in-polygon test against real, already-committed geometry — never a guess.
     * Missing a real match here (e.g. because an extreme-width face's true boundary
     * reaches past this method's own small ring) only costs an unnecessary cluster solve
     * later, never a wrong answer — see {@link #realizeCluster}'s own doc for the
     * AUTHORITATIVE check once a cluster is actually solved.
     */
    private static Optional<FastPathMatch> findFastPathMatch(long worldSeed, double queryX, double queryZ, DecidedFaceStore faceStore, DecidedVertexStore vertexStore) {
        double pad = FAST_PATH_LOOKUP_PAD_BLOCKS;
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(
            HEX_SIZE_BLOCKS, (int) Math.floor(queryX - pad), (int) Math.floor(queryZ - pad), (int) Math.ceil(queryX + pad), (int) Math.ceil(queryZ + pad)
        );
        TriangleAdjacency.Adjacency adjacency = TriangleAdjacency.of(mesh.faces());

        HexGrid.AxialPos nearest = HexGrid.nearestAxialPos(new Point2D(queryX, queryZ), HEX_SIZE_BLOCKS);
        Optional<Integer> candidateIndex = findFaceIndex(mesh, nearest);
        if (candidateIndex.isEmpty()) {
            return Optional.empty(); // lookup pad too small to even contain the nearest candidate - vanishingly unlikely, just fall to the slow path
        }

        Set<Integer> ring = new LinkedHashSet<>();
        ring.add(candidateIndex.get());
        ring.addAll(adjacency.neighborFaceIndices().getOrDefault(candidateIndex.get(), Set.of()));

        Map<Integer, Point2D> committedPositions = alreadyDecidedVerticesOf(mesh, adjacency, vertexStore);
        for (int faceIndex : ring) {
            Optional<BiomeId> match = matchIfDecidedAndContains(mesh, adjacency, faceIndex, committedPositions, faceStore, worldSeed, queryX, queryZ);
            if (match.isPresent()) {
                return Optional.of(new FastPathMatch(mesh, adjacency, faceIndex, match.get()));
            }
        }
        return Optional.empty();
    }

    /** See {@link #findFastPathMatch}'s own doc — this is the thin, read-only, side-effect-free view of it. */
    public static Optional<BiomeId> tryFastPathLookup(long worldSeed, double queryX, double queryZ, DecidedFaceStore faceStore, DecidedVertexStore vertexStore) {
        return findFastPathMatch(worldSeed, queryX, queryZ, faceStore, vertexStore).map(FastPathMatch::biome);
    }

    /**
     * Called by the caller's own lock (see {@code SeedMeshCache}'s own doc) after {@link
     * #tryFastPathLookup} found a match that turned out to be merely TENTATIVE — promotes
     * that specific face (and any of its vertex triples now fully permanent) before the
     * caller hands the answer to a real caller. A no-op (safe to call regardless) if the
     * match was already permanent — {@link DecidedFaceStore#commitPermanent}'s own
     * same-value re-commit is a harmless no-op.
     */
    public static void promoteCoveringFaceToPermanent(long worldSeed, double queryX, double queryZ, DecidedFaceStore faceStore, DecidedVertexStore vertexStore) {
        FastPathMatch match = findFastPathMatch(worldSeed, queryX, queryZ, faceStore, vertexStore).orElseThrow(() -> new IllegalStateException(
            "TriangleFieldGeometryRealization.promoteCoveringFaceToPermanent: no fast-path match found for (" + queryX + "," + queryZ + ") - "
                + "caller must only call this right after tryFastPathLookup found one, real bug if it didn't this time"
        ));
        promoteFaceToPermanent(match.mesh(), match.adjacency(), match.faceIndex(), faceStore, vertexStore);
    }

    /** {@code null}-safe: returns empty if this face has no value yet, isn't fully-cornered yet, or its real boundary simply doesn't contain the point - never throws for those ordinary cases. */
    private static Optional<BiomeId> matchIfDecidedAndContains(
        HexMeshBuilder.HexMesh mesh, TriangleAdjacency.Adjacency adjacency, int faceIndex, Map<Integer, Point2D> committedPositions,
        DecidedFaceStore faceStore, long worldSeed, double queryX, double queryZ
    ) {
        Optional<DecidedFaceStore.DecidedFace> decided = faceStore.get(mesh.faces().get(faceIndex).axialPos());
        if (decided.isEmpty()) {
            return Optional.empty();
        }
        boolean allCornersCommitted = mesh.faces().get(faceIndex).vertexIndices().stream().allMatch(committedPositions::containsKey);
        if (!allCornersCommitted) {
            return Optional.empty(); // this face has a value but not every corner does yet - can't safely build its real boundary
        }
        List<Point2D> boundary = EdgeDisplacementRasterizer.displacedBoundaryOf(faceIndex, mesh, adjacency, committedPositions, worldSeed);
        return EdgeDisplacementRasterizer.isInsidePolygon(boundary, queryX, queryZ) ? Optional.of(decided.get().biomeId()) : Optional.empty();
    }

    private static Optional<Integer> findFaceIndex(HexMeshBuilder.HexMesh mesh, HexGrid.AxialPos axialPos) {
        for (int faceIndex = 0; faceIndex < mesh.faces().size(); faceIndex++) {
            if (mesh.faces().get(faceIndex).axialPos().equals(axialPos)) {
                return Optional.of(faceIndex);
            }
        }
        return Optional.empty();
    }

    /** One BFS growth attempt's own result — see {@link #growCluster}'s own doc. */
    private record ClusterGrowth(Set<Integer> cluster, Map<Integer, DecidedFaceStore.DecidedFace> fixedContext) {
    }

    /**
     * BFS over real mesh-adjacency from {@code seedIndex} and its own immediate ring.
     * {@code treatTentativeAsFixed} selects which of the two growth attempts {@link
     * #realizeCluster} runs: {@code true} (attempt 1, the cheap common case) stops at ANY
     * already-valued face (tentative or permanent) exactly like the old single-tier
     * design did; {@code false} (attempt 2, only run if attempt 1's own CSP solve failed)
     * stops ONLY at PERMANENT faces, pulling every merely-tentative neighbor into the
     * cluster instead — safe to revise, since nothing irrevocable has happened to it yet
     * (see {@link DecidedFaceStore}'s own doc). A hull-touching face always stops growth
     * regardless of attempt (ran out of mesh — normal on fresh territory, not an error).
     */
    private static ClusterGrowth growCluster(
        HexMeshBuilder.HexMesh mesh, TriangleAdjacency.Adjacency adjacency, int seedIndex, DecidedFaceStore faceStore, boolean treatTentativeAsFixed
    ) {
        Set<Integer> cluster = new LinkedHashSet<>();
        Map<Integer, DecidedFaceStore.DecidedFace> fixedContext = new LinkedHashMap<>();
        Set<Integer> visited = new HashSet<>();
        Deque<Integer> frontier = new ArrayDeque<>();
        visited.add(seedIndex);
        frontier.add(seedIndex);
        for (int neighbor : adjacency.neighborFaceIndices().getOrDefault(seedIndex, Set.of())) {
            if (visited.add(neighbor)) {
                frontier.add(neighbor);
            }
        }

        while (!frontier.isEmpty()) {
            int faceIndex = frontier.poll();
            HexGrid.AxialPos axialPos = mesh.faces().get(faceIndex).axialPos();
            Optional<DecidedFaceStore.DecidedFace> permanent = faceStore.getPermanent(axialPos);
            if (permanent.isPresent()) {
                fixedContext.put(faceIndex, permanent.get());
                continue; // a true hard wall, both attempts stop here
            }
            if (treatTentativeAsFixed) {
                Optional<DecidedFaceStore.DecidedFace> tentative = faceStore.get(axialPos); // permanent already ruled out above, so this reads tentative
                if (tentative.isPresent()) {
                    fixedContext.put(faceIndex, tentative.get());
                    continue;
                }
            }
            if (adjacency.hullTouchingFaceIndices().contains(faceIndex)) {
                continue; // ran out of mesh (normal on fresh territory) - excluded, no data, no error
            }
            cluster.add(faceIndex);
            for (int neighbor : adjacency.neighborFaceIndices().getOrDefault(faceIndex, Set.of())) {
                if (visited.add(neighbor)) {
                    frontier.add(neighbor);
                }
            }
        }
        return new ClusterGrowth(cluster, fixedContext);
    }

    /**
     * The organic-cluster-growth slow path (see this whole redesign's own plan doc):
     * called under the caller's own single lock (see {@code SeedMeshCache}) after {@link
     * #tryFastPathLookup} found nothing.
     *
     * <p><b>Two-attempt strategy — the direct fix for a real dead-end crash</b> (see this
     * whole redesign's own plan doc for the full incident): attempt 1 ({@link
     * #growCluster} with {@code treatTentativeAsFixed=true}) is cheap and matches almost
     * every query — tentative neighbors are treated as ordinary fixed context, same cost
     * as the old single-tier design. If that CSP solve fails, attempt 2 re-grows the SAME
     * seed with {@code treatTentativeAsFixed=false}, pulling every reachable tentative
     * neighbor into the free set instead of treating it as a wall — genuine extra
     * flexibility, since nothing about a tentative face is irrevocable yet. Only if attempt
     * 2 ALSO fails (every reachable, still-revisable face jointly has no legal solution,
     * with only truly PERMANENT faces as real constraints) does this fail loud — a
     * strictly narrower, rarer trigger than "any already-decided neighbor, however it got
     * that way."
     *
     * <p>Whichever attempt succeeds commits its OWN cluster faces/vertices as TENTATIVE
     * (never directly permanent) — then the ONE face that actually covers {@code
     * (queryX, queryZ)} is promoted to PERMANENT via {@link #promoteFaceToPermanent},
     * since Minecraft is about to use this exact answer to place real terrain. Everything
     * else touched by this solve stays tentative, freely revisable by a LATER query that
     * needs to.
     *
     * <p><b>Reaching the mesh's own hull is a NORMAL stopping condition, not an error</b> —
     * found the hard way: on genuinely fresh, never-touched territory (nothing decided
     * ANYWHERE nearby, the common first-touch case), every direction is undecided, so BFS
     * growth never finds an already-decided boundary to stop at, and legitimately grows
     * until it runs out of mesh. {@link #CLUSTER_GROWTH_CAP_RADIUS_BLOCKS} is simply "how
     * big a mesh to build for one solve attempt," not a fail-loud limit; a first-touch
     * solve legitimately using the whole thing is expected, not exceptional.
     */
    public static BiomeId realizeCluster(
        BiomeGraph graph, MacroGridConfig gridConfig, long worldSeed, double queryX, double queryZ,
        DecidedFaceStore faceStore, DecidedVertexStore vertexStore
    ) {
        double cap = CLUSTER_GROWTH_CAP_RADIUS_BLOCKS;
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(
            HEX_SIZE_BLOCKS, (int) Math.floor(queryX - cap), (int) Math.floor(queryZ - cap), (int) Math.ceil(queryX + cap), (int) Math.ceil(queryZ + cap)
        );
        TriangleAdjacency.Adjacency adjacency = TriangleAdjacency.of(mesh.faces());

        HexGrid.AxialPos seedAxial = HexGrid.nearestAxialPos(new Point2D(queryX, queryZ), HEX_SIZE_BLOCKS);
        int seedIndex = findFaceIndex(mesh, seedAxial).orElseThrow(() -> new IllegalStateException(
            "TriangleFieldGeometryRealization.realizeCluster: growth cap radius " + cap + " blocks too small to even "
                + "contain the seed face itself at (" + queryX + "," + queryZ + ") - should be geometrically impossible, real bug"
        ));

        ClusterGrowth attempt1 = growCluster(mesh, adjacency, seedIndex, faceStore, true);
        Map<Integer, Point2D> alreadyDecidedVertices = alreadyDecidedVerticesOf(mesh, adjacency, vertexStore);

        if (attempt1.cluster().isEmpty()) {
            // Every candidate reachable from the seed's own ring already has a value - the
            // fast-path pre-check's own narrower ring apparently missed a real match. Check
            // directly against this method's own more generous mesh before giving up.
            for (Map.Entry<Integer, DecidedFaceStore.DecidedFace> entry : attempt1.fixedContext().entrySet()) {
                List<Point2D> boundary = EdgeDisplacementRasterizer.displacedBoundaryOf(entry.getKey(), mesh, adjacency, alreadyDecidedVertices, worldSeed);
                if (EdgeDisplacementRasterizer.isInsidePolygon(boundary, queryX, queryZ)) {
                    promoteFaceToPermanent(mesh, adjacency, entry.getKey(), faceStore, vertexStore);
                    return entry.getValue().biomeId();
                }
            }
            throw new IllegalStateException(
                "TriangleFieldGeometryRealization.realizeCluster: query point (" + queryX + "," + queryZ + ") is not covered by "
                    + "any nearby face, decided or undecided - genuinely unexpected, needs investigation. seed=" + seedAxial + ", worldSeed=" + worldSeed
            );
        }

        Optional<Map<Integer, TriangleIdentitySizeAssignment.Assignment>> solved = trySolveCluster(attempt1, adjacency, graph, worldSeed);
        ClusterGrowth used = attempt1;
        if (solved.isEmpty()) {
            ClusterGrowth attempt2 = growCluster(mesh, adjacency, seedIndex, faceStore, false);
            solved = trySolveCluster(attempt2, adjacency, graph, worldSeed);
            if (solved.isEmpty()) {
                throw new IllegalStateException(
                    "TriangleFieldGeometryRealization.realizeCluster: no legal identity assignment exists for the cluster seeded at "
                        + seedAxial + " (worldSeed=" + worldSeed + "), even after pulling in every reachable tentative neighbor as free -"
                        + " a genuine dead end against only truly PERMANENT context. attempt1FreeFaceCount=" + attempt1.cluster().size()
                        + ", attempt1FixedFaceCount=" + attempt1.fixedContext().size() + ", attempt2FreeFaceCount=" + attempt2.cluster().size()
                        + ", attempt2FixedFaceCount=" + attempt2.fixedContext().size() + ", attempt2FixedFaceBiomes="
                        + biomesOf(attempt2.fixedContext())
                );
            }
            used = attempt2;
        }
        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments = solved.get();

        Map<Integer, BiomeId> fixedFaceBiomes = new LinkedHashMap<>();
        Map<Integer, Integer> committedWidthBlocksByFace = new LinkedHashMap<>();
        used.fixedContext().forEach((faceIndex, decided) -> {
            fixedFaceBiomes.put(faceIndex, decided.biomeId());
            committedWidthBlocksByFace.put(faceIndex, decided.widthBlocks());
        });

        double minEdgeLengthBlocks = MIN_EDGE_LENGTH_MACRO_CELLS * gridConfig.macroCellSizeBlocks();
        Map<Integer, Point2D> displacedVertices = WeightedVertexPlacement.displacedVertexPositions(
            mesh, adjacency, assignments, HEX_SIZE_BLOCKS, minEdgeLengthBlocks, committedWidthBlocksByFace, alreadyDecidedVertices
        );

        commitClusterFacesTentatively(mesh, assignments, used.cluster(), faceStore);
        commitClusterVerticesTentatively(mesh, adjacency, used.cluster(), fixedFaceBiomes, alreadyDecidedVertices, displacedVertices, vertexStore);

        for (int faceIndex : used.cluster()) {
            List<Point2D> boundary = EdgeDisplacementRasterizer.displacedBoundaryOf(faceIndex, mesh, adjacency, displacedVertices, worldSeed);
            if (EdgeDisplacementRasterizer.isInsidePolygon(boundary, queryX, queryZ)) {
                promoteFaceToPermanent(mesh, adjacency, faceIndex, faceStore, vertexStore);
                return assignments.get(faceIndex).biome();
            }
        }
        for (Map.Entry<Integer, DecidedFaceStore.DecidedFace> entry : used.fixedContext().entrySet()) {
            List<Point2D> boundary = EdgeDisplacementRasterizer.displacedBoundaryOf(entry.getKey(), mesh, adjacency, displacedVertices, worldSeed);
            if (EdgeDisplacementRasterizer.isInsidePolygon(boundary, queryX, queryZ)) {
                promoteFaceToPermanent(mesh, adjacency, entry.getKey(), faceStore, vertexStore);
                return entry.getValue().biomeId();
            }
        }
        throw new IllegalStateException(
            "TriangleFieldGeometryRealization.realizeCluster: solved cluster (seed=" + seedAxial + ", worldSeed=" + worldSeed
                + ", " + used.cluster().size() + " faces) doesn't cover its own query point (" + queryX + "," + queryZ + ") - a real "
                + "rasterizer/closure bug, not expected"
        );
    }

    private static Optional<Map<Integer, TriangleIdentitySizeAssignment.Assignment>> trySolveCluster(
        ClusterGrowth growth, TriangleAdjacency.Adjacency adjacency, BiomeGraph graph, long worldSeed
    ) {
        Map<Integer, BiomeId> fixedFaceBiomes = new LinkedHashMap<>();
        growth.fixedContext().forEach((faceIndex, decided) -> fixedFaceBiomes.put(faceIndex, decided.biomeId()));
        double clusterAreaBlocks2 = growth.cluster().size() * baselineFaceAreaBlocks2();
        return TriangleIdentitySizeAssignment.solve(adjacency, growth.cluster(), fixedFaceBiomes, graph, worldSeed, clusterAreaBlocks2);
    }

    private static Map<Integer, BiomeId> biomesOf(Map<Integer, DecidedFaceStore.DecidedFace> fixedContext) {
        Map<Integer, BiomeId> biomes = new LinkedHashMap<>();
        fixedContext.forEach((faceIndex, decided) -> biomes.put(faceIndex, decided.biomeId()));
        return biomes;
    }

    private static double baselineFaceAreaBlocks2() {
        return (3.0 * Math.sqrt(3.0) / 2.0) * HEX_SIZE_BLOCKS * HEX_SIZE_BLOCKS;
    }

    private static BlockBounds blockBoundsOf(List<MacroCellPos> cells, int cellSize) {
        int minBlockX = cells.stream().mapToInt(MacroCellPos::x).min().orElseThrow() * cellSize;
        int maxBlockX = (cells.stream().mapToInt(MacroCellPos::x).max().orElseThrow() + 1) * cellSize;
        int minBlockZ = cells.stream().mapToInt(MacroCellPos::z).min().orElseThrow() * cellSize;
        int maxBlockZ = (cells.stream().mapToInt(MacroCellPos::z).max().orElseThrow() + 1) * cellSize;
        return new BlockBounds(minBlockX, maxBlockX, minBlockZ, maxBlockZ);
    }

    /** {@code bounds} padded by {@link #TRIANGULATION_MARGIN_BLOCKS} on every side - the margin is what guarantees every face touching the actual region is non-hull-touching (real neighbor context on every side for the CSP's own legality solving). */
    private static HexMeshBuilder.HexMesh buildMesh(BlockBounds bounds) {
        return HexMeshBuilder.build(
            HEX_SIZE_BLOCKS,
            bounds.minX() - TRIANGULATION_MARGIN_BLOCKS, bounds.minZ() - TRIANGULATION_MARGIN_BLOCKS,
            bounds.maxX() + TRIANGULATION_MARGIN_BLOCKS, bounds.maxZ() + TRIANGULATION_MARGIN_BLOCKS
        );
    }

    private static Map<Integer, Point2D> centroidsOf(HexMeshBuilder.HexMesh mesh) {
        Map<Integer, Point2D> centroids = new LinkedHashMap<>();
        for (int faceIndex = 0; faceIndex < mesh.faces().size(); faceIndex++) {
            centroids.put(faceIndex, centroidOf(mesh.faces().get(faceIndex), mesh.vertices()));
        }
        return centroids;
    }

    private static Point2D centroidOf(HexFace face, List<Point2D> vertices) {
        double sumX = 0;
        double sumZ = 0;
        for (int vertexIndex : face.vertexIndices()) {
            Point2D vertex = vertices.get(vertexIndex);
            sumX += vertex.x();
            sumZ += vertex.z();
        }
        int cornerCount = face.vertexIndices().size();
        return new Point2D(sumX / cornerCount, sumZ / cornerCount);
    }

    /** Every face that's neither hull-touching (missing real neighbor context, see {@link TriangleAdjacency}'s own doc) nor already fixed by a neighbor region's own solve - the CSP only ever assigns these. */
    private static Set<Integer> freeFaceIndices(int faceCount, TriangleAdjacency.Adjacency adjacency, Map<Integer, BiomeId> fixedFaceBiomes) {
        Set<Integer> freeFaceIndices = new LinkedHashSet<>();
        for (int faceIndex = 0; faceIndex < faceCount; faceIndex++) {
            if (!adjacency.hullTouchingFaceIndices().contains(faceIndex) && !fixedFaceBiomes.containsKey(faceIndex)) {
                freeFaceIndices.add(faceIndex);
            }
        }
        return freeFaceIndices;
    }

    /**
     * The exception message here is the ONLY diagnostic trail a real, in-game dead end
     * leaves behind - {@code worldgen-core} deliberately has no logging dependency (see
     * this module's own build.gradle doc: staying pure Java keeps its tests fast plain
     * JUnit, not NeoForge's "unit testing mode" that boots a whole Minecraft instance),
     * so there is no separate logger call to fall back on. Whatever isn't in THIS string
     * is gone once the crash report closes - found the hard way (2026-09-04): a real
     * in-game dead end at {@code worldSeed=42}, {@code RegionPos[-2,8]}, reached only via
     * a structure-placement ring search (far outside spawn, never exercised by this
     * codebase's own 50-seed 2026-2075 sweep), crashed with only the region name to go
     * on - not enough to reproduce without re-running the exact same seed and hoping the
     * same ring search fires again. Every field a future repro test would need to
     * reconstruct this EXACT CSP call is included directly: the seed, the region, how
     * many faces were free vs. already fixed by a neighboring region's own solve, AND the
     * full fixed-face biome assignment itself (the one input a synthetic single-region
     * test can't fabricate on its own - it comes from whatever neighboring regions
     * happened to solve first, in whatever order this specific run's worker threads
     * happened to reach them).
     */
    private static Map<Integer, TriangleIdentitySizeAssignment.Assignment> solveIdentities(
        TriangleAdjacency.Adjacency adjacency, Set<Integer> freeFaceIndices, Map<Integer, BiomeId> fixedFaceBiomes,
        BiomeGraph graph, long worldSeed, double regionAreaBlocks2, RegionPos region
    ) {
        return TriangleIdentitySizeAssignment.solve(adjacency, freeFaceIndices, fixedFaceBiomes, graph, worldSeed, regionAreaBlocks2)
            .orElseThrow(() -> new IllegalStateException(
                "TriangleFieldGeometryRealization: no legal identity assignment exists for region " + region
                    + " (worldSeed=" + worldSeed + ") - the BiomeGraph's own construction-time checks only rule out"
                    + " configs impossible in principle, not every real local mesh arrangement."
                    + " freeFaceCount=" + freeFaceIndices.size() + ", fixedFaceCount=" + fixedFaceBiomes.size()
                    + ", regionAreaBlocks2=" + regionAreaBlocks2
                    + ", fixedFaceBiomes=" + fixedFaceBiomes
            ));
    }

    /**
     * Any free face whose centroid falls inside an already-solved neighbor region is fixed
     * to that neighbor's own already-committed biome at the containing cell, sourced from
     * {@link RegionSolveInput#fixedBoundaryCells()} — see this class's own doc for why this
     * closes the cross-region disagreement gap using {@link TriangleIdentitySizeAssignment}'s
     * existing fixed-context mechanism with no changes needed inside that class. A face whose
     * centroid finds no exact entry (its neighbor genuinely isn't solved yet, or it's simply
     * still within this region's own legitimate territory) is correctly left unfixed - there
     * is no ground truth to pin it to.
     */
    private static Map<Integer, BiomeId> buildFixedFaceBiomes(
        Map<Integer, Point2D> centroids, MacroGridConfig gridConfig, Map<MacroCellPos, BiomeId> fixedBoundaryCells
    ) {
        if (fixedBoundaryCells.isEmpty()) {
            return Map.of();
        }
        Map<Integer, BiomeId> fixedFaceBiomes = new LinkedHashMap<>();
        centroids.forEach((faceIndex, centroid) -> {
            MacroCellPos containing = gridConfig.cellContaining(centroid.x(), centroid.z());
            BiomeId fixed = fixedBoundaryCells.get(containing);
            if (fixed != null) {
                fixedFaceBiomes.put(faceIndex, fixed);
            }
        });
        return fixedFaceBiomes;
    }
}
