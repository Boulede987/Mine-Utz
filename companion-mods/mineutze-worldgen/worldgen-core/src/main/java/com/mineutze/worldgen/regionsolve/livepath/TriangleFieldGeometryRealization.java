package com.mineutze.worldgen.regionsolve.livepath;

import com.mineutze.worldgen.regionsolve.topology.mesh.HexGrid;

import com.mineutze.worldgen.regionsolve.topology.mesh.HexMeshBuilder;

import com.mineutze.worldgen.regionsolve.topology.placement.WeightedVertexPlacement;

import com.mineutze.worldgen.regionsolve.topology.mesh.VertexKey;

import com.mineutze.worldgen.regionsolve.topology.identity.TriangleIdentitySizeAssignment;

import com.mineutze.worldgen.regionsolve.topology.mesh.FaceAdjacency;

import com.mineutze.worldgen.regionsolve.topology.mesh.HexFace;

import com.mineutze.worldgen.regionsolve.RegionPos;

import com.mineutze.worldgen.regionsolve.RegionSolveResult;

import com.mineutze.worldgen.regionsolve.MacroGridConfig;

import com.mineutze.worldgen.regionsolve.MacroCellPos;

import com.mineutze.worldgen.regionsolve.RegionSolveInput;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.regionsolve.topology.Point2D;

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
 * Live region-solve path: {@link HexMeshBuilder} (regular hex lattice, exactly 3 faces
 * meeting at every vertex - see doc/decisions/2026-08-29-hex-mesh-edge-displacement.md)
 * → {@link FaceAdjacency} (real ≤6-neighbor mesh topology) → {@link
 * TriangleIdentitySizeAssignment} (fused identity+size CSP, legality fully decided
 * here) → {@link WeightedVertexPlacement} (power-diagram vertex placement, size baked
 * directly into WHERE each corner sits) → {@link EdgeDisplacementRasterizer}
 * (independent per-face polygon rasterization, no competition, no live legality
 * re-check) → {@code Map<MacroCellPos,BiomeId>}.
 *
 * <p>A hex mesh's vertex-degree-3 property makes edge-adjacency and vertex-adjacency the
 * same relation, always, so CSP-time legality checking is provably complete and the
 * rasterizer needs no live per-cell check. See DECISIONS.md 2026-08-29 entry for why
 * this superseded the earlier Delaunay-triangle-mesh + competitive-growth path.
 *
 * <p><b>The mesh's raw point positions matter only up to identity+size assignment.</b>
 * After that, a face's SHAPE comes from its corners' weighted positions ({@link
 * WeightedVertexPlacement}) plus organic wiggle ({@link
 * com.mineutze.worldgen.regionsolve.topology.EdgeDisplacement}), never from growth
 * competing for territory. {@link RegionSolveInput#fixedBoundaryCells} is still
 * consulted for IDENTITY (a face whose centroid falls in an already-solved neighbor is
 * fixed to that neighbor's decision, via {@link TriangleIdentitySizeAssignment}'s {@code
 * fixedFaceBiomes} mechanism - see {@link #buildFixedFaceBiomes}); a cross-region
 * vertex falls back to a baseline (unweighted) position in {@link
 * WeightedVertexPlacement} (only real, in-region assignments carry a real weight - see
 * its doc), so a region boundary displaces with wiggle only, not size-driven precision -
 * matches the "legality guaranteed, seam smoothness a separate concern" split the old
 * path had.
 */
public final class TriangleFieldGeometryRealization {

    // 650-block undisplaced width (circumradius 325), not the naive 800 (circumradius
    // 400) doc/DESIGN.md's raw 300-1000 range midpoint would suggest - chosen to minimize
    // the REAL 48-biome catalog's total starting size deficit instead: ModBiomeGraph.
    // OVERWORLD's size ranges aren't evenly spread across 300-1000 (14 biomes in 300-600,
    // 24 in 500-800, only 10 in 700-1000), and 800 sits at tier-2's ceiling but far above
    // the ENTIRE tier-1 range (max 600). 650 picked by minimizing total
    // |targetArea(biome's midpoint width) - targetArea(650)| summed across all 48 real
    // biomes, weighted by biomes per tier - see WeightedVertexPlacement's doc for the
    // area/weight formula reused here. Still not a full fix even under the current
    // power-diagram mechanism (originally justified against the OLDER, deleted per-edge
    // additive-bias mechanism, whose "same-tier neighbors trade zero area" degeneracy no
    // longer exists as a hard rule - but a real, measured softer version remains: a face
    // wanting to be much smaller than its surrounding weights still lands systematically
    // oversized, since weight-equals-target-area is only a one-shot proxy for realized
    // area, not exact - see WeightedVertexPlacement's doc). Reduces every face's own
    // starting gap regardless of which size mechanism sits downstream.
    //
    // This EXACT value was tried once already and reverted: it exposed two real dead ends
    // in TriangleIdentitySizeAssignment's backtracking search that plain forward checking
    // (and later, binary AC-3 alone) couldn't see - worldSeed=2034's
    // 13s-at-400-but-non-terminating-at-325 case, then worldSeed=2060's genuine
    // zero-valid-triple mesh-vertex dead end. Both fixed now (real AC-3 + mesh-vertex
    // triple consistency + a hard node-budget safety net - see that class's doc for the
    // full story). Swept all 50 real starvation-test seeds at this value post-fix: 100%
    // solve clean. Re-applying isn't re-trying the same broken thing - the blocker was
    // fixed at its root, in the CSP.
    private static final double HEX_SIZE_BLOCKS = 325;
    // Generous margin (matches the old triangle mesh's own empirically-trusted value) -
    // guarantees every face touching the actual region is non-hull-touching (real
    // neighbor context on every side for the CSP's legality solving).
    //
    // Public, not package-private: RegionBoundaryCollector.cellsBorderingRegion (sibling
    // windowcache package) reuses this SAME constant as its own collection depth - exact
    // bound past which no face centroid in a region's mesh can exist, so both need one
    // shared source of truth, not two independently-tuned numbers that could drift apart.
    public static final int TRIANGULATION_MARGIN_BLOCKS = 1500;

    // A face's edge is a real physical boundary only if wide enough for the macro-cell
    // pixel grid to sample it - a face squeezed thinner than this has zero cell centers
    // along that edge, so the rasterizer's point-in-polygon test silently skips it, and
    // the two faces on either side end up pixel-adjacent with nothing sampled between
    // them. Real, live defect (not hypothetical): a 30-seed sweep of the real 48-biome
    // catalog, before this existed, found realized edge lengths as low as 7e-12 blocks
    // and 95 real illegal biome adjacencies via RegionIntegrityValidator. 3 macro cells,
    // not 1 - a single cell-width margin leaves almost no room for the macro grid's own
    // region warp (MacroGridConfig's boundary wobble) to shift which cell samples a
    // point between solves.
    private static final int MIN_EDGE_LENGTH_MACRO_CELLS = 6;

    // Generous but small relative to TRIANGULATION_MARGIN_BLOCKS (1500) - pure-read
    // lookup mesh (no CSP), only needs to contain the nearest lattice candidate's
    // immediate ring with real (non-hull-touching) adjacency, not a whole
    // legality-solving neighborhood. See #tryFastPathLookup's doc.
    private static final double FAST_PATH_LOOKUP_PAD_BLOCKS = 3 * HEX_SIZE_BLOCKS;

    // How big a mesh to build for one organic-cluster-growth solve attempt (see this
    // redesign's plan doc: query-centered clusters, no fixed grid) - NOT a fail-loud
    // limit, just the mesh's literal size. Comfortably larger than the real catalog's
    // largest biome width (~1000 blocks) so ordinary INCREMENTAL closure (next to
    // already-decided territory) finishes well before reaching it; on genuinely fresh,
    // never-touched territory, growth legitimately uses the whole thing (see
    // #realizeCluster's doc for why that's expected, not exceptional) - also the
    // worst-case first-touch solve size, mirroring the old window design's own
    // first-touch cost. Tune from real deployment once one exists - same discipline as
    // RegionCacheHolder's own MAX_CACHED_WINDOWS.
    private static final double CLUSTER_GROWTH_CAP_RADIUS_BLOCKS = 2500;

    private TriangleFieldGeometryRealization() {
    }

    /** This region's mesh cells' bounding box, in block coords - input to both mesh seeding (padded by {@link #TRIANGULATION_MARGIN_BLOCKS}) and the CSP's own area-fairness math. */
    private record BlockBounds(int minX, int maxX, int minZ, int maxZ) {
        double areaBlocks2() {
            return (double) (maxX - minX) * (maxZ - minZ);
        }
    }

    /**
     * <b>Export-only today, not the shipped mod's live path</b> - its only production
     * caller is {@link SeedRegionCache} (export-only, see that class's doc); the shipped
     * mod calls {@link #realizeCluster} instead, via {@code SeedMeshCache}. Real,
     * previously-costly confusion: this whole-mesh, single-shot path (joint calibration,
     * whole region at once) has a genuinely different accuracy profile than {@link
     * #realizeCluster}'s incremental, committed-context-aware one - a whole session's
     * worth of size-fidelity measurement was spent against THIS method before that gap was
     * found; use {@code SizeFidelityLivePathMeasurement}, not {@code SizeFidelityMeasurement}
     * / {@code ModBiomeGraphTest}'s whole-mesh tests, for any real live-path number.
     */
    public static RegionSolveResult realize(RegionSolveInput input) {
        MacroGridConfig gridConfig = input.gridConfig();
        List<MacroCellPos> cells = gridConfig.cellsInRegion(input.region(), input.worldSeed());
        BlockBounds bounds = blockBoundsOf(cells, gridConfig.macroCellSizeBlocks());

        HexMeshBuilder.HexMesh mesh = buildMesh(bounds);
        FaceAdjacency.Adjacency adjacency = FaceAdjacency.of(mesh.faces());
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
    private static Map<Integer, Point2D> alreadyDecidedVerticesOf(HexMeshBuilder.HexMesh mesh, FaceAdjacency.Adjacency adjacency, DecidedVertexStore vertexStore) {
        Map<Integer, Point2D> alreadyDecided = new LinkedHashMap<>();
        for (int[] triangle : adjacency.mutuallyAdjacentFaceTriples(allFaceIndicesOf(mesh))) {
            VertexKey key = vertexKeyOf(mesh, triangle);
            vertexStore.get(key).ifPresent(position -> alreadyDecided.put(FaceAdjacency.sharedVertexOf(mesh, triangle), position));
        }
        return alreadyDecided;
    }

    /**
     * Called by {@link #realizeCluster} - commits every face in {@code clusterFaceIndices}
     * as TENTATIVE (never directly permanent - see {@link DecidedFaceStore}'s doc for why
     * only the actual query-target face, promoted separately by {@link
     * #promoteFaceToPermanent}, ever becomes irrevocable). A cluster's boundary IS exactly
     * "everything this solve decided," by construction - no leftover "margin-only" subset
     * to exclude, unlike the old window-based design.
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
     * A vertex becomes eligible to TENTATIVELY commit the moment all 3 owning faces have
     * SOME value (tentative or permanent) - either already-decided context ({@code
     * fixedFaceBiomes}, itself possibly tentative when this cluster's growth treated
     * tentative neighbors as fixed - see {@link #realizeCluster}'s doc on the two-attempt
     * strategy) or newly committed THIS call ({@code clusterFaceIndices} - the whole
     * cluster).
     */
    private static void commitClusterVerticesTentatively(
        HexMeshBuilder.HexMesh mesh, FaceAdjacency.Adjacency adjacency, Set<Integer> clusterFaceIndices,
        Map<Integer, BiomeId> fixedFaceBiomes, Map<Integer, Point2D> alreadyDecidedVertices, Map<Integer, Point2D> displacedVertices, DecidedVertexStore vertexStore
    ) {
        Map<VertexKey, Point2D> newlyDecided = new LinkedHashMap<>();
        for (int[] triangle : adjacency.mutuallyAdjacentFaceTriples(allFaceIndicesOf(mesh))) {
            int vertexIndex = FaceAdjacency.sharedVertexOf(mesh, triangle);
            if (alreadyDecidedVertices.containsKey(vertexIndex)) {
                continue; // already available (tentative or permanent) from an earlier call - recomputed above regardless, nothing new to commit
            }
            if (allThreeFacesHaveSomeValue(triangle, clusterFaceIndices, fixedFaceBiomes)) {
                newlyDecided.put(vertexKeyOf(mesh, triangle), displacedVertices.get(vertexIndex));
            }
        }
        vertexStore.commitTentative(newlyDecided);
    }

    private static boolean allThreeFacesHaveSomeValue(int[] triangle, Set<Integer> clusterFaceIndices, Map<Integer, BiomeId> fixedFaceBiomes) {
        for (int faceIndex : triangle) {
            if (!clusterFaceIndices.contains(faceIndex) && !fixedFaceBiomes.containsKey(faceIndex)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Promotes ONE specific, already-valued (tentative or permanent) face to PERMANENT,
     * forever - direct trigger is "this face's biome was just handed to a REAL caller"
     * (see {@code SeedMeshCache}'s doc: Minecraft already used it to place real terrain,
     * so it can never change). Also promotes any of the face's vertex triples whose all 3
     * owning faces are NOW permanent (including this one, just promoted) - the moment a
     * vertex's last non-permanent owner locks in, the vertex itself becomes irrevocable
     * too, same "commit once, forever" guarantee one level down.
     *
     * <p>Never assigns a NEW identity - the face must already have a real value (from an
     * earlier tentative cluster solve, or already permanent from an even earlier query);
     * this only locks in whatever's already there.
     */
    private static void promoteFaceToPermanent(
        HexMeshBuilder.HexMesh mesh, FaceAdjacency.Adjacency adjacency, int faceIndex, DecidedFaceStore faceStore, DecidedVertexStore vertexStore
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
        for (int[] triangle : adjacency.mutuallyAdjacentFaceTriples(localGroup)) {
            promoteVertexIfAllOwnersPermanent(mesh, triangle, faceStore, vertexStore);
        }
    }

    private static boolean allThreeOwnersPermanent(HexMeshBuilder.HexMesh mesh, int[] triangle, DecidedFaceStore faceStore) {
        for (int member : triangle) {
            if (faceStore.getPermanent(mesh.faces().get(member).axialPos()).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** One mesh-vertex triple's own promotion attempt - the loop body of {@link #promoteFaceToPermanent}. */
    private static void promoteVertexIfAllOwnersPermanent(
        HexMeshBuilder.HexMesh mesh, int[] triangle, DecidedFaceStore faceStore, DecidedVertexStore vertexStore
    ) {
        if (!allThreeOwnersPermanent(mesh, triangle, faceStore)) {
            return;
        }
        VertexKey key = vertexKeyOf(mesh, triangle);
        Optional<Point2D> position = vertexStore.get(key);
        if (position.isEmpty()) {
            // All 3 owning faces NOW globally permanent, but no solve ever had all 3
            // simultaneously in view to COMPUTE this vertex - real, expected gap (could've
            // become permanent via 3 separate, non-overlapping solves), not a bug: "all 3
            // permanent" and "vertex ever computed" are different questions. Skip it - a
            // future solve needing this vertex (mesh reaching all 3 owners at once) will
            // compute and commit it fresh, using their now-permanent real widths.
            return;
        }
        vertexStore.commitPermanent(Map.of(key, position.get()));
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
    private record FastPathMatch(HexMeshBuilder.HexMesh mesh, FaceAdjacency.Adjacency adjacency, int faceIndex, BiomeId biome) {
    }

    /**
     * Heuristic pre-check, no lock needed (pure reads against {@link DecidedFaceStore}/
     * {@link DecidedVertexStore}'s thread-safe stores): builds a SMALL local mesh around
     * {@code (queryX, queryZ)}, checks whether the nearest hex-lattice candidate face
     * (see {@link HexGrid#nearestAxialPos}) OR an immediate mesh-adjacency neighbor has
     * SOME value already (tentative or permanent - either is real, currently-standing,
     * see {@link DecidedFaceStore}'s doc) with all 6 corners also committed, and its REAL
     * displaced boundary actually contains the point.
     *
     * <p>Safe to under-match, never over-matches: a returned value is always a genuine
     * point-in-polygon test against real, already-committed geometry - never a guess.
     * Missing a real match here (e.g. an extreme-width face's true boundary reaches past
     * this method's small ring) only costs an unnecessary cluster solve later, never a
     * wrong answer - see {@link #realizeCluster}'s doc for the AUTHORITATIVE check once a
     * cluster is actually solved.
     */
    private static Optional<FastPathMatch> findFastPathMatch(long worldSeed, double queryX, double queryZ, DecidedFaceStore faceStore, DecidedVertexStore vertexStore) {
        double pad = FAST_PATH_LOOKUP_PAD_BLOCKS;
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(
            HEX_SIZE_BLOCKS, (int) Math.floor(queryX - pad), (int) Math.floor(queryZ - pad), (int) Math.ceil(queryX + pad), (int) Math.ceil(queryZ + pad)
        );
        FaceAdjacency.Adjacency adjacency = FaceAdjacency.of(mesh.faces());

        HexGrid.AxialPos nearest = HexGrid.nearestAxialPos(new Point2D(queryX, queryZ), HEX_SIZE_BLOCKS);
        Optional<Integer> candidateIndex = findFaceIndex(mesh, nearest);
        if (candidateIndex.isEmpty()) {
            return Optional.empty(); // lookup pad too small to contain the nearest candidate - vanishingly unlikely, fall to the slow path
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

    /** See {@link #findFastPathMatch}'s doc - thin, read-only, side-effect-free view of it. */
    public static Optional<BiomeId> tryFastPathLookup(long worldSeed, double queryX, double queryZ, DecidedFaceStore faceStore, DecidedVertexStore vertexStore) {
        return findFastPathMatch(worldSeed, queryX, queryZ, faceStore, vertexStore).map(FastPathMatch::biome);
    }

    /**
     * Called by the caller's own lock (see {@code SeedMeshCache}'s doc) after {@link
     * #tryFastPathLookup} found a match that turned out merely TENTATIVE - promotes that
     * face (and any vertex triples now fully permanent) before the caller hands the
     * answer to a real caller. Safe to call regardless if already permanent - {@link
     * DecidedFaceStore#commitPermanent}'s same-value re-commit is a harmless no-op.
     */
    public static void promoteCoveringFaceToPermanent(long worldSeed, double queryX, double queryZ, DecidedFaceStore faceStore, DecidedVertexStore vertexStore) {
        FastPathMatch match = findFastPathMatch(worldSeed, queryX, queryZ, faceStore, vertexStore).orElseThrow(() -> new IllegalStateException(
            "TriangleFieldGeometryRealization.promoteCoveringFaceToPermanent: no fast-path match found for (" + queryX + "," + queryZ + ") - "
                + "caller must only call this right after tryFastPathLookup found one, real bug if it didn't this time"
        ));
        promoteFaceToPermanent(match.mesh(), match.adjacency(), match.faceIndex(), faceStore, vertexStore);
    }

    /** {@code null}-safe: returns empty if this face has no value yet, isn't fully-cornered, or its real boundary doesn't contain the point - never throws for those ordinary cases. */
    private static Optional<BiomeId> matchIfDecidedAndContains(
        HexMeshBuilder.HexMesh mesh, FaceAdjacency.Adjacency adjacency, int faceIndex, Map<Integer, Point2D> committedPositions,
        DecidedFaceStore faceStore, long worldSeed, double queryX, double queryZ
    ) {
        Optional<DecidedFaceStore.DecidedFace> decided = faceStore.get(mesh.faces().get(faceIndex).axialPos());
        if (decided.isEmpty()) {
            return Optional.empty();
        }
        boolean allCornersCommitted = mesh.faces().get(faceIndex).vertexIndices().stream().allMatch(committedPositions::containsKey);
        if (!allCornersCommitted) {
            return Optional.empty(); // face has a value but not every corner does yet - can't safely build its real boundary
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

    /** One BFS growth attempt's result - see {@link #growCluster}'s doc. */
    private record ClusterGrowth(Set<Integer> cluster, Map<Integer, DecidedFaceStore.DecidedFace> fixedContext) {
    }

    /**
     * BFS over real mesh-adjacency from {@code seedIndex} and its immediate ring. {@code
     * treatTentativeAsFixed} selects which of the two growth attempts {@link
     * #realizeCluster} runs: {@code true} (attempt 1, cheap common case) stops at ANY
     * already-valued face (tentative or permanent), like the old single-tier design;
     * {@code false} (attempt 2, only if attempt 1's CSP solve failed) stops ONLY at
     * PERMANENT faces, pulling every merely-tentative neighbor into the cluster instead -
     * safe to revise, nothing irrevocable has happened yet (see {@link DecidedFaceStore}'s
     * doc). A hull-touching face always stops growth regardless of attempt (ran out of
     * mesh - normal on fresh territory, not an error).
     */
    private static ClusterGrowth growCluster(
        HexMeshBuilder.HexMesh mesh, FaceAdjacency.Adjacency adjacency, int seedIndex, DecidedFaceStore faceStore, boolean treatTentativeAsFixed
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
     * The organic-cluster-growth slow path (see this redesign's plan doc): called under
     * the caller's own single lock (see {@code SeedMeshCache}) after {@link
     * #tryFastPathLookup} found nothing.
     *
     * <p><b>Two-attempt strategy — the direct fix for a real dead-end crash</b> (see this
     * redesign's plan doc for the full incident): attempt 1 ({@link #growCluster} with
     * {@code treatTentativeAsFixed=true}) is cheap, matches almost every query - tentative
     * neighbors treated as ordinary fixed context, same cost as the old single-tier
     * design. If that CSP solve fails, attempt 2 re-grows the SAME seed with {@code
     * treatTentativeAsFixed=false}, pulling every reachable tentative neighbor into the
     * free set instead of treating it as a wall - genuine extra flexibility, nothing about
     * a tentative face is irrevocable yet. Only if attempt 2 ALSO fails (every reachable,
     * still-revisable face jointly has no legal solution, only truly PERMANENT faces as
     * real constraints) does this fail loud - a strictly narrower, rarer trigger than "any
     * already-decided neighbor, however it got that way."
     *
     * <p>Whichever attempt succeeds commits its OWN cluster faces/vertices as TENTATIVE
     * (never directly permanent) - then the ONE face actually covering {@code (queryX,
     * queryZ)} is promoted to PERMANENT via {@link #promoteFaceToPermanent}, since
     * Minecraft is about to use this exact answer to place real terrain. Everything else
     * touched by this solve stays tentative, freely revisable by a LATER query.
     *
     * <p><b>Reaching the mesh's own hull is a NORMAL stopping condition, not an error</b>
     * - found the hard way: on genuinely fresh, never-touched territory (nothing decided
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
        FaceAdjacency.Adjacency adjacency = FaceAdjacency.of(mesh.faces());

        HexGrid.AxialPos seedAxial = HexGrid.nearestAxialPos(new Point2D(queryX, queryZ), HEX_SIZE_BLOCKS);
        int seedIndex = findFaceIndex(mesh, seedAxial).orElseThrow(() -> new IllegalStateException(
            "TriangleFieldGeometryRealization.realizeCluster: growth cap radius " + cap + " blocks too small to even "
                + "contain the seed face itself at (" + queryX + "," + queryZ + ") - should be geometrically impossible, real bug"
        ));

        ClusterGrowth attempt1 = growCluster(mesh, adjacency, seedIndex, faceStore, true);
        Map<Integer, Point2D> alreadyDecidedVertices = alreadyDecidedVerticesOf(mesh, adjacency, vertexStore);

        if (attempt1.cluster().isEmpty()) {
            return resolveFromAlreadyDecidedNeighbors(
                mesh, adjacency, attempt1, alreadyDecidedVertices, worldSeed, queryX, queryZ, seedAxial, faceStore, vertexStore
            );
        }

        double minEdgeLengthBlocks = MIN_EDGE_LENGTH_MACRO_CELLS * gridConfig.macroCellSizeBlocks();
        ClusterSolution solution = solveClusterWithTwoAttempts(
            mesh, adjacency, graph, worldSeed, minEdgeLengthBlocks, alreadyDecidedVertices, faceStore, seedIndex, attempt1, seedAxial
        );
        ClusterGrowth used = solution.growth();
        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments = solution.assignments();

        Map<Integer, BiomeId> fixedFaceBiomes = new LinkedHashMap<>();
        Map<Integer, Integer> committedWidthBlocksByFace = new LinkedHashMap<>();
        used.fixedContext().forEach((faceIndex, decided) -> {
            fixedFaceBiomes.put(faceIndex, decided.biomeId());
            committedWidthBlocksByFace.put(faceIndex, decided.widthBlocks());
        });

        // Sequential, one-face-at-a-time geometry commitment - a PERMANENT design principle
        // for this project (not a tentative numbers-driven choice, not casually revisitable):
        // no two faces ever negotiate size simultaneously, full stop. See DECISIONS.md
        // 2026-09-11 "Sequential, one-face-at-a-time geometry commitment" entry for the real
        // measured cost and why it's kept anyway.
        Map<Integer, Point2D> displacedVertices = WeightedVertexPlacement.sequentiallyRealizedVertexPositions(
            mesh, adjacency, assignments, HEX_SIZE_BLOCKS, minEdgeLengthBlocks, committedWidthBlocksByFace, alreadyDecidedVertices, worldSeed
        );

        commitClusterFacesTentatively(mesh, assignments, used.cluster(), faceStore);
        commitClusterVerticesTentatively(mesh, adjacency, used.cluster(), fixedFaceBiomes, alreadyDecidedVertices, displacedVertices, vertexStore);

        return resolveCoveringFace(
            mesh, adjacency, used, assignments, displacedVertices, worldSeed, queryX, queryZ, seedAxial, faceStore, vertexStore
        );
    }

    /**
     * Every candidate reachable from the seed's ring already has a value - the fast-path
     * pre-check's narrower ring apparently missed a real match. Checks directly against
     * this method's more generous mesh before giving up.
     */
    private static BiomeId resolveFromAlreadyDecidedNeighbors(
        HexMeshBuilder.HexMesh mesh, FaceAdjacency.Adjacency adjacency, ClusterGrowth attempt1, Map<Integer, Point2D> alreadyDecidedVertices,
        long worldSeed, double queryX, double queryZ, HexGrid.AxialPos seedAxial, DecidedFaceStore faceStore, DecidedVertexStore vertexStore
    ) {
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

    /** One successful attempt's growth and the identity assignment it solved - see {@link #solveClusterWithTwoAttempts}. */
    private record ClusterSolution(ClusterGrowth growth, Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments) {
    }

    /** The two-attempt strategy - see {@link #realizeCluster}'s doc for the full "no tug-of-war" incident this fixes. */
    private static ClusterSolution solveClusterWithTwoAttempts(
        HexMeshBuilder.HexMesh mesh, FaceAdjacency.Adjacency adjacency, BiomeGraph graph, long worldSeed, double minEdgeLengthBlocks,
        Map<Integer, Point2D> alreadyDecidedVertices, DecidedFaceStore faceStore, int seedIndex, ClusterGrowth attempt1, HexGrid.AxialPos seedAxial
    ) {
        Optional<Map<Integer, TriangleIdentitySizeAssignment.Assignment>> solved = trySolveCluster(attempt1, adjacency, graph, worldSeed, mesh, minEdgeLengthBlocks, alreadyDecidedVertices);
        if (solved.isPresent()) {
            return new ClusterSolution(attempt1, solved.get());
        }
        ClusterGrowth attempt2 = growCluster(mesh, adjacency, seedIndex, faceStore, false);
        solved = trySolveCluster(attempt2, adjacency, graph, worldSeed, mesh, minEdgeLengthBlocks, alreadyDecidedVertices);
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
        return new ClusterSolution(attempt2, solved.get());
    }

    /**
     * Finds whichever face actually covers the query point - the just-solved CLUSTER
     * first (fresh geometry), then the fixed CONTEXT it was solved against - promotes it
     * to permanent, and returns its biome.
     */
    private static BiomeId resolveCoveringFace(
        HexMeshBuilder.HexMesh mesh, FaceAdjacency.Adjacency adjacency, ClusterGrowth used,
        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments, Map<Integer, Point2D> displacedVertices,
        long worldSeed, double queryX, double queryZ, HexGrid.AxialPos seedAxial, DecidedFaceStore faceStore, DecidedVertexStore vertexStore
    ) {
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

    /**
     * Threads real committed neighbor WIDTHS into {@link
     * TriangleIdentitySizeAssignment#solve}'s mesh-aware, achievable-range-computing
     * overload ("compute achievable range first, decide second"). See DECISIONS.md
     * 2026-09-11 "Committed-width threading attempt rejected" entry for why this
     * mechanism (compute WHAT'S ACHIEVABLE, hard-exclude/narrow from there) was used
     * instead of an earlier, rejected target-biasing attempt.
     *
     * <p>ALSO threads real, already-decided VERTEX positions through (closing the gap the
     * committed-widths-only version left open - see {@code TriangleIdentitySizeAssignment}'s
     * doc on its {@code committedVertexPositions} parameter): a face fully boxed in by
     * already-decided neighbors has zero real geometric freedom left by its own turn,
     * which a committed-WIDTH-only achievable-range estimate can't see - makes that
     * estimate reflect the SAME real, immovable corners {@link #realizeCluster} itself
     * will treat as fixed anchors.
     */
    private static Optional<Map<Integer, TriangleIdentitySizeAssignment.Assignment>> trySolveCluster(
        ClusterGrowth growth, FaceAdjacency.Adjacency adjacency, BiomeGraph graph, long worldSeed,
        HexMeshBuilder.HexMesh mesh, double minEdgeLengthBlocks, Map<Integer, Point2D> alreadyDecidedVertices
    ) {
        Map<Integer, BiomeId> fixedFaceBiomes = new LinkedHashMap<>();
        Map<Integer, Integer> committedWidthBlocksByFace = new LinkedHashMap<>();
        growth.fixedContext().forEach((faceIndex, decided) -> {
            fixedFaceBiomes.put(faceIndex, decided.biomeId());
            committedWidthBlocksByFace.put(faceIndex, decided.widthBlocks());
        });
        double clusterAreaBlocks2 = growth.cluster().size() * baselineFaceAreaBlocks2();
        return TriangleIdentitySizeAssignment.solve(
            adjacency, growth.cluster(), fixedFaceBiomes, graph, worldSeed, clusterAreaBlocks2, committedWidthBlocksByFace,
            mesh, HEX_SIZE_BLOCKS, minEdgeLengthBlocks, alreadyDecidedVertices
        );
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

    /** {@code bounds} padded by {@link #TRIANGULATION_MARGIN_BLOCKS} on every side - the margin guarantees every face touching the actual region is non-hull-touching (real neighbor context for the CSP's legality solving). */
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

    /** Every face that's neither hull-touching (missing real neighbor context, see {@link FaceAdjacency}'s doc) nor already fixed by a neighbor region's solve - the CSP only ever assigns these. */
    private static Set<Integer> freeFaceIndices(int faceCount, FaceAdjacency.Adjacency adjacency, Map<Integer, BiomeId> fixedFaceBiomes) {
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
     * this module's build.gradle doc: staying pure Java keeps tests fast plain JUnit, not
     * NeoForge's "unit testing mode" booting a whole Minecraft instance), so no separate
     * logger call to fall back on - whatever isn't in THIS string is gone once the crash
     * report closes. See DECISIONS.md 2026-09-04 "Dead-end exceptions enriched" entry for
     * the real incident that motivated this.
     */
    private static Map<Integer, TriangleIdentitySizeAssignment.Assignment> solveIdentities(
        FaceAdjacency.Adjacency adjacency, Set<Integer> freeFaceIndices, Map<Integer, BiomeId> fixedFaceBiomes,
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
     * Any free face whose centroid falls inside an already-solved neighbor region is
     * fixed to that neighbor's already-committed biome at the containing cell, sourced
     * from {@link RegionSolveInput#fixedBoundaryCells()} - see this class's doc for why
     * this closes the cross-region disagreement gap using {@link
     * TriangleIdentitySizeAssignment}'s existing fixed-context mechanism, no changes
     * needed inside that class. A face whose centroid finds no exact entry (neighbor
     * genuinely not solved yet, or still within this region's own legitimate territory)
     * is correctly left unfixed - no ground truth to pin it to.
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
