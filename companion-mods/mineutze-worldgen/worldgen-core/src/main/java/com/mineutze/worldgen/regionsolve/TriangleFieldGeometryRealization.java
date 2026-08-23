package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeSizeRange;
import com.mineutze.worldgen.biomegraph.BiomeNode;
import com.mineutze.worldgen.regionsolve.field.AdaptiveDomainWarp;
import com.mineutze.worldgen.regionsolve.field.AdaptiveWarpTuning;
import com.mineutze.worldgen.regionsolve.field.CoherentNoise2D;
import com.mineutze.worldgen.regionsolve.field.SafetyField;
import com.mineutze.worldgen.regionsolve.field.DomainWarp;
import com.mineutze.worldgen.regionsolve.topology.DelaunayTriangulation;
import com.mineutze.worldgen.regionsolve.topology.IndexedTriangle;
import com.mineutze.worldgen.regionsolve.topology.Point2D;
import com.mineutze.worldgen.regionsolve.topology.SeedGrid;
import com.mineutze.worldgen.regionsolve.topology.TriangleAdjacency;
import com.mineutze.worldgen.regionsolve.topology.TriangleIdentitySizeAssignment;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The actual region-solve path, gluing every piece built this session into
 * one: {@link SeedGrid} (topology-only placement) → {@link
 * DelaunayTriangulation} → {@link TriangleAdjacency} (used ONLY for the
 * CSP's legality-solving tractability — see {@link #rasterizeFaceWinners}'s
 * own doc for why the field stage deliberately does NOT reuse this ≤3
 * cap) → {@link TriangleIdentitySizeAssignment} (fused identity+size CSP)
 * → {@link #correctRadii} (pre-commit size feedback loop) → {@link
 * AdaptiveDomainWarp} (organic shape) → rasterize to {@code
 * Map<MacroCellPos,BiomeId>}.
 *
 * <p><b>Illegal adjacency is now a hard ZERO, not a low rate.</b> Two
 * closure mechanisms work together:
 * <ul>
 *   <li>{@link RegionSolveInput#fixedBoundaryCells} is consulted for IDENTITY
 *   (a face whose centroid falls in an already-solved neighbor is fixed to
 *   that neighbor's own decision, via {@link TriangleIdentitySizeAssignment}'s
 *   existing {@code fixedFaceBiomes} mechanism — see {@link
 *   #buildFixedFaceBiomes}), reducing how often the CSP hands a free face a
 *   biome that would need correcting at rasterization time at all.
 *   <li>{@link LegalityConstrainedRasterizer} does the actual final
 *   rasterization (replacing a plain global weighted-nearest search, which
 *   — see {@link #rasterizeFaceWinners}'s own doc — is deliberately NOT
 *   restricted to {@link TriangleAdjacency}'s Delaunay edges, so two faces
 *   the CSP never legality-checked against each other could otherwise win
 *   geometrically-adjacent cells). It grows the raster out cell-by-cell,
 *   most-confident-first, committing each cell only to a biome that's
 *   graph-legal against every already-decided Moore (8-way) neighbor —
 *   see that class's own doc for the full mechanism, the fallback layer,
 *   and the fail-loud dead-end path. Paired with {@link
 *   com.mineutze.worldgen.biomegraph.BiomeGraph#requireEveryLocalNeighborhoodSolvable},
 *   which proves that dead-end structurally unreachable for a well-formed
 *   graph rather than just hoping it's rare.
 * </ul>
 *
 * <p>Real, separate, and still-open gap: the exact rendered pixel SHAPE at
 * a region border can still show a visible "join," since a region's own
 * field/warp/radius competition isn't forced to reproduce an already-solved
 * neighbor's exact geometry there — legality is now guaranteed, smoothness
 * of the seam curve itself is not (would need {@link RegionSolveResult} to
 * carry more than the final biome-per-cell map — a real, separate, and
 * smaller follow-up, deliberately not built here).
 */
public final class TriangleFieldGeometryRealization {

    // Coarse relative to macroCellSizeBlocks (4) - matches doc/DESIGN.md's own target widths
    // (400-1000 blocks), so a typical triangle's territory is comparable to one real biome's
    // size, not many tiny slivers or one giant blob.
    private static final int SEED_SPACING_BLOCKS = 500;
    // Generous multiple of spacing, not the noise-field's own (much smaller) SafetyField
    // margin - this is what guarantees every triangle touching the actual region is
    // non-hull-touching in TriangleAdjacency's sense (real neighbor context on every side
    // for the CSP's own legality solving - the field stage no longer uses this adjacency
    // at all, see rasterizeFaceWinners's own doc).
    //
    // Package-private, not private: RegionBoundaryCollector.cellsBorderingRegion reuses
    // this SAME constant as its own collection depth - it's the exact bound past which no
    // face centroid in a region's own mesh can ever exist, so the two need a single shared
    // source of truth, not two independently-tuned numbers that could quietly drift apart.
    static final int TRIANGULATION_MARGIN_BLOCKS = SEED_SPACING_BLOCKS * 3;

    // Bounded pre-commit refinement, not a runtime repair loop and not unbounded search -
    // adjusts the RADIUS (a generation parameter) and re-evaluates the SAME deterministic
    // field, never touches an already-decided output cell. Measured directly: the very
    // first version, with no correction at all, produced a patch 60%+ over its own
    // declared maximum. No convergence proof - a heuristic feedback loop, same "tune from
    // real output, cheapest-fix ceiling reached, accept the last round" status as the
    // deleted EdgeDisplacement.correctAreaIfNeeded this replaces the spirit of.
    // COORDINATE DESCENT (see correctRadii's own doc for why): one full rasterization per
    // RELEVANT face per pass, not per pass.
    private static final int MAX_CORRECTION_PASSES = 3;
    private static final double CORRECTION_DAMPING = 0.35;
    // Real, pre-existing instability found via direct diagnostic trace (a region where
    // several biomes are closely competing): "factor = target / measuredWidth" is
    // UNBOUNDED when a triangle's measured width is tiny next to its target (e.g.
    // target 800 / measured 20 = factor 40), so despite CORRECTION_DAMPING, one single
    // pass can slam a radius almost straight to its cap - measured directly, several
    // biomes' radii jumped from ~400-500 to their 1500 cap in ONE pass. With several
    // biomes all making cap-sized jumps at once, the region-wide field never settles:
    // whichever biome jumped biggest this pass steals everyone else's area, so THEY
    // measure near-zero next pass and jump huge themselves, back and forth, oscillating
    // rather than converging within MAX_CORRECTION_PASSES. Capping the STEP itself (not
    // just the final ceiling) is what actually stops this - a triangle can still reach
    // its full ceiling eventually, just not in one destabilizing lunge.
    private static final double MAX_STEP_FACTOR_PER_PASS = 2.0;
    private static final double MIN_RADIUS_BLOCKS = 10.0;
    // A face that won zero cells this round has no measured width to compute a ratio
    // from - grow it by a fixed step instead so it gets a real chance to compete next
    // pass, rather than staying stuck at zero forever.
    private static final double ZERO_AREA_GROWTH_FACTOR = 1.5;
    // Real bug this fixes, found via direct trace: a triangle far outside the region (one
    // of the many placed only for TRIANGULATION_MARGIN_BLOCKS's own hull-safety purpose,
    // never meant to win real cells) has no way to ever measure a nonzero area, so
    // ZERO_AREA_GROWTH_FACTOR compounded it every pass completely unbounded - one such
    // triangle reached a 17,172-block radius for a biome whose max target was 1000,
    // before this cap existed. PER-BIOME (each face capped relative to ITS OWN declared
    // max width, not one blanket value derived from the graph's single largest biome) -
    // a first attempt used one shared cap (2x the graph's largest max width) and it was
    // measured to make things WORSE: that shared value was wider than
    // TRIANGULATION_MARGIN_BLOCKS itself, so it capped nothing in practice, AND let
    // genuinely irrelevant margin-only triangles become real (illegitimate) long-range
    // competitors once the search went global - ocean's oversizing got WORSE (1886 vs
    // 1613 blocks), not better, and runtime nearly quadrupled (56s -> 213s).
    private static final double MAX_RADIUS_FACTOR_OF_OWN_TARGET = 1.5;
    // Decoupled from the radius cap on purpose (see the note above) - a FIXED prefilter
    // margin, independent of any biome's declared size, so it can't accidentally end up
    // wider than TRIANGULATION_MARGIN_BLOCKS the way the radius-derived version did.
    private static final int RELEVANCE_MARGIN_BLOCKS = SEED_SPACING_BLOCKS * 2;

    // AREA FAIRNESS (found via direct diagnostic: badlands crushed to 2 total cells across
    // 2 disconnected islands in a real region, despite a 500-800 block target width and a
    // legal CSP identity assignment - the CSP gives fair TRIANGLE counts, but the field/
    // radius competition below has zero awareness of that, so a biome next to bigger-target
    // neighbors can lose almost every cell it's owed even though nothing about its own
    // width target was ever violated). Same quota idea already used by the CSP's own
    // FairnessTracker (BiomeSizeRange midpoint SQUARED, since a final patch is an AREA, not
    // just a width) - reused here, but on measured CELL COUNT instead of triangle count.
    // A biome is "starved" this pass if its own measured area (summed across every face
    // that won cells for it) is under half its fair share of the whole region's cells.
    //
    // GATED per-biome by measured deficit - NOT the same as the shared-cap experiment
    // documented on MAX_RADIUS_FACTOR_OF_OWN_TARGET above, which raised the cap for EVERY
    // face regardless of need and was measured to make an already-fine biome (ocean) worse.
    // Here, a biome that's already meeting or beating its quota (e.g. an oversized desert)
    // never gets the boost - only one measurably losing the fairness competition does.
    private static final double FAIRNESS_STARVATION_RATIO = 0.5;
    private static final double FAIRNESS_MAX_RADIUS_FACTOR = 4.0;
    private static final double FAIRNESS_ZERO_AREA_GROWTH_FACTOR = 2.5;

    private TriangleFieldGeometryRealization() {
    }

    public static RegionSolveResult realize(RegionSolveInput input) {
        MacroGridConfig gridConfig = input.gridConfig();
        List<MacroCellPos> cells = gridConfig.cellsInRegion(input.region(), input.worldSeed());
        int cellSize = gridConfig.macroCellSizeBlocks();

        int minBlockX = cells.stream().mapToInt(MacroCellPos::x).min().orElseThrow() * cellSize;
        int maxBlockX = (cells.stream().mapToInt(MacroCellPos::x).max().orElseThrow() + 1) * cellSize;
        int minBlockZ = cells.stream().mapToInt(MacroCellPos::z).min().orElseThrow() * cellSize;
        int maxBlockZ = (cells.stream().mapToInt(MacroCellPos::z).max().orElseThrow() + 1) * cellSize;

        List<Point2D> points = SeedGrid.seedsIn(
            input.worldSeed(), SEED_SPACING_BLOCKS,
            minBlockX - TRIANGULATION_MARGIN_BLOCKS, minBlockZ - TRIANGULATION_MARGIN_BLOCKS,
            maxBlockX + TRIANGULATION_MARGIN_BLOCKS, maxBlockZ + TRIANGULATION_MARGIN_BLOCKS
        );
        List<IndexedTriangle> faces = DelaunayTriangulation.triangulateFaces(points);
        TriangleAdjacency.Adjacency adjacency = TriangleAdjacency.of(faces);

        Map<Integer, Point2D> allCentroids = new LinkedHashMap<>();
        for (int faceIndex = 0; faceIndex < faces.size(); faceIndex++) {
            allCentroids.put(faceIndex, centroidOf(faces.get(faceIndex), points));
        }
        Map<Integer, BiomeId> fixedFaceBiomes = buildFixedFaceBiomes(allCentroids, gridConfig, input.fixedBoundaryCells());

        Set<Integer> freeFaceIndices = new LinkedHashSet<>();
        for (int faceIndex = 0; faceIndex < faces.size(); faceIndex++) {
            if (!adjacency.hullTouchingFaceIndices().contains(faceIndex) && !fixedFaceBiomes.containsKey(faceIndex)) {
                freeFaceIndices.add(faceIndex);
            }
        }

        double regionAreaBlocks2 = (double) (maxBlockX - minBlockX) * (maxBlockZ - minBlockZ);
        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments =
            TriangleIdentitySizeAssignment.solve(adjacency, freeFaceIndices, fixedFaceBiomes, input.graph(), input.worldSeed(), regionAreaBlocks2)
                .orElseThrow(() -> new IllegalStateException(
                    "TriangleFieldGeometryRealization: no legal identity assignment exists for region " + input.region()
                        + " - the BiomeGraph's own construction-time bridgeability check only rules out configs "
                        + "impossible in principle, not every real local triangle arrangement."
                ));
        FieldContext field = buildField(input.worldSeed(), allCentroids, assignments);
        Set<Integer> relevantFaceIndices = relevantFaceIndices(assignments, field, minBlockX, minBlockZ, maxBlockX, maxBlockZ);
        correctRadii(cells, cellSize, assignments, relevantFaceIndices, field, input.graph());

        Map<MacroCellPos, BiomeId> result = LegalityConstrainedRasterizer.rasterize(
            cells, cellSize, relevantFaceIndices, field, assignments, input.graph(), input.fixedBoundaryCells()
        );
        return new RegionSolveResult(result, List.of(), 0);
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

    private static FieldContext buildField(long worldSeed, Map<Integer, Point2D> centroids, Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments) {
        CoherentNoise2D noiseX = new CoherentNoise2D(worldSeed ^ 0x1234_5678_9ABC_DEF0L);
        CoherentNoise2D noiseZ = new CoherentNoise2D(worldSeed ^ 0x0FED_CBA9_8765_4321L);
        AdaptiveWarpTuning tuning = AdaptiveWarpTuning.RECOMMENDED_ADAPTIVE;

        double minX = centroids.values().stream().mapToDouble(Point2D::x).min().orElse(0) - 10;
        double maxX = centroids.values().stream().mapToDouble(Point2D::x).max().orElse(0) + 10;
        double minZ = centroids.values().stream().mapToDouble(Point2D::z).min().orElse(0) - 10;
        double maxZ = centroids.values().stream().mapToDouble(Point2D::z).max().orElse(0) + 10;
        double fieldMargin = tuning.dilateRadiusCells() * tuning.latticeSpacing() + tuning.latticeSpacing();

        DomainWarp riskReference = new DomainWarp(noiseX, noiseZ, tuning.strongAmplitude(), tuning.frequency(), tuning.octaves());
        SafetyField safetyField = new SafetyField(
            riskReference, minX - fieldMargin, maxX + fieldMargin, minZ - fieldMargin, maxZ + fieldMargin, tuning.latticeSpacing(),
            tuning.dangerDeterminant(), tuning.comfortableDeterminant(), tuning.dilateRadiusCells()
        );
        AdaptiveDomainWarp warp = new AdaptiveDomainWarp(noiseX, noiseZ, tuning.floorAmplitude(), tuning.strongAmplitude(), tuning.frequency(), tuning.octaves(), safetyField);

        Map<Integer, Point2D> assignedCentroids = new LinkedHashMap<>();
        Map<Integer, Double> radii = new LinkedHashMap<>();
        assignments.forEach((faceIndex, assignment) -> {
            assignedCentroids.put(faceIndex, centroids.get(faceIndex));
            radii.put(faceIndex, assignment.widthBlocks() / 2.0);
        });

        return new FieldContext(warp, assignedCentroids, radii);
    }

    private static Point2D centroidOf(IndexedTriangle face, List<Point2D> points) {
        Point2D a = points.get(face.a());
        Point2D b = points.get(face.b());
        Point2D c = points.get(face.c());
        return new Point2D((a.x() + b.x() + c.x()) / 3.0, (a.z() + b.z() + c.z()) / 3.0);
    }

    /**
     * A face whose centroid sits further than {@link #RELEVANCE_MARGIN_BLOCKS} outside the
     * region's own bounding box is treated as irrelevant — excluded from both {@link
     * #correctRadii} and {@link #rasterizeFaceWinners}. Filters most of the many triangles
     * that exist ONLY for {@code TRIANGULATION_MARGIN_BLOCKS}'s hull-safety purpose — the
     * real fix for the wasted-correction-time bug found via direct trace (dozens of
     * always-area-0 faces burning full rasterization passes every round for no visual
     * effect). {@code RELEVANCE_MARGIN_BLOCKS} is a fixed constant, deliberately NOT
     * derived from any radius cap — see that constant's own doc for the measured regression
     * from an earlier version that coupled the two.
     */
    private static Set<Integer> relevantFaceIndices(
        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments, FieldContext field,
        int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ
    ) {
        Set<Integer> relevant = new LinkedHashSet<>();
        for (int faceIndex : assignments.keySet()) {
            Point2D centroid = field.centroids().get(faceIndex);
            boolean withinReach = centroid.x() >= minBlockX - RELEVANCE_MARGIN_BLOCKS && centroid.x() <= maxBlockX + RELEVANCE_MARGIN_BLOCKS
                && centroid.z() >= minBlockZ - RELEVANCE_MARGIN_BLOCKS && centroid.z() <= maxBlockZ + RELEVANCE_MARGIN_BLOCKS;
            if (withinReach) {
                relevant.add(faceIndex);
            }
        }
        return relevant;
    }

    /**
     * Mutates {@code field.radii()} in place, toward each triangle's own biome's declared
     * {@link BiomeSizeRange} — COORDINATE DESCENT, one face at a time, each one fully
     * re-measured against the LATEST radii before the next face is touched. A first
     * version updated every face simultaneously each round from one shared snapshot —
     * cheaper but measured to actively OSCILLATE for some faces (more rounds made MORE
     * patches drift out of range, not fewer, because every face was reacting to a
     * same-round snapshot already stale by the time its own update landed). Coordinate
     * descent is the principled fix — each update sees the true current state — at real,
     * measured extra cost: one rasterization per RELEVANT face per pass.
     *
     * <p>Each pass also takes ONE extra full rasterization up front (see {@link
     * #starvedBiomes}) to check, per BIOME, whether it's losing the AREA fairness
     * competition region-wide. A triangle that is CURRENTLY WINNING NOTHING (its own
     * measured area is zero) AND belongs to a starved biome gets a taller radius ceiling
     * and a faster growth step (see the {@code FAIRNESS_*} constants) — narrowly scoped to
     * only-empty-triangles-of-starved-biomes on purpose, see {@link #starvedBiomes}'s own
     * doc for the measured region-wide runaway a broader version of this caused.
     */
    private static void correctRadii(
        List<MacroCellPos> cells, int cellSize, Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments,
        Set<Integer> relevantFaceIndices, FieldContext field, BiomeGraph graph
    ) {
        List<Integer> orderedFaces = relevantFaceIndices.stream().sorted().toList();
        Map<BiomeId, Double> fairShareWeights = fairShareWeights(graph);

        for (int pass = 0; pass < MAX_CORRECTION_PASSES; pass++) {
            Set<BiomeId> starved = starvedBiomes(cells, cellSize, relevantFaceIndices, field, assignments, fairShareWeights);
            boolean anyChanged = false;
            for (int faceIndex : orderedFaces) {
                Map<Integer, Integer> winners = rasterizeFaceWinners(cells, cellSize, relevantFaceIndices, field);
                int area = 0;
                for (int winner : winners.values()) {
                    if (winner == faceIndex) {
                        area++;
                    }
                }

                BiomeId biome = assignments.get(faceIndex).biome();
                BiomeSizeRange range = graph.node(biome).sizeRange();
                // Only a triangle that is CURRENTLY winning nothing gets the taller,
                // faster-growing starved treatment - a triangle already contributing real
                // area is left on the normal path even if its biome is starved, so this
                // never re-inflates an already-fine patch (see this method's own doc for
                // the measured runaway an earlier version of this caused by doing that).
                boolean isStarved = area == 0 && starved.contains(biome);
                double radiusFactor = isStarved ? FAIRNESS_MAX_RADIUS_FACTOR : MAX_RADIUS_FACTOR_OF_OWN_TARGET;
                double ownMaxRadius = radiusFactor * range.maxWidthBlocks();
                double currentRadius = field.radii().get(faceIndex);

                if (area == 0) {
                    double growthFactor = isStarved ? FAIRNESS_ZERO_AREA_GROWTH_FACTOR : ZERO_AREA_GROWTH_FACTOR;
                    double grown = Math.min(ownMaxRadius, currentRadius * growthFactor);
                    if (grown != currentRadius) {
                        field.radii().put(faceIndex, grown);
                        anyChanged = true;
                    }
                    continue;
                }

                double measuredWidth = Math.sqrt(area) * cellSize;
                double target;
                if (measuredWidth < range.minWidthBlocks()) {
                    target = range.minWidthBlocks();
                } else if (measuredWidth > range.maxWidthBlocks()) {
                    target = range.maxWidthBlocks();
                } else {
                    continue; // already within range, leave this face's radius alone
                }

                double factor = target / measuredWidth;
                double newRadius = currentRadius * (1.0 + CORRECTION_DAMPING * (factor - 1.0));
                // Clamp the STEP itself, not just the final ceiling - see
                // MAX_STEP_FACTOR_PER_PASS's own doc for the real oscillation this stops.
                newRadius = Math.min(newRadius, currentRadius * MAX_STEP_FACTOR_PER_PASS);
                newRadius = Math.max(newRadius, currentRadius / MAX_STEP_FACTOR_PER_PASS);
                newRadius = Math.min(ownMaxRadius, Math.max(MIN_RADIUS_BLOCKS, newRadius));
                if (newRadius != currentRadius) {
                    field.radii().put(faceIndex, newRadius);
                    anyChanged = true;
                }
            }

            if (!anyChanged) {
                break;
            }
        }
    }

    /** Each biome's target share of the region's total cells, proportional to its {@link BiomeSizeRange} midpoint SQUARED — same weight rule {@code TriangleIdentitySizeAssignment.fairShareWeights} uses for triangle-count fairness, applied here to measured AREA instead. */
    private static Map<BiomeId, Double> fairShareWeights(BiomeGraph graph) {
        Map<BiomeId, Double> weights = new LinkedHashMap<>();
        for (BiomeNode node : graph.nodes()) {
            BiomeSizeRange range = node.sizeRange();
            double midWidth = (range.minWidthBlocks() + range.maxWidthBlocks()) / 2.0;
            weights.put(node.id(), midWidth * midWidth);
        }
        return weights;
    }

    /**
     * One full rasterization (region-wide, not per-face), aggregated by biome — which
     * biomes are measurably below half their fair share of the region's total cells this
     * pass. Deliberately per-BIOME, not per-triangle: a per-triangle version (tried and
     * measured) compares each triangle against an EVEN split of its biome's quota across
     * every triangle that biome happens to have — but real Voronoi competition splits area
     * unevenly by nature (a few triangles win big, many win little), so an even split
     * flags nearly every triangle of every populous biome as "starved" simultaneously.
     * Measured directly: with that version, EVERY biome's max radius was exploding by the
     * same ~2.4-2.5x every single pass (not just the genuinely crushed one), because the
     * starved-boost path was firing almost universally instead of only where it was truly
     * needed — a self-reinforcing, region-wide radius arms race, not a targeted fairness
     * correction. Per-biome totals don't have this problem: a biome is only starved if it
     * is, in aggregate, actually losing the space competition.
     */
    private static Set<BiomeId> starvedBiomes(
        List<MacroCellPos> cells, int cellSize, Set<Integer> relevantFaceIndices, FieldContext field,
        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments, Map<BiomeId, Double> fairShareWeights
    ) {
        Map<Integer, Integer> winners = rasterizeFaceWinners(cells, cellSize, relevantFaceIndices, field);
        Map<BiomeId, Integer> areaByBiome = new HashMap<>();
        for (int winnerFace : winners.values()) {
            areaByBiome.merge(assignments.get(winnerFace).biome(), 1, Integer::sum);
        }

        double totalWeight = fairShareWeights.values().stream().mapToDouble(Double::doubleValue).sum();
        int totalCells = cells.size();
        Set<BiomeId> starved = new HashSet<>();
        for (Map.Entry<BiomeId, Double> entry : fairShareWeights.entrySet()) {
            double quota = entry.getValue() / totalWeight * totalCells;
            int measured = areaByBiome.getOrDefault(entry.getKey(), 0);
            if (measured < quota * FAIRNESS_STARVATION_RATIO) {
                starved.add(entry.getKey());
            }
        }
        return starved;
    }

    /**
     * MEASUREMENT-ONLY — used exclusively by {@link #correctRadii}'s own internal area
     * counting, never for the actual final output ({@link LegalityConstrainedRasterizer}
     * does that now). Legality-UNAWARE on purpose: {@code correctRadii} only needs an area
     * estimate for radius tuning, not a legality guarantee, and this stays the cheap,
     * embarrassingly-parallel-shaped version specifically so the repeated per-face,
     * per-pass measurement loop doesn't pay the sequential growth algorithm's real cost
     * (see {@link LegalityConstrainedRasterizer}'s own doc) on every single call.
     *
     * <p>Genuinely GLOBAL weighted-nearest search (additively-weighted Voronoi: minimize
     * distance-in-warped-space MINUS radius, over every relevant face) — NOT restricted to
     * a "containing/anchor triangle's ≤3 real neighbors" the way an earlier version of this
     * method was. That restriction existed to enforce {@link TriangleAdjacency}'s own
     * ≤3-neighbor cap, but that cap's real purpose (per direct clarification) was only ever
     * to keep the CSP's own legality-solving tractable — it was never meant to bound how
     * far a triangle's declared SIZE could extend its rendered reach. Measured directly:
     * with the old restriction, a triangle's plain-nearest-centroid "anchor" status (never
     * weighted by radius) put a hard ceiling on its reach regardless of radius — one real
     * triangle sat at an 11,040-block radius (target ~800) while still only winning its
     * original, far-too-small territory, because it could never even become a CANDIDATE
     * for cells beyond its own immediate unweighted neighborhood. A real global search
     * (feasible here: {@link #relevantFaceIndices} already prunes to a small set) fixes
     * this at the source instead of fighting it with an ever-bigger radius.
     *
     * @return winning face index per cell, keyed by POSITION in {@code cells} (not the
     *         cell itself) — {@link #correctRadii} only needs the counts, and avoiding a
     *         real {@code MacroCellPos} key here is a cheap win at 262k-cell scale.
     */
    static Map<Integer, Integer> rasterizeFaceWinners(
        List<MacroCellPos> cells, int cellSize, Set<Integer> relevantFaceIndices, FieldContext field
    ) {
        Map<Integer, Integer> winners = new LinkedHashMap<>();
        for (int i = 0; i < cells.size(); i++) {
            MacroCellPos cell = cells.get(i);
            double blockX = cell.x() * cellSize + cellSize / 2.0;
            double blockZ = cell.z() * cellSize + cellSize / 2.0;
            double[] warped = field.warp().warp(blockX, blockZ);

            int winner = -1;
            double bestScore = Double.MAX_VALUE;
            for (int faceIndex : relevantFaceIndices) {
                double candidateScore = score(warped, field, faceIndex);
                if (candidateScore < bestScore) {
                    bestScore = candidateScore;
                    winner = faceIndex;
                }
            }
            winners.put(i, winner);
        }
        return winners;
    }

    /** Package-private: {@link LegalityConstrainedRasterizer} reuses this exact scoring function, single source of truth for the distance-minus-radius rule both classes score candidates by. */
    static double score(double[] warpedPoint, FieldContext field, int faceIndex) {
        Point2D centroid = field.centroids().get(faceIndex);
        double dx = warpedPoint[0] - centroid.x();
        double dz = warpedPoint[1] - centroid.z();
        return Math.sqrt(dx * dx + dz * dz) - field.radii().get(faceIndex);
    }
}
