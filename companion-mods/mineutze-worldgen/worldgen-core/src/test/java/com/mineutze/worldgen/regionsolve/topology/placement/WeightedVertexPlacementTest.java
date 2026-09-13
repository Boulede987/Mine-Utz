package com.mineutze.worldgen.regionsolve.topology.placement;

import com.mineutze.worldgen.regionsolve.topology.Point2D;

import com.mineutze.worldgen.regionsolve.topology.mesh.HexMeshBuilder;

import com.mineutze.worldgen.regionsolve.topology.identity.TriangleIdentitySizeAssignment;

import com.mineutze.worldgen.regionsolve.topology.mesh.FaceAdjacency;

import com.mineutze.worldgen.regionsolve.topology.mesh.HexFace;

import com.mineutze.worldgen.biomegraph.BiomeId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeightedVertexPlacementTest {

    private static final double SIZE = 200;
    private static final BiomeId BIOME = new BiomeId("test", "a");

    /**
     * Class doc's central claim, checked directly rather than trusted: when every face
     * gets IDENTICAL weight (uniform target width matching mesh's raw circumradius
     * exactly - real hexagon's side length equals circumradius, so width = 2 x SIZE
     * reproduces undisplaced footprint exactly), radical center of every mesh-vertex
     * triple must reduce to ordinary circumcenter of 3 face centers - which, for
     * regular hex lattice, IS original undisplaced vertex. Makes power-diagram
     * construction provably subsume old "nothing to correct" baseline, not just
     * approximate it.
     */
    @Test
    void uniformWeightsReproduceTheOriginalUndisplacedVertices() {
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(SIZE, 0, 0, 3000, 3000);
        FaceAdjacency.Adjacency adjacency = FaceAdjacency.of(mesh.faces());
        Map<Integer, TriangleIdentitySizeAssignment.Assignment> uniformAssignments = uniformAssignments(mesh, adjacency, (int) (2 * SIZE));

        Map<Integer, Point2D> displaced = WeightedVertexPlacement.displacedVertexPositions(mesh, adjacency, uniformAssignments, SIZE, 0.0);
        assertTrue(displaced.size() > 10, "expected a real number of resolved mesh vertices, got " + displaced.size());

        for (Map.Entry<Integer, Point2D> entry : displaced.entrySet()) {
            Point2D original = mesh.vertices().get(entry.getKey());
            Point2D computed = entry.getValue();
            assertEquals(original.x(), computed.x(), 1e-6, "vertex " + entry.getKey() + " x drifted with uniform weights");
            assertEquals(original.z(), computed.z(), 1e-6, "vertex " + entry.getKey() + " z drifted with uniform weights");
        }
    }

    /**
     * Whole point of switching to power diagram: face targeting much bigger width than
     * neighbors must end up with MORE territory, meaning boundary vertices sit FARTHER
     * from center than undisplaced baseline - opposite of old per-edge bias formula,
     * which produced exactly zero correction whenever neighbors didn't happen to want
     * opposite direction.
     */
    @Test
    void aFaceTargetingMuchMoreAreaPushesItsBoundaryFartherFromItsOwnCenter() {
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(SIZE, 0, 0, 3000, 3000);
        FaceAdjacency.Adjacency adjacency = FaceAdjacency.of(mesh.faces());
        int[] triangle = anyRealTriangle(mesh, adjacency);
        int bigFace = triangle[0];

        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments = uniformAssignments(mesh, adjacency, (int) (2 * SIZE));
        assignments.put(bigFace, new TriangleIdentitySizeAssignment.Assignment(BIOME, (int) (2 * SIZE * 3)));

        Map<Integer, Point2D> displaced = WeightedVertexPlacement.displacedVertexPositions(mesh, adjacency, assignments, SIZE, 0.0);
        Point2D bigFaceCenter = centroidOf(mesh, bigFace);

        boolean foundARealShift = false;
        for (int vertexIndex : mesh.faces().get(bigFace).vertexIndices()) {
            Point2D original = mesh.vertices().get(vertexIndex);
            Point2D computed = displaced.get(vertexIndex);
            if (computed == null) {
                continue; // outer-hull vertex with no real mesh-vertex triple, not this test's concern
            }
            double originalDistance = distance(bigFaceCenter, original);
            double newDistance = distance(bigFaceCenter, computed);
            if (newDistance > originalDistance + 1e-6) {
                foundARealShift = true;
            }
        }
        assertTrue(foundARealShift, "expected at least one of the oversized face's own boundary vertices to move farther from its own center");
    }

    /**
     * Real property adaptive clamp exists to guarantee, checked directly rather than by
     * proxy: under extreme weight mismatch, EVERY face in mesh (not just extreme one -
     * neighbor pulled hard by it could fold too) must still be simple polygon (no
     * self-intersection), no matter how large requested displacement. Replaces older,
     * weaker version of this test that only checked DISTANCE against fixed threshold -
     * proxy for simplicity, not simplicity itself, since vertex can stay well within
     * any distance bound and still cross adjacent edge if enough OTHER corners also
     * moved.
     */
    @Test
    void extremeWeightMismatchNeverProducesASelfIntersectingFace() {
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(SIZE, 0, 0, 3000, 3000);
        FaceAdjacency.Adjacency adjacency = FaceAdjacency.of(mesh.faces());
        int[] triangle = anyRealTriangle(mesh, adjacency);

        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments = uniformAssignments(mesh, adjacency, (int) (2 * SIZE));
        assignments.put(triangle[0], new TriangleIdentitySizeAssignment.Assignment(BIOME, 100_000));
        assignments.put(triangle[1], new TriangleIdentitySizeAssignment.Assignment(BIOME, 1));

        Map<Integer, Point2D> displaced = WeightedVertexPlacement.displacedVertexPositions(mesh, adjacency, assignments, SIZE, 0.0);

        for (int faceIndex = 0; faceIndex < mesh.faces().size(); faceIndex++) {
            List<Point2D> corners = new ArrayList<>();
            for (int vertexIndex : mesh.faces().get(faceIndex).vertexIndices()) {
                corners.add(displaced.getOrDefault(vertexIndex, mesh.vertices().get(vertexIndex)));
            }
            assertTrue(isPolygonSimple(corners), "face " + faceIndex + " became a self-intersecting polygon under an extreme weight mismatch");
        }
    }

    /**
     * Exact case that used to plateau under old flat 0.4/0.8 displacement clamp: face
     * targeting roughly half its 6 uniform neighbors' width (ratio of real catalog's
     * single most extreme tier-1 mismatch). Under flat clamp this stuck at ratio 1.30
     * regardless of calibration rounds - proven geometry-clamp limit, not iteration
     * limit, by fact 15 and 60 rounds gave byte-identical results. Adaptive per-face
     * clamp removes that artificial ceiling: only limits face as far as ITS OWN real
     * geometry requires, so same case now converges close to target instead of
     * plateauing.
     */
    @Test
    void theCatalogsMostExtremeSizeMismatchNoLongerPlateausUnderTheAdaptiveClamp() {
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(SIZE, 0, 0, 3000, 3000);
        FaceAdjacency.Adjacency adjacency = FaceAdjacency.of(mesh.faces());
        int center = aFaceWithSixFullyInteriorNeighbors(mesh, adjacency);

        int baselineWidth = (int) (2 * SIZE);
        int targetWidth = (int) Math.round(baselineWidth * (300.0 / 650.0)); // same ratio as catalog's most extreme real case
        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments = new HashMap<>();
        assignments.put(center, new TriangleIdentitySizeAssignment.Assignment(BIOME, targetWidth));
        for (int neighbor : adjacency.neighborFaceIndices().get(center)) {
            assignments.put(neighbor, new TriangleIdentitySizeAssignment.Assignment(BIOME, baselineWidth));
        }

        Map<Integer, Point2D> displaced = WeightedVertexPlacement.displacedVertexPositions(mesh, adjacency, assignments, SIZE, 0.0);
        double realizedWidth = realizedWidthOf(mesh, displaced, center);

        assertEquals(targetWidth, realizedWidth, targetWidth * 0.10, "expected the adaptive clamp to close the old plateau (was stuck at ratio 1.30) to within 10% of target");
    }

    /**
     * Exact regression this test exists to prevent: EARLIER version of {@link
     * #calibratedWeights} used MULTIPLICATIVE update ({@code newWeight = oldWeight *
     * ratio}), which measurably failed - {@link #radicalCenter}'s formula depends on
     * DIFFERENCE between two weights, never their ratio, so multiplying every face's
     * weight by similar factor barely moves diagram even as weight value collapses
     * toward zero (confirmed via real round-by-round trace at time: weight fell from
     * 22500 to 39 over 15 rounds while realized area stayed almost flat). Fix (additive
     * correction) must bring moderate mismatch essentially exactly onto target, not
     * just closer than uncalibrated one-shot guess.
     */
    @Test
    void calibrationConvergesCloseToTargetForAModerateMismatch() {
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(SIZE, 0, 0, 3000, 3000);
        FaceAdjacency.Adjacency adjacency = FaceAdjacency.of(mesh.faces());
        int center = aFaceWithSixFullyInteriorNeighbors(mesh, adjacency);

        int baselineWidth = (int) (2 * SIZE);
        int targetWidth = (int) (baselineWidth * 0.7); // moderate mismatch, well short of catalog's most extreme cases
        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments = new HashMap<>();
        assignments.put(center, new TriangleIdentitySizeAssignment.Assignment(BIOME, targetWidth));
        for (int neighbor : adjacency.neighborFaceIndices().get(center)) {
            assignments.put(neighbor, new TriangleIdentitySizeAssignment.Assignment(BIOME, baselineWidth));
        }

        Map<Integer, Point2D> displaced = WeightedVertexPlacement.displacedVertexPositions(mesh, adjacency, assignments, SIZE, 0.0);
        double realizedWidth = realizedWidthOf(mesh, displaced, center);

        assertEquals(targetWidth, realizedWidth, targetWidth * 0.05, "expected calibration to converge within 5% of target for a moderate mismatch");
    }

    /**
     * Direct fix for cross-region seam bug (see {@code DecidedVertexStore}'s doc): face
     * with no {@code assignments} entry but real committed width behaves EXACTLY like
     * face that DOES have fresh {@code assignments} entry of same width, for placement
     * purposes - not like unassigned/baseline face. Same moderate-mismatch setup as
     * {@link #calibrationConvergesCloseToTargetForAModerateMismatch}, but 6 ring
     * neighbors' widths arrive via {@code committedWidthBlocksByFace} instead of
     * {@code assignments} - realized center width must land within same tolerance,
     * proving committed-width path isn't silently falling back to baseline.
     */
    @Test
    void committedFaceWidthIsUsedInPlaceOfBaselineWeight() {
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(SIZE, 0, 0, 3000, 3000);
        FaceAdjacency.Adjacency adjacency = FaceAdjacency.of(mesh.faces());
        int center = aFaceWithSixFullyInteriorNeighbors(mesh, adjacency);

        int baselineWidth = (int) (2 * SIZE);
        int targetWidth = (int) (baselineWidth * 0.7);
        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments = new HashMap<>();
        assignments.put(center, new TriangleIdentitySizeAssignment.Assignment(BIOME, targetWidth));
        Map<Integer, Integer> committedWidthBlocksByFace = new HashMap<>();
        for (int neighbor : adjacency.neighborFaceIndices().get(center)) {
            committedWidthBlocksByFace.put(neighbor, baselineWidth);
        }

        Map<Integer, Point2D> displaced = WeightedVertexPlacement.displacedVertexPositions(
            mesh, adjacency, assignments, SIZE, 0.0, committedWidthBlocksByFace, Map.of()
        );
        double realizedWidth = realizedWidthOf(mesh, displaced, center);

        assertEquals(targetWidth, realizedWidth, targetWidth * 0.05, "expected a committed neighbor width to drive placement the same as a fresh assignment would");
    }

    /**
     * Direct fix for real live-path finding (2026-09-11,
     * `SizeFidelityLivePathMeasurement`): face pinned tight by ONE already-committed
     * neighbor still had weight pushed up by calibration trying to reach its target,
     * and ballooned on OTHER, open sides well past what target justified, since nothing
     * on top end was ever tied to target the way {@code
     * MIN_REALIZED_AREA_FRACTION_OF_TARGET} already is on bottom end. One neighbor
     * committed to small width (real, immovable resistance); other 5 deliberately left
     * unassigned (today's baseline placeholder - "open side"). Center wants noticeably
     * more than baseline; without ceiling this would run well past it on open sides -
     * with it, realized width must stay within same {@code
     * MAX_REALIZED_AREA_FRACTION_OF_TARGET} bound (recomputed here from same formula,
     * since that constant is private to production class).
     */
    @Test
    void aFaceWithOneTightCommittedNeighborStaysWithinItsOwnRealizedAreaCeiling() {
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(SIZE, 0, 0, 3000, 3000);
        FaceAdjacency.Adjacency adjacency = FaceAdjacency.of(mesh.faces());
        int center = aFaceWithSixFullyInteriorNeighbors(mesh, adjacency);

        int baselineWidth = (int) (2 * SIZE);
        int targetWidth = (int) (baselineWidth * 1.5); // wants noticeably more than baseline
        List<Integer> neighbors = new ArrayList<>(adjacency.neighborFaceIndices().get(center));
        int tightNeighbor = neighbors.get(0);

        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments = new HashMap<>();
        assignments.put(center, new TriangleIdentitySizeAssignment.Assignment(BIOME, targetWidth));
        Map<Integer, Integer> committedWidthBlocksByFace = new HashMap<>();
        committedWidthBlocksByFace.put(tightNeighbor, (int) (baselineWidth * 0.3)); // tight, real, immovable resistance

        Map<Integer, Point2D> displaced = WeightedVertexPlacement.displacedVertexPositions(
            mesh, adjacency, assignments, SIZE, 0.0, committedWidthBlocksByFace, Map.of()
        );
        double realizedWidth = realizedWidthOf(mesh, displaced, center);

        double targetArea = (3.0 * Math.sqrt(3.0) / 2.0) * (targetWidth / 2.0) * (targetWidth / 2.0);
        double baselineArea = (3.0 * Math.sqrt(3.0) / 2.0) * SIZE * SIZE;
        double ceilingArea = Math.max(targetArea * 1.3, baselineArea); // mirrors MAX_REALIZED_AREA_FRACTION_OF_TARGET
        double ceilingWidth = 2.0 * Math.sqrt(ceilingArea / (3.0 * Math.sqrt(3.0) / 2.0));

        assertTrue(realizedWidth <= ceilingWidth + 1.0, "expected the ceiling to cap realized width near " + ceilingWidth + ", got " + realizedWidth);
    }

    /**
     * Direct capability "compute achievable range first, decide second" design
     * (2026-09-11) needs. Real, important correction found BEFORE this was ever tested
     * against real data: earlier version of {@code achievableAreaRangeFor} probed at
     * astronomically large weight (looking for absolute physical ceiling) - but once
     * probe weight is large enough, it wins weight-DIFFERENCE comparison against ANY
     * real, finite neighbor weight by nearly same margin, so result silently collapsed
     * to SAME constant (absolute displacement cap) regardless of whether neighbors were
     * tight or generous - opposite of what "achievable range" is supposed to capture.
     * Fixed by probing at REAL, catalog-scale widths instead - this test proves fix
     * actually works: neighbors committed to SAME width as probe's high end (real,
     * equal-strength competitor) must produce meaningfully LOWER achievable ceiling
     * than fully-open (baseline-weighted) neighbors, since equal-weight competitor
     * concedes nothing (radicalCenter reduces to plain circumcenter, i.e. no growth)
     * while weaker baseline neighbor concedes real territory.
     */
    @Test
    void achievableAreaRangeReflectsRealNeighborResistanceNotAnArbitraryConstant() {
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(SIZE, 0, 0, 3000, 3000);
        FaceAdjacency.Adjacency adjacency = FaceAdjacency.of(mesh.faces());
        int center = aFaceWithSixFullyInteriorNeighbors(mesh, adjacency);

        int probeMinWidth = 100;
        int probeMaxWidth = 800; // well above baselineWidth (2*SIZE=400), so it's real, distinguishing probe
        Map<Integer, Integer> strongNeighbors = new HashMap<>();
        for (int neighbor : adjacency.neighborFaceIndices().get(center)) {
            strongNeighbors.put(neighbor, probeMaxWidth); // equal-strength competitor on every side
        }

        WeightedVertexPlacement.AreaRange constrained = WeightedVertexPlacement.achievableAreaRangeFor(
            mesh, adjacency, center, strongNeighbors, Map.of(), SIZE, 0.0, probeMinWidth, probeMaxWidth
        );
        WeightedVertexPlacement.AreaRange open = WeightedVertexPlacement.achievableAreaRangeFor(
            mesh, adjacency, center, Map.of(), Map.of(), SIZE, 0.0, probeMinWidth, probeMaxWidth
        );

        assertTrue(constrained.minAreaBlocks2() <= constrained.maxAreaBlocks2(), "expected a real, non-inverted range");
        assertTrue(constrained.maxAreaBlocks2() < open.maxAreaBlocks2(),
            "expected real, equal-strength neighbor resistance to produce a meaningfully lower ceiling than fully-open baseline neighbors - "
                + "constrained=" + constrained.maxAreaBlocks2() + ", open=" + open.maxAreaBlocks2());
    }

    /**
     * Direct check of real fix for face fully boxed in by already-decided neighbors
     * (found via live-path legal-size test - see {@code
     * ModBiomeGraphTest.overworldLivePathRealizedWidthNeverBreachesItsOwnBiomesLegalSizeRange}'s
     * doc): {@code committedWidthBlocksByFace} alone assumes every committed neighbor's
     * shape can be freely RECOMPUTED from width, but neighbor's TRUE final shape can
     * already be pinned tighter than recompute would ever produce (real corners, locked
     * by ITS OTHER neighbors' earlier context, not visible to fresh width-only
     * recompute here). Hand-pinned corner - deliberately placed far closer to {@code
     * center}'s centroid than any real recompute from plain baseline-width neighbor
     * would land - proves {@code committedVertexPositions} genuinely changes estimate,
     * not just in theory: ceiling with pinned corner must be measurably LOWER than
     * width-only estimate, since that one corner now physically cannot move outward no
     * matter how high probe weight goes.
     */
    @Test
    void achievableAreaRangeIsNarrowerWhenARealNeighborCornerIsAlreadyPinnedTighterThanItsWidthImplies() {
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(SIZE, 0, 0, 3000, 3000);
        FaceAdjacency.Adjacency adjacency = FaceAdjacency.of(mesh.faces());
        int center = aFaceWithSixFullyInteriorNeighbors(mesh, adjacency);

        int probeMinWidth = 100;
        int probeMaxWidth = 800;
        int baselineWidth = (int) (2 * SIZE);
        Map<Integer, Integer> committedWidths = new HashMap<>();
        for (int neighbor : adjacency.neighborFaceIndices().get(center)) {
            committedWidths.put(neighbor, baselineWidth); // ordinary neighbors, nothing tight by width alone
        }

        int pinnedVertex = mesh.faces().get(center).vertexIndices().get(0);
        Point2D centerCentroid = centroidOf(mesh, center);
        Point2D rawCorner = mesh.vertices().get(pinnedVertex);
        // 5% of way from center's centroid to raw corner - deliberately far tighter
        // than any width-only recompute of ordinary baseline neighbor would ever place
        // it, simulating corner already locked by real context this probe can't see.
        Point2D pinnedPosition = new Point2D(
            centerCentroid.x() + (rawCorner.x() - centerCentroid.x()) * 0.05,
            centerCentroid.z() + (rawCorner.z() - centerCentroid.z()) * 0.05
        );

        WeightedVertexPlacement.AreaRange withoutVertexInfo = WeightedVertexPlacement.achievableAreaRangeFor(
            mesh, adjacency, center, committedWidths, Map.of(), SIZE, 0.0, probeMinWidth, probeMaxWidth
        );
        WeightedVertexPlacement.AreaRange withPinnedCorner = WeightedVertexPlacement.achievableAreaRangeFor(
            mesh, adjacency, center, committedWidths, Map.of(pinnedVertex, pinnedPosition), SIZE, 0.0, probeMinWidth, probeMaxWidth
        );

        assertTrue(withPinnedCorner.maxAreaBlocks2() < withoutVertexInfo.maxAreaBlocks2(),
            "expected a real pinned-tight committed corner to meaningfully lower the achievable ceiling vs a width-only estimate - "
                + "withPinnedCorner=" + withPinnedCorner.maxAreaBlocks2() + ", withoutVertexInfo=" + withoutVertexInfo.maxAreaBlocks2());
    }

    /**
     * Real precision gain sequential mechanism (2026-09-11) exists for: direct binary
     * search against fully-known, fixed context should land MUCH closer to target than
     * old joint calibration's established 5% tolerance ({@link
     * #calibrationConvergesCloseToTargetForAModerateMismatch}) - same moderate-mismatch
     * scenario, same numbers, different mechanism.
     */
    @Test
    void sequentialResolutionConvergesTighterThanCalibration() {
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(SIZE, 0, 0, 3000, 3000);
        FaceAdjacency.Adjacency adjacency = FaceAdjacency.of(mesh.faces());
        int center = aFaceWithSixFullyInteriorNeighbors(mesh, adjacency);

        int baselineWidth = (int) (2 * SIZE);
        int targetWidth = (int) (baselineWidth * 0.7);
        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments = new HashMap<>();
        assignments.put(center, new TriangleIdentitySizeAssignment.Assignment(BIOME, targetWidth));
        Map<Integer, Integer> committedWidthBlocksByFace = new HashMap<>();
        for (int neighbor : adjacency.neighborFaceIndices().get(center)) {
            committedWidthBlocksByFace.put(neighbor, baselineWidth);
        }

        Map<Integer, Point2D> displaced = WeightedVertexPlacement.sequentiallyRealizedVertexPositions(
            mesh, adjacency, assignments, SIZE, 0.0, committedWidthBlocksByFace, Map.of(), 7L
        );
        double realizedWidth = realizedWidthOf(mesh, displaced, center);

        assertEquals(targetWidth, realizedWidth, targetWidth * 0.01,
            "expected sequential resolution to converge within 1% of target - much tighter than calibration's own 5%");
    }

    /**
     * Direct, isolated check of absolute legal-range clamp (added after real live-path
     * test caught face realizing outside its biome's declared range - see {@code
     * ModBiomeGraphTest.overworldLivePathRealizedWidthNeverBreachesItsOwnBiomesLegalSizeRange}).
     * Face whose TARGET is undersized must still realize at least its {@code
     * legalMinWidthBlocks}, not just {@code MIN_REALIZED_AREA_FRACTION_OF_TARGET} of
     * its (already too-small) target - whole point of absolute, target-INDEPENDENT
     * floor.
     */
    @Test
    void realizedWidthNeverDropsBelowAssignmentsOwnAbsoluteLegalFloor() {
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(SIZE, 0, 0, 3000, 3000);
        FaceAdjacency.Adjacency adjacency = FaceAdjacency.of(mesh.faces());
        int center = aFaceWithSixFullyInteriorNeighbors(mesh, adjacency);

        int baselineWidth = (int) (2 * SIZE);
        int targetWidth = (int) (baselineWidth * 0.5); // deliberately undersized target
        int legalMinWidthBlocks = (int) (baselineWidth * 0.9); // real absolute floor ABOVE target, still below baseline
        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments = new HashMap<>();
        assignments.put(center, new TriangleIdentitySizeAssignment.Assignment(BIOME, targetWidth, legalMinWidthBlocks, Integer.MAX_VALUE));
        Map<Integer, Integer> committedWidthBlocksByFace = new HashMap<>();
        for (int neighbor : adjacency.neighborFaceIndices().get(center)) {
            committedWidthBlocksByFace.put(neighbor, baselineWidth);
        }

        Map<Integer, Point2D> displaced = WeightedVertexPlacement.sequentiallyRealizedVertexPositions(
            mesh, adjacency, assignments, SIZE, 0.0, committedWidthBlocksByFace, Map.of(), 7L
        );
        double realizedWidth = realizedWidthOf(mesh, displaced, center);

        assertTrue(realizedWidth >= legalMinWidthBlocks * 0.99,
            "expected the absolute legal floor to override the undersized target - realized=" + realizedWidth + ", legalMin=" + legalMinWidthBlocks);
    }

    /**
     * Actual "no tug-of-war" guarantee, checked directly rather than just asserted in
     * prose. Real, important subtlety found WHILE building this test, not assumed:
     * face's resolved WEIGHT is single scalar governing ALL 6 of its corners at once -
     * if "later" face happens to actually process BEFORE "earlier" one, "earlier"
     * face's weight legitimately, correctly incorporates "later" one - NOT a bug,
     * exactly how strictly-ordered (never circular) dependency is supposed to behave.
     * Earlier attempt at this test tried to FORCE specific face to process first via
     * indirect "give it one more fixed neighbor" trick - broke, because greedy order is
     * ADAPTIVE: processing third face can retroactively raise second face's
     * solved-neighbor count mid-pass, silently erasing intended margin. Fixed by not
     * predicting order at all - {@link WeightedVertexPlacement#processingOrderFor}
     * called DIRECTLY (package-visible for exactly this reason) to find REAL
     * first-processed face, then corner of THAT face not shared with second one
     * (checked to avoid OTHER real, documented property - SHARED corner can still see
     * legitimate last-mile refinement from whichever of its 3 owners processes last)
     * must be byte-identical whether second face's target is 300 or 900.
     */
    @Test
    void aFacesOwnCornerIsCompletelyIndependentOfALaterProcessedFacesOwnTarget() {
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(SIZE, 0, 0, 3000, 3000);
        FaceAdjacency.Adjacency adjacency = FaceAdjacency.of(mesh.faces());
        int faceA = aFaceWithSixFullyInteriorNeighbors(mesh, adjacency);
        // .sorted(), not just new ArrayList<> - FaceAdjacency.of's Adjacency wraps
        // neighborFaceIndices in Map.copyOf/Set.copyOf, whose iteration order is
        // deliberately randomized PER JVM RUN (same established codebase-wide gotcha
        // documented elsewhere, e.g. BiomeGraph.nodes()) - unsorted pick here would
        // choose DIFFERENT actual face between separate JVM launches.
        int faceB = adjacency.neighborFaceIndices().get(faceA).stream().sorted().toList().get(0);

        int baselineWidth = (int) (2 * SIZE);
        Map<Integer, Integer> committedWidthBlocksByFace = new HashMap<>();
        for (int neighbor : adjacency.neighborFaceIndices().get(faceA)) {
            if (neighbor != faceB) {
                committedWidthBlocksByFace.put(neighbor, baselineWidth);
            }
        }
        for (int neighbor : adjacency.neighborFaceIndices().get(faceB)) {
            if (neighbor != faceA) {
                committedWidthBlocksByFace.put(neighbor, baselineWidth);
            }
        }

        Set<Integer> freeFaces = Set.of(faceA, faceB);
        Set<Integer> initiallySolved = new HashSet<>();
        for (int faceIndex = 0; faceIndex < mesh.faces().size(); faceIndex++) {
            if (!freeFaces.contains(faceIndex)) {
                initiallySolved.add(faceIndex);
            }
        }
        List<Integer> order = WeightedVertexPlacement.processingOrderFor(freeFaces, adjacency, initiallySolved, 7L);
        int first = order.get(0);
        int second = order.get(1);

        Set<Integer> firstOnlyCorners = new HashSet<>(mesh.faces().get(first).vertexIndices());
        firstOnlyCorners.removeAll(mesh.faces().get(second).vertexIndices());
        assertTrue(!firstOnlyCorners.isEmpty(), "expected the first-processed face to have at least one corner not shared with the second");
        int independentVertex = firstOnlyCorners.iterator().next();

        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignmentsRun1 = new HashMap<>();
        assignmentsRun1.put(first, new TriangleIdentitySizeAssignment.Assignment(BIOME, (int) (baselineWidth * 1.3)));
        assignmentsRun1.put(second, new TriangleIdentitySizeAssignment.Assignment(BIOME, 300));

        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignmentsRun2 = new HashMap<>();
        assignmentsRun2.put(first, new TriangleIdentitySizeAssignment.Assignment(BIOME, (int) (baselineWidth * 1.3)));
        assignmentsRun2.put(second, new TriangleIdentitySizeAssignment.Assignment(BIOME, 900));

        Map<Integer, Point2D> displacedRun1 = WeightedVertexPlacement.sequentiallyRealizedVertexPositions(
            mesh, adjacency, assignmentsRun1, SIZE, 0.0, committedWidthBlocksByFace, Map.of(), 7L
        );
        Map<Integer, Point2D> displacedRun2 = WeightedVertexPlacement.sequentiallyRealizedVertexPositions(
            mesh, adjacency, assignmentsRun2, SIZE, 0.0, committedWidthBlocksByFace, Map.of(), 7L
        );

        Point2D positionRun1 = displacedRun1.get(independentVertex);
        Point2D positionRun2 = displacedRun2.get(independentVertex);
        assertEquals(positionRun1.x(), positionRun2.x(), 1e-9, "expected the first-processed face's own non-shared corner x to be untouched by the second face's target");
        assertEquals(positionRun1.z(), positionRun2.z(), 1e-9, "expected the first-processed face's own non-shared corner z to be untouched by the second face's target");
    }

    /**
     * Other half of seam fix: vertex already present in {@code committedVertexPositions}
     * must come back EXACTLY as given, completely unaffected by otherwise-extreme
     * surrounding weight mismatch that would normally clamp/reshrink it - immovable
     * anchor, not just another candidate to validate.
     */
    @Test
    void committedVertexPositionIsNeverAlteredRegardlessOfSurroundingWeights() {
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(SIZE, 0, 0, 3000, 3000);
        FaceAdjacency.Adjacency adjacency = FaceAdjacency.of(mesh.faces());
        int[] triangle = anyRealTriangle(mesh, adjacency);
        int sharedVertex = sharedVertexOf(mesh, triangle);
        Point2D committedPosition = new Point2D(mesh.vertices().get(sharedVertex).x() + 3.0, mesh.vertices().get(sharedVertex).z() - 5.0);

        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments = uniformAssignments(mesh, adjacency, (int) (2 * SIZE));
        assignments.put(triangle[0], new TriangleIdentitySizeAssignment.Assignment(BIOME, 100_000));
        assignments.put(triangle[1], new TriangleIdentitySizeAssignment.Assignment(BIOME, 1));

        Map<Integer, Point2D> displaced = WeightedVertexPlacement.displacedVertexPositions(
            mesh, adjacency, assignments, SIZE, 0.0, Map.of(), Map.of(sharedVertex, committedPosition)
        );

        assertEquals(committedPosition, displaced.get(sharedVertex), "a committed vertex position must never be altered by calibration or the geometric-validity clamp");
    }

    /** Face with no assignment at all (fixed cross-region, or hull-touching) must not crash computation - falls back to baseline weight, same as documented. */
    @Test
    void unassignedFacesFallBackToBaselineWeightWithoutCrashing() {
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(SIZE, 0, 0, 3000, 3000);
        FaceAdjacency.Adjacency adjacency = FaceAdjacency.of(mesh.faces());
        int[] triangle = anyRealTriangle(mesh, adjacency);

        // Only ONE of 3 faces in this triangle has real assignment - other two unassigned (simulating fixed/hull-touching context).
        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments = new HashMap<>();
        assignments.put(triangle[0], new TriangleIdentitySizeAssignment.Assignment(BIOME, (int) (2 * SIZE)));

        Map<Integer, Point2D> displaced = WeightedVertexPlacement.displacedVertexPositions(mesh, adjacency, assignments, SIZE, 0.0);
        assertTrue(displaced.containsKey(sharedVertexOf(mesh, triangle)), "expected the shared vertex to still resolve using baseline weight for the unassigned faces");
    }

    /**
     * Independent reimplementation of production simplicity check (same standard
     * orientation-based segment-intersection test), not call into private production
     * method - these tests exist to verify real geometric PROPERTY production code
     * claims to guarantee, so must not share its implementation, or shared bug in both
     * would go undetected.
     */
    @Test
    void isPolygonSimpleReturnsTrueForARegularHexagon() {
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(SIZE, 0, 0, 3000, 3000);
        List<Point2D> corners = new ArrayList<>();
        for (int vertexIndex : mesh.faces().get(anyRealTriangle(mesh, FaceAdjacency.of(mesh.faces()))[0]).vertexIndices()) {
            corners.add(mesh.vertices().get(vertexIndex));
        }
        assertTrue(isPolygonSimple(corners), "a real, undisplaced regular hexagon must always be simple");
    }

    @Test
    void isPolygonSimpleReturnsFalseForAHandBuiltBowtieQuadrilateral() {
        List<Point2D> bowtie = List.of(
            new Point2D(0, 0), new Point2D(10, 10), new Point2D(10, 0), new Point2D(0, 10)
        ); // edges (0,0)-(10,10) and (10,0)-(0,10) cross in middle - textbook bowtie
        assertTrue(!isPolygonSimple(bowtie), "expected a hand-built bowtie quadrilateral to be detected as non-simple");
    }

    @Test
    void isPolygonSimpleDoesNotFlagASharedCornerBetweenAdjacentEdgesAsACrossing() {
        List<Point2D> irregularButSimple = List.of(
            new Point2D(0, 0), new Point2D(10, -2), new Point2D(20, 0),
            new Point2D(18, 15), new Point2D(10, 40), new Point2D(2, 15)
        ); // one corner (10, 40) pulled far out, but shape never crosses itself
        assertTrue(isPolygonSimple(irregularButSimple), "a heavily irregular but genuinely non-crossing hexagon must not be flagged as unsimple");
    }

    /** Standalone reimplementation, deliberately not sharing code with production method under test - see class doc above first of these tests. */
    private static boolean isPolygonSimple(List<Point2D> corners) {
        int cornerCount = corners.size();
        for (int i = 0; i < cornerCount; i++) {
            for (int j = i + 2; j < cornerCount; j++) {
                if (i == 0 && j == cornerCount - 1) {
                    continue;
                }
                if (segmentsCross(corners.get(i), corners.get((i + 1) % cornerCount), corners.get(j), corners.get((j + 1) % cornerCount))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean segmentsCross(Point2D a1, Point2D a2, Point2D b1, Point2D b2) {
        double d1 = orientation(b1, b2, a1);
        double d2 = orientation(b1, b2, a2);
        double d3 = orientation(a1, a2, b1);
        double d4 = orientation(a1, a2, b2);
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0));
    }

    private static double orientation(Point2D a, Point2D b, Point2D c) {
        return (b.x() - a.x()) * (c.z() - a.z()) - (b.z() - a.z()) * (c.x() - a.x());
    }

    private static Map<Integer, TriangleIdentitySizeAssignment.Assignment> uniformAssignments(
        HexMeshBuilder.HexMesh mesh, FaceAdjacency.Adjacency adjacency, int widthBlocks
    ) {
        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments = new HashMap<>();
        for (int faceIndex = 0; faceIndex < mesh.faces().size(); faceIndex++) {
            if (!adjacency.hullTouchingFaceIndices().contains(faceIndex)) {
                assignments.put(faceIndex, new TriangleIdentitySizeAssignment.Assignment(BIOME, widthBlocks));
            }
        }
        return assignments;
    }

    private static int[] anyRealTriangle(HexMeshBuilder.HexMesh mesh, FaceAdjacency.Adjacency adjacency) {
        Set<Integer> allFaces = new HashSet<>();
        for (int faceIndex = 0; faceIndex < mesh.faces().size(); faceIndex++) {
            allFaces.add(faceIndex);
        }
        for (int[] triangle : adjacency.mutuallyAdjacentFaceTriples(allFaces)) {
            boolean anyHullTouching = false;
            for (int face : triangle) {
                if (adjacency.hullTouchingFaceIndices().contains(face)) {
                    anyHullTouching = true;
                }
            }
            if (!anyHullTouching) {
                return triangle;
            }
        }
        throw new IllegalStateException("expected at least one fully-interior real mesh-vertex triple in a real-sized mesh");
    }

    /** Interior face whose 6 neighbors are ALSO non-hull-touching - so every one of its 6 vertex-triples resolves to real position, matching genuine interior-region face rather than one near mesh's outer margin. */
    private static int aFaceWithSixFullyInteriorNeighbors(HexMeshBuilder.HexMesh mesh, FaceAdjacency.Adjacency adjacency) {
        for (Map.Entry<Integer, Set<Integer>> entry : adjacency.neighborFaceIndices().entrySet()) {
            int faceIndex = entry.getKey();
            if (adjacency.hullTouchingFaceIndices().contains(faceIndex)) {
                continue;
            }
            Set<Integer> neighbors = entry.getValue();
            if (neighbors.size() == 6 && neighbors.stream().noneMatch(n -> adjacency.hullTouchingFaceIndices().contains(n))) {
                return faceIndex;
            }
        }
        throw new IllegalStateException("expected at least one fully-interior 6-neighbor face in a real-sized mesh");
    }

    private static double realizedWidthOf(HexMeshBuilder.HexMesh mesh, Map<Integer, Point2D> displaced, int faceIndex) {
        List<Integer> vertexIndices = mesh.faces().get(faceIndex).vertexIndices();
        int cornerCount = vertexIndices.size();
        double sum = 0;
        for (int i = 0; i < cornerCount; i++) {
            Point2D current = displaced.getOrDefault(vertexIndices.get(i), mesh.vertices().get(vertexIndices.get(i)));
            Point2D next = displaced.getOrDefault(vertexIndices.get((i + 1) % cornerCount), mesh.vertices().get(vertexIndices.get((i + 1) % cornerCount)));
            sum += current.x() * next.z() - next.x() * current.z();
        }
        double area = Math.abs(sum) / 2.0;
        return 2.0 * Math.sqrt(area / (3.0 * Math.sqrt(3.0) / 2.0));
    }

    private static int sharedVertexOf(HexMeshBuilder.HexMesh mesh, int[] triangle) {
        Set<Integer> shared = new HashSet<>(mesh.faces().get(triangle[0]).vertexIndices());
        shared.retainAll(mesh.faces().get(triangle[1]).vertexIndices());
        shared.retainAll(mesh.faces().get(triangle[2]).vertexIndices());
        return shared.iterator().next();
    }

    private static Point2D centroidOf(HexMeshBuilder.HexMesh mesh, int faceIndex) {
        double sumX = 0;
        double sumZ = 0;
        HexFace face = mesh.faces().get(faceIndex);
        for (int vertexIndex : face.vertexIndices()) {
            Point2D vertex = mesh.vertices().get(vertexIndex);
            sumX += vertex.x();
            sumZ += vertex.z();
        }
        int cornerCount = face.vertexIndices().size();
        return new Point2D(sumX / cornerCount, sumZ / cornerCount);
    }

    private static double distance(Point2D a, Point2D b) {
        return Math.hypot(a.x() - b.x(), a.z() - b.z());
    }
}
