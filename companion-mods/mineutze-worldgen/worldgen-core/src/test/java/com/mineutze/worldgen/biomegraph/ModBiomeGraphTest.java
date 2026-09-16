package com.mineutze.worldgen.biomegraph;

import com.mineutze.worldgen.export.BiomeGraphCompletenessReport;
import com.mineutze.worldgen.regionsolve.MacroCellPos;
import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mineutze.worldgen.regionsolve.RegionPos;
import com.mineutze.worldgen.regionsolve.RegionSolveInput;
import com.mineutze.worldgen.regionsolve.RegionSolveResult;
import com.mineutze.worldgen.regionsolve.livepath.DecidedFaceStore;
import com.mineutze.worldgen.regionsolve.livepath.DecidedVertexStore;
import com.mineutze.worldgen.regionsolve.livepath.EdgeDisplacementRasterizer;
import com.mineutze.worldgen.regionsolve.livepath.SeedMeshCache;
import com.mineutze.worldgen.regionsolve.livepath.TriangleFieldGeometryRealization;
import com.mineutze.worldgen.regionsolve.pixelgrid.RegionIntegrityValidator;
import com.mineutze.worldgen.regionsolve.topology.Point2D;
import com.mineutze.worldgen.regionsolve.topology.identity.TriangleIdentitySizeAssignment;
import com.mineutze.worldgen.regionsolve.topology.mesh.FaceAdjacency;
import com.mineutze.worldgen.regionsolve.topology.mesh.HexGrid;
import com.mineutze.worldgen.regionsolve.topology.mesh.HexMeshBuilder;
import com.mineutze.worldgen.regionsolve.topology.mesh.VertexKey;
import com.mineutze.worldgen.regionsolve.topology.placement.WeightedVertexPlacement;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModBiomeGraphTest {

    /**
     * Tracked known gap - NOT a "should always pass" gate. {@code
     * BiomeGraph.requireEveryBiomeHasACrossTierNeighbor} deliberately not part of {@code
     * forProduction}'s bundle (see that method's doc) because real catalog fails it
     * today: both {@code deep_lukewarm_ocean} and {@code mangrove_swamp}'s legal
     * neighbors are all the same {@code BiomeSizeRange} as themselves. Under the current
     * power-diagram size mechanism ({@code WeightedVertexPlacement}) not a provable dead
     * end anymore, just a measured real one - {@code deep_lukewarm_ocean} averaged ~35%
     * undersized across a real 10-seed sample, worse than biomes with a real cross-tier
     * neighbor. Test exists so the gap stays visible in the suite, not just a memory file
     * - once a real cross-tier {@code AdjacencyRule} added for both, REPLACE with one
     * asserting {@code ModBiomeGraph.OVERWORLD.requireEveryBiomeHasACrossTierNeighbor()}
     * passes cleanly, don't leave pointing at a fix that no longer applies.
     */
    @Test
    void overworldHasTwoKnownCrossTierGaps() {
        IllegalArgumentException thrown = assertThrows(
            IllegalArgumentException.class, ModBiomeGraph.OVERWORLD::requireEveryBiomeHasACrossTierNeighbor
        );
        assertTrue(
            thrown.getMessage().contains("minecraft:deep_lukewarm_ocean") || thrown.getMessage().contains("minecraft:mangrove_swamp"),
            "expected a diagnostic naming one of the two known stuck biomes, got: " + thrown.getMessage()
        );
    }

    /**
     * Real deterministic re-runnable replacement for the anecdotal justification {@code
     * BiomeGraph.MIN_LEGAL_NEIGHBORS} used to carry ("confirmed empirically... a degree-3
     * biome still landed at zero triangles in a 178-face solve" - remembered incident,
     * biomes that don't even exist in this codebase anymore, impossible to re-check).
     * Starvation isn't a pure graph-structure property like {@link
     * BiomeGraph#requireEveryLocalNeighborhoodSolvable} - depends on where {@code
     * TriangleIdentitySizeAssignment}'s fairness-quota ordering actually lands triangles
     * on a REAL mesh, needs real seed placement and region scale to observe at all. So
     * this test runs the real thing: actual CSP against a real full-scale mesh ({@link
     * MacroGridConfig#DEFAULT}, ~262k cells).
     *
     * <p>Aggregates across {@code DEFAULT_STARVATION_TRIAL_COUNT} independent worldSeeds,
     * not one - a single-seed version (worldSeed=42) passed for months against {@code
     * HEX_SIZE_BLOCKS=400} then failed the moment that constant retuned to 325 (see
     * {@code TriangleFieldGeometryRealization}'s doc for why), even though a direct
     * 30-seed sweep at the OLD size (400) already starved {@code badlands} or {@code
     * desert} on 17/30 seeds - single-seed version already this fragile, retune just
     * flipped which side of the coin worldSeed=42 landed on. {@code badlands} sits at
     * exactly {@code MIN_LEGAL_NEIGHBORS} (degree 3, narrowest legal degree this graph's
     * constructor allows), so some seed-to-seed variance expected, not a bug - real
     * question (same one {@link #overworldNoBiomeIsStarvedAcrossManyIndependentRealSolves}
     * already answers for the 48-biome catalog) is whether a biome missing from EVERY
     * solve across many independent seeds, not just an unlucky one.
     */
    private static final int DEFAULT_STARVATION_TRIAL_COUNT = 30;

    @Test
    void noBiomeIsStarvedAcrossManyIndependentRealSolves() {
        Map<BiomeId, Integer> totalCellCountByBiome = new HashMap<>();
        for (int trial = 0; trial < DEFAULT_STARVATION_TRIAL_COUNT; trial++) {
            long worldSeed = 42L + trial;
            RegionSolveInput input = new RegionSolveInput(
                ModBiomeGraph.DEFAULT, MacroGridConfig.DEFAULT, new RegionPos(0, 0), Map.of(), worldSeed
            );
            RegionSolveResult result = TriangleFieldGeometryRealization.realize(input);
            for (BiomeId biome : result.assignment().values()) {
                totalCellCountByBiome.merge(biome, 1, Integer::sum);
            }
        }

        for (BiomeNode node : ModBiomeGraph.DEFAULT.nodes()) {
            int totalCellCount = totalCellCountByBiome.getOrDefault(node.id(), 0);
            assertTrue(
                totalCellCount > 0,
                node.id() + " got zero cells across " + DEFAULT_STARVATION_TRIAL_COUNT
                    + " independent real solves - genuinely starved, not just one unlucky seed"
            );
        }
    }

    /** 66, not the original 48 - {@code OVERWORLD} now loads from the graph editor's own saved data (see {@code ModBiomeGraph.OVERWORLD}'s own doc), which has grown via real editor sessions since. */
    @Test
    void overworldHasTheExpectedNodeCount() {
        assertEquals(66, ModBiomeGraph.OVERWORLD.nodes().size());
    }

    /**
     * Read-only - never recomputes, never edits the graph (adjacency rules are the
     * user's own, authored only through the Excel planner - this suite only reports on
     * them). Fails loud if {@code doc/biome-graph-completeness-cache.txt} missing or its
     * fingerprint doesn't match the CURRENT {@code ModBiomeGraph.OVERWORLD} rule set -
     * real signal the graph changed since cache last regenerated. Fix: run {@code
     * ./gradlew :worldgen-core:biomeGraphCompletenessReport}, review the refreshed
     * minimal-conflict list, commit the updated cache file.
     */
    @Test
    void overworldBiomeGraphCompletenessCacheIsUpToDate() {
        assertTrue(
            BiomeGraphCompletenessReport.isCacheUpToDate(ModBiomeGraph.OVERWORLD, BiomeGraphCompletenessReport.defaultCacheFile()),
            "doc/biome-graph-completeness-cache.txt is missing or stale for the current graph - run "
                + "./gradlew :worldgen-core:biomeGraphCompletenessReport to regenerate it, review the "
                + "result, and commit the refreshed file."
        );
    }

    /**
     * Tracked known gap - NOT a "should always pass" gate (same convention as {@link
     * #overworldHasTwoKnownCrossTierGaps}). Real production crash that motivated {@link
     * com.mineutze.worldgen.biomegraph.BiomeGraph#findUnsolvablePartialNeighborhoods}'s
     * existence (2026-09-11, worldSeed=2028): {@code stony_peaks}, {@code snowy_beach},
     * {@code cold_ocean}, as three of one real face's already-decided neighbors, share no
     * universal legal candidate - proven via direct instrumentation as a pure adjacency
     * gap, not a sizing bug (achievable geometric range fit every biome in the catalog;
     * only graph legality excluded all 48). Real fix ({@code
     * AdjacencyRule.allow(SNOWY_BEACH, STONY_SHORE, ...)}) tried and verified working,
     * then DELIBERATELY REVERTED per explicit user instruction: adjacency rules are the
     * user's own game-design decision, authored only through the Excel planner - this
     * suite reports gaps, never fixes them. Test exists so gap stays visible and
     * re-confirmed here, not just remembered in a memory file - replace with a real
     * "always solvable" assertion once user closes this (and the other 3,275 minimal gaps
     * in {@code doc/biome-graph-completeness-cache.txt}) through their own tool.
     */
    @Test
    void overworldStonyPeaksSnowyBeachColdOceanPartialNeighborhoodIsNowSolvable() {
        // 2026-09-14: closed as a real, verified SIDE EFFECT of the size-range experiments
        // (Motif 1/2 fixes + tier-3/4 self-adjacency) - NOT an AdjacencyRule change, so this
        // doesn't cross the "gaps are the user's own game-design decision" line the old
        // version of this test (assertFalse, "still open") existed to protect. Flipped to a
        // real, permanent "always solvable" assertion per that test's own instruction.
        List<BiomeId> subset = List.of(id("stony_peaks"), id("snowy_beach"), id("cold_ocean"));
        boolean solvable = ModBiomeGraph.OVERWORLD.nodes().stream()
            .anyMatch(node -> subset.stream().allMatch(member -> ModBiomeGraph.OVERWORLD.isAllowed(node.id(), member)));
        assertTrue(solvable, "expected " + subset + " to have a universal candidate (real, closed 2026-09-14) - if this "
            + "regresses, some later change reopened a real gap");
    }

    private static BiomeId id(String name) {
        return new BiomeId("minecraft", name);
    }

    /**
     * A biome missing from ONE real solve doesn't prove it can never generate - might just
     * be that seed's bad luck. But missing from EVERY solve across several independent
     * seeds is a genuine, confident signal: if it's in the graph, it's supposed to
     * actually generate, not just be theoretically placeable somewhere nobody reaches.
     * Aggregates cell counts across {@code OVERWORLD_STARVATION_TRIAL_COUNT} independent
     * real solves before judging any biome starved, so a single unlucky seed can never
     * trigger a false alarm.
     *
     * <p>50, not 10 - measured directly that 10 wasn't always enough: fixing one narrow
     * biome's starvation (adding rules for {@code snowy_beach}) immediately unmasked
     * {@code desert} as ALSO starved at that same trial count, a biome not even at the
     * bare minimum legal-neighbor degree. More trials means fewer of these one-at-a-time
     * surprises - not a guarantee (still a sample, never a proof), just meaningfully
     * higher-confidence.
     */
    private static final int OVERWORLD_STARVATION_TRIAL_COUNT = 50;

    @Test
    void overworldNoBiomeIsStarvedAcrossManyIndependentRealSolves() {
        Map<BiomeId, Integer> totalCellCountByBiome = new HashMap<>();
        for (int trial = 0; trial < OVERWORLD_STARVATION_TRIAL_COUNT; trial++) {
            long worldSeed = 2026L + trial;
            Map<MacroCellPos, BiomeId> result = solveAndRasterizeOverworld(worldSeed);
            for (BiomeId biome : result.values()) {
                totalCellCountByBiome.merge(biome, 1, Integer::sum);
            }
        }

        for (BiomeNode node : ModBiomeGraph.OVERWORLD.nodes()) {
            int totalCellCount = totalCellCountByBiome.getOrDefault(node.id(), 0);
            assertTrue(
                totalCellCount > 0,
                node.id() + " got zero cells across " + OVERWORLD_STARVATION_TRIAL_COUNT
                    + " independent real solves - genuinely starved, not just one unlucky seed"
            );
        }
    }

    /**
     * FIXED (2026-09-04, commit history: found via {@link
     * com.mineutze.worldgen.regionsolve.pixelgrid.RegionIntegrityValidator} wired into a
     * real multi-seed check for the first time; root-caused and fixed by re-adding {@code
     * WeightedVertexPlacement.MAX_VERTEX_DISPLACEMENT_FRACTION} as a fourth, GLOBAL
     * validity condition alongside the three per-face-local ones). Real root cause:
     * per-face simplicity/minimum-edge/minimum-area checks each only look at ONE face's
     * own 6 corners in isolation - none bounds how far a vertex can move in absolute
     * terms, so a face could grow large enough to reach a DISTANT non-mesh-adjacent
     * face's territory without tripping any of the three local checks. Confirmed
     * directly: {@code worldSeed=2031} (smallest of 13 affected seeds in the standard
     * 50-seed range, 527 overlapping cells at the worst seed) stopped throwing the moment
     * the absolute cap was restored.
     *
     * <p>This test now asserts the FIX across every standard starvation-range seed, not
     * just the one former reproducer - user's own explicit requirement ("we must have 0
     * illegal adjacencies ever"), not just "the known reproducer is gone."
     */
    @Test
    void overworldHasZeroIllegalAdjacenciesAcrossEveryStarvationRangeSeed() {
        int totalIllegal = 0;
        for (int trial = 0; trial < OVERWORLD_STARVATION_TRIAL_COUNT; trial++) {
            long worldSeed = 2026L + trial;
            Map<MacroCellPos, BiomeId> result = solveAndRasterizeOverworld(worldSeed);
            RegionIntegrityValidator.Report report = RegionIntegrityValidator.validate(result, ModBiomeGraph.OVERWORLD);
            totalIllegal += report.illegalAdjacencies().size();
        }
        assertEquals(0, totalIllegal, "expected zero illegal adjacencies across every worldSeed in the standard starvation range (2026.." + (2025 + OVERWORLD_STARVATION_TRIAL_COUNT) + ")");
    }

    /**
     * Exact 30-seed range ({@code 5000..5029}) that originally found this whole arc's
     * founding bug (2026-09-04): 95 real illegal adjacencies from edges collapsing toward
     * zero length. Fixed in stages - minimum edge length (95->4-7), then the
     * minimum-realized-area floor added for user's own "one face still looks way too
     * small" follow-up report turned out to ALSO clear residual illegal adjacencies and
     * overlaps on this specific range entirely (0 illegal, 0 overlap-seeds), a real
     * measured side effect, not assumed. Locked in here as a permanent regression bound
     * on the exact range that mattered, not the whole 50-seed starvation range (which
     * still includes worldSeed=2031, the tracked still-open overlap bug - see {@link
     * #overworldHasAKnownFacePolygonOverlapBug}, a DIFFERENT range this test deliberately
     * avoids so it can assert something true today).
     */
    @Test
    void overworldHasZeroIllegalAdjacenciesOnTheFoundingBugSeedRange() {
        int totalIllegal = 0;
        for (long worldSeed = 5000; worldSeed < 5030; worldSeed++) {
            Map<MacroCellPos, BiomeId> result = solveAndRasterizeOverworld(worldSeed);
            RegionIntegrityValidator.Report report = RegionIntegrityValidator.validate(result, ModBiomeGraph.OVERWORLD);
            totalIllegal += report.illegalAdjacencies().size();
        }
        assertEquals(0, totalIllegal, "expected zero illegal adjacencies across worldSeed 5000..5029 - a real regression on the exact range that found this bug in the first place");
    }

    /**
     * User's own explicit standard (2026-09-06): "under or oversizing by over 10% is as
     * illegal as illegal adjacency" - same severity, same test style as {@link
     * #overworldHasZeroIllegalAdjacenciesAcrossEveryStarvationRangeSeed} (accumulate real
     * violations across many independent solves, assert zero at end), just checking
     * REALIZED AREA vs TARGET AREA per face instead of adjacency legality. Reuses the
     * same 10-seed, fresh-unconstrained-solve methodology as {@code
     * SizeFidelityMeasurement} (same seeds, same mesh box) - directly comparable to
     * numbers already reported from that tool, not a new sample.
     *
     * <p><b>Expected to currently FAIL, deliberately</b> - same convention this file uses
     * for a real tracked not-yet-fixed defect (see the historical face-polygon-overlap
     * bug this file's own git history closed the same way): measured directly, {@code
     * deep_lukewarm_ocean} realizes as low as ~0.31x its own target and {@code
     * jagged_peaks} as high as ~1.55x - both real, both already known, neither fixed yet.
     * Test exists so that gap is enforced in the suite, not just remembered in a
     * diagnostic tool's printed output - replace the {@code
     * assertTrue(violations.isEmpty(), ...)} expectation with a real zero once the fix
     * (self-adjacency prevalence and/or a predictive "would this hex actually fall short"
     * check) lands, never widen the 10% tolerance to make it pass instead.
     */
    private static final double SIZE_FIDELITY_TOLERANCE = 0.10;
    private static final int SIZE_FIDELITY_TRIAL_COUNT = 10;
    private static final long SIZE_FIDELITY_SEED_BASE = 2026L;
    private static final int SIZE_FIDELITY_HALF_WIDTH_BLOCKS = 3000;

    private record SizeFidelityViolation(long worldSeed, BiomeId biome, double ratio) {
    }

    @Test
    void overworldHasNoFaceOversizedOrUndersizedByMoreThanTenPercent() {
        double sizeBlocks = 325; // matches TriangleFieldGeometryRealization.HEX_SIZE_BLOCKS
        int minEdgeLengthBlocks = 24; // matches MIN_EDGE_LENGTH_MACRO_CELLS(6) * macroCellSizeBlocks(4)
        List<SizeFidelityViolation> violations = new ArrayList<>();
        int facesChecked = 0;

        for (int trial = 0; trial < SIZE_FIDELITY_TRIAL_COUNT; trial++) {
            long worldSeed = SIZE_FIDELITY_SEED_BASE + trial;
            HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(
                sizeBlocks, -SIZE_FIDELITY_HALF_WIDTH_BLOCKS, -SIZE_FIDELITY_HALF_WIDTH_BLOCKS,
                SIZE_FIDELITY_HALF_WIDTH_BLOCKS, SIZE_FIDELITY_HALF_WIDTH_BLOCKS
            );
            FaceAdjacency.Adjacency adjacency = FaceAdjacency.of(mesh.faces());
            Set<Integer> freeFaceIndices = nonHullTouchingFaceIndices(mesh, adjacency);

            double areaBlocks2 = (double) (2 * SIZE_FIDELITY_HALF_WIDTH_BLOCKS) * (2 * SIZE_FIDELITY_HALF_WIDTH_BLOCKS);
            Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments = TriangleIdentitySizeAssignment.solve(
                adjacency, freeFaceIndices, Map.of(), ModBiomeGraph.OVERWORLD, worldSeed, areaBlocks2
            ).orElseThrow(() -> new AssertionError("expected a real hex mesh at this scale to be solvable against ModBiomeGraph.OVERWORLD (worldSeed=" + worldSeed + ")"));
            Map<Integer, Point2D> displaced = WeightedVertexPlacement.displacedVertexPositions(mesh, adjacency, assignments, sizeBlocks, minEdgeLengthBlocks);

            for (int faceIndex : freeFaceIndices) {
                TriangleIdentitySizeAssignment.Assignment assignment = assignments.get(faceIndex);
                double realizedArea = shoelaceArea(mesh.faces().get(faceIndex).vertexIndices(), displaced);
                double targetArea = hexagonAreaForWidth(assignment.widthBlocks());
                double ratio = realizedArea / targetArea;
                facesChecked++;
                if (Math.abs(ratio - 1.0) > SIZE_FIDELITY_TOLERANCE) {
                    violations.add(new SizeFidelityViolation(worldSeed, assignment.biome(), ratio));
                }
            }
        }

        assertTrue(facesChecked > 500, "expected a real number of faces actually checked, got " + facesChecked);
        assertTrue(
            violations.isEmpty(),
            violations.size() + "/" + facesChecked + " faces violated the +/-" + (int) (SIZE_FIDELITY_TOLERANCE * 100)
                + "% size-fidelity bound - real, tracked, not-yet-fixed (see this test's own doc). Worst 10: "
                + violations.stream()
                    .sorted((a, b) -> Double.compare(Math.abs(a.ratio() - 1.0), Math.abs(b.ratio() - 1.0)) * -1)
                    .limit(10)
                    .map(v -> v.biome() + " ratio=" + String.format("%.2f", v.ratio()) + " (worldSeed=" + v.worldSeed() + ")")
                    .toList()
        );
    }

    /**
     * The real hard requirement itself, not the softer precision metric {@link
     * #overworldHasNoFaceOversizedOrUndersizedByMoreThanTenPercent} already tracks. That
     * test measures a face's REALIZED area against its own internally-ASSIGNED target -
     * useful for tuning, but assigned width already guaranteed legal ({@code
     * TriangleIdentitySizeAssignment}'s achievable-range pre-check intersects a candidate
     * biome's own {@link BiomeSizeRange} with what's geometrically achievable given
     * committed neighbors, hard-excluding anything that can't fit BEFORE it's picked).
     * Never checked anywhere in this suite: whether the REALIZED, player-visible geometry
     * - after {@code WeightedVertexPlacement}'s own {@code
     * MIN_REALIZED_AREA_FRACTION_OF_TARGET}/{@code MAX_REALIZED_AREA_FRACTION_OF_TARGET}
     * clamp, which bounds realized area only RELATIVE TO THE FACE'S OWN CHOSEN TARGET
     * (roughly -16%/+14% effective width), never against the biome's own absolute range
     * boundary - can still end up outside that range if the chosen target already sat
     * near an edge of it.
     *
     * <p><b>Measured by CLUSTER, not by individual face</b> - a {@link BiomeSizeRange}
     * describes one biome's own TOTAL felt patch size, not any single mesh face's share
     * of it. {@code BiomeNode.selfAdjacencyLimit=1} (desert + 3 of the 4 deep-ocean
     * variants) lets up to 2 mesh faces share one felt patch, deliberately splitting ONE
     * combined target evenly BY AREA between them ({@code
     * TriangleIdentitySizeAssignment.splitWidthForPair}, {@code eachFaceWidth =
     * combinedWidth / sqrt(2)}) - checking each half-face alone against the FULL range
     * (an earlier version of this test did exactly that) is wrong methodology for those
     * biomes: a legitimately-split half will always read as spuriously undersized. This
     * version flood-fills mutually-adjacent same-biome already-decided faces into
     * clusters (same approach {@code SizeFidelityMeasurement.sameBiomeClusters} already
     * established) and compares each cluster's COMBINED realized area (converted back to
     * one equivalent width via the same area-scales-as-width² relationship {@code
     * splitWidthForPair} itself relies on) against the range - correct for both a
     * singleton (cluster size 1, the ordinary case for every biome with {@code
     * selfAdjacencyLimit=0}) and a real pair alike.
     *
     * <p>Drives the REAL live path ({@link SeedMeshCache} -> {@code realizeCluster}, the
     * exact public entry point Minecraft itself calls) rather than the whole-mesh {@code
     * TriangleFieldGeometryRealization.realize}/{@code
     * WeightedVertexPlacement.displacedVertexPositions} path the other size test above
     * uses - that path confirmed dead in production (see that method's own doc), so a
     * test asserting a hard no-exception user requirement has to exercise the path
     * players actually reach. Builds ONE shared measurement mesh per seed (not a fresh
     * local mesh per face) so real mesh adjacency is available directly for clustering,
     * unlike a per-face rebuild would allow.
     */
    private static final int LEGAL_SIZE_RANGE_TRIAL_COUNT = 10;
    private static final long LEGAL_SIZE_RANGE_SEED_BASE = 2026L;
    private static final double LEGAL_SIZE_RANGE_HEX_SIZE_BLOCKS = 325; // matches TriangleFieldGeometryRealization.HEX_SIZE_BLOCKS
    private static final double LEGAL_SIZE_RANGE_AREA_HALF_WIDTH_BLOCKS = 1500;
    private static final double LEGAL_SIZE_RANGE_MEASUREMENT_MESH_PAD_BLOCKS = 3 * LEGAL_SIZE_RANGE_HEX_SIZE_BLOCKS;

    private record LegalRangeViolation(long worldSeed, BiomeId biome, int clusterSize, double realizedWidthBlocks, int minWidthBlocks, int maxWidthBlocks) {
    }

    /**
     * Explicit non-blocking diagnostic (not part of the fail-loud size/legality suite on
     * purpose - see {@link #overworldLivePathRealizedWidthNeverBreachesItsOwnBiomesLegalSizeRange}'s
     * doc for why THAT test is the real gate): sweeps broadly across many seeds to
     * collect every DISTINCT real reachable dead end in one pass, rather than discovering
     * them one crash at a time. Real result, 2026-09-11, 50-seed sweep: 28 distinct
     * fixed-neighbor-biome sets each triggered a real production crash - not yet reduced
     * to their own minimal culprit subset (each reported set is the FULL fixed context of
     * its cluster, a superset of whatever the true minimal conflicting combination is),
     * and not yet fixed - a real ongoing game-design task (which specific biomes should
     * legally border which), same category as the one concrete gap this session already
     * found, root-caused, and fixed (stony_peaks/snowy_beach/cold_ocean, see {@link
     * #overworldStonyPeaksSnowyBeachColdOceanPartialNeighborhoodGapIsFixed}).
     */
    @Test
    void diagnoseAllReachableDeadEndsAcrossManySeeds() {
        java.util.Set<java.util.Set<BiomeId>> distinctDeadEnds = new java.util.LinkedHashSet<>();
        double querySpacingBlocks = LEGAL_SIZE_RANGE_HEX_SIZE_BLOCKS * 0.9;
        for (int trial = 0; trial < 50; trial++) {
            long worldSeed = 2026L + trial;
            DecidedFaceStore faceStore = new DecidedFaceStore();
            DecidedVertexStore vertexStore = new DecidedVertexStore();
            SeedMeshCache cache = new SeedMeshCache(ModBiomeGraph.OVERWORLD, MacroGridConfig.DEFAULT, worldSeed, faceStore, vertexStore);
            for (double x = -LEGAL_SIZE_RANGE_AREA_HALF_WIDTH_BLOCKS; x <= LEGAL_SIZE_RANGE_AREA_HALF_WIDTH_BLOCKS; x += querySpacingBlocks) {
                for (double z = -LEGAL_SIZE_RANGE_AREA_HALF_WIDTH_BLOCKS; z <= LEGAL_SIZE_RANGE_AREA_HALF_WIDTH_BLOCKS; z += querySpacingBlocks) {
                    try {
                        cache.biomeAt(MacroGridConfig.DEFAULT.cellContaining(x, z));
                    } catch (IllegalStateException e) {
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("attempt2FixedFaceBiomes=\\{([^}]*)\\}").matcher(e.getMessage());
                        if (m.find()) {
                            java.util.Set<BiomeId> biomes = new java.util.TreeSet<>();
                            for (String entry : m.group(1).split(", ")) {
                                String[] kv = entry.split("=");
                                if (kv.length == 2) {
                                    String[] parts = kv[1].split(":");
                                    biomes.add(new BiomeId(parts[0], parts[1]));
                                }
                            }
                            distinctDeadEnds.add(biomes);
                        } else {
                            System.out.println("worldSeed=" + worldSeed + " unparsed dead end: " + e.getMessage());
                        }
                    }
                }
            }
        }
        System.out.println("distinctDeadEndFixedNeighborSets=" + distinctDeadEnds.size());
        distinctDeadEnds.forEach(System.out::println);
    }

    @Test
    void overworldLivePathRealizedWidthNeverBreachesItsOwnBiomesLegalSizeRange() {
        double querySpacingBlocks = LEGAL_SIZE_RANGE_HEX_SIZE_BLOCKS * 0.9;
        double pad = LEGAL_SIZE_RANGE_MEASUREMENT_MESH_PAD_BLOCKS;
        List<LegalRangeViolation> violations = new ArrayList<>();
        int clustersChecked = 0;

        for (int trial = 0; trial < LEGAL_SIZE_RANGE_TRIAL_COUNT; trial++) {
            long worldSeed = LEGAL_SIZE_RANGE_SEED_BASE + trial;
            DecidedFaceStore faceStore = new DecidedFaceStore();
            DecidedVertexStore vertexStore = new DecidedVertexStore();
            SeedMeshCache cache = new SeedMeshCache(ModBiomeGraph.OVERWORLD, MacroGridConfig.DEFAULT, worldSeed, faceStore, vertexStore);

            for (double x = -LEGAL_SIZE_RANGE_AREA_HALF_WIDTH_BLOCKS; x <= LEGAL_SIZE_RANGE_AREA_HALF_WIDTH_BLOCKS; x += querySpacingBlocks) {
                for (double z = -LEGAL_SIZE_RANGE_AREA_HALF_WIDTH_BLOCKS; z <= LEGAL_SIZE_RANGE_AREA_HALF_WIDTH_BLOCKS; z += querySpacingBlocks) {
                    cache.biomeAt(MacroGridConfig.DEFAULT.cellContaining(x, z));
                }
            }

            HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(
                LEGAL_SIZE_RANGE_HEX_SIZE_BLOCKS,
                (int) Math.floor(-LEGAL_SIZE_RANGE_AREA_HALF_WIDTH_BLOCKS - pad), (int) Math.floor(-LEGAL_SIZE_RANGE_AREA_HALF_WIDTH_BLOCKS - pad),
                (int) Math.ceil(LEGAL_SIZE_RANGE_AREA_HALF_WIDTH_BLOCKS + pad), (int) Math.ceil(LEGAL_SIZE_RANGE_AREA_HALF_WIDTH_BLOCKS + pad)
            );
            FaceAdjacency.Adjacency adjacency = FaceAdjacency.of(mesh.faces());

            Map<Integer, BiomeId> biomeByFaceIndex = new HashMap<>();
            Map<Integer, Double> areaByFaceIndex = new HashMap<>();
            for (int faceIndex = 0; faceIndex < mesh.faces().size(); faceIndex++) {
                if (adjacency.hullTouchingFaceIndices().contains(faceIndex)) {
                    continue;
                }
                HexGrid.AxialPos axialPos = mesh.faces().get(faceIndex).axialPos();
                Optional<DecidedFaceStore.DecidedFace> decided = faceStore.get(axialPos);
                if (decided.isEmpty()) {
                    continue;
                }
                Optional<Double> realizedArea = committedFaceAreaOf(mesh, adjacency, faceIndex, vertexStore);
                if (realizedArea.isEmpty()) {
                    continue;
                }
                biomeByFaceIndex.put(faceIndex, decided.get().biomeId());
                areaByFaceIndex.put(faceIndex, realizedArea.get());
            }

            Set<Integer> visited = new HashSet<>();
            for (int start : biomeByFaceIndex.keySet()) {
                if (!visited.add(start)) {
                    continue;
                }
                BiomeId biome = biomeByFaceIndex.get(start);
                List<Integer> members = new ArrayList<>();
                java.util.Deque<Integer> frontier = new java.util.ArrayDeque<>();
                frontier.add(start);
                while (!frontier.isEmpty()) {
                    int current = frontier.poll();
                    members.add(current);
                    for (int neighbor : adjacency.neighborFaceIndices().getOrDefault(current, Set.of())) {
                        if (visited.contains(neighbor) || !biome.equals(biomeByFaceIndex.get(neighbor))) {
                            continue;
                        }
                        visited.add(neighbor);
                        frontier.add(neighbor);
                    }
                }

                double totalArea = members.stream().mapToDouble(areaByFaceIndex::get).sum();
                double combinedWidthBlocks = widthFromHexagonArea(totalArea);
                BiomeSizeRange range = ModBiomeGraph.OVERWORLD.node(biome).sizeRange();
                clustersChecked++;
                if (combinedWidthBlocks < range.minWidthBlocks() || combinedWidthBlocks > range.maxWidthBlocks()) {
                    violations.add(new LegalRangeViolation(worldSeed, biome, members.size(), combinedWidthBlocks, range.minWidthBlocks(), range.maxWidthBlocks()));
                }
            }
        }

        assertTrue(clustersChecked > 150, "expected a real number of clusters actually measured, got " + clustersChecked);
        assertTrue(
            violations.isEmpty(),
            violations.size() + "/" + clustersChecked + " biome patches REALIZED outside their own biome's legal size range "
                + "(combined-by-cluster, not just off-target - this is the hard, no-exception requirement). Worst offenders: "
                + violations.stream()
                    .sorted((a, b) -> Double.compare(
                        Math.max(a.minWidthBlocks() - a.realizedWidthBlocks(), a.realizedWidthBlocks() - a.maxWidthBlocks()),
                        Math.max(b.minWidthBlocks() - b.realizedWidthBlocks(), b.realizedWidthBlocks() - b.maxWidthBlocks())
                    ) * -1)
                    .limit(10)
                    .map(v -> v.biome() + " realized=" + String.format("%.0f", v.realizedWidthBlocks()) + " (clusterSize=" + v.clusterSize() + ")"
                        + " legalRange=" + v.minWidthBlocks() + "-" + v.maxWidthBlocks() + " (worldSeed=" + v.worldSeed() + ")")
                    .toList()
        );
    }

    /**
     * This face's REAL committed area, read directly from {@code vertexStore} against
     * the SHARED per-seed measurement {@code mesh}/{@code adjacency} (not a fresh
     * per-face rebuild - needed so real mesh adjacency available for clustering). Empty
     * if any of this face's own 6 corners isn't committed yet (ordinary possibility - a
     * corner only commits once all 3 owning faces have some value).
     */
    private static Optional<Double> committedFaceAreaOf(HexMeshBuilder.HexMesh mesh, FaceAdjacency.Adjacency adjacency, int faceIndex, DecidedVertexStore vertexStore) {
        Set<Integer> ring = new LinkedHashSet<>();
        ring.add(faceIndex);
        ring.addAll(adjacency.neighborFaceIndices().getOrDefault(faceIndex, Set.of()));

        Map<Integer, Point2D> positionByVertexIndex = new HashMap<>();
        for (int[] triangle : adjacency.mutuallyAdjacentFaceTriples(ring)) {
            if (triangle[0] != faceIndex && triangle[1] != faceIndex && triangle[2] != faceIndex) {
                continue;
            }
            int vertexIndex = FaceAdjacency.sharedVertexOf(mesh, triangle);
            VertexKey key = VertexKey.of(mesh.faces().get(triangle[0]), mesh.faces().get(triangle[1]), mesh.faces().get(triangle[2]));
            Optional<Point2D> position = vertexStore.get(key);
            if (position.isEmpty()) {
                return Optional.empty();
            }
            positionByVertexIndex.put(vertexIndex, position.get());
        }

        List<Point2D> corners = new ArrayList<>();
        for (int vertexIndex : mesh.faces().get(faceIndex).vertexIndices()) {
            Point2D position = positionByVertexIndex.get(vertexIndex);
            if (position == null) {
                return Optional.empty();
            }
            corners.add(position);
        }
        return Optional.of(shoelaceArea(corners));
    }

    private static double shoelaceArea(List<Point2D> corners) {
        double sum = 0;
        int n = corners.size();
        for (int i = 0; i < n; i++) {
            Point2D a = corners.get(i);
            Point2D b = corners.get((i + 1) % n);
            sum += a.x() * b.z() - b.x() * a.z();
        }
        return Math.abs(sum) / 2.0;
    }

    /** Inverse of {@code targetArea}/{@code hexagonAreaForWidth}: width is a regular hexagon's own corner-to-corner span, area = (3*sqrt(3)/2) * (width/2)^2. */
    private static double widthFromHexagonArea(double areaBlocks2) {
        double circumradius = Math.sqrt(areaBlocks2 / (3.0 * Math.sqrt(3.0) / 2.0));
        return circumradius * 2.0;
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

    /** Same convention {@code WeightedVertexPlacement.powerWeight}/{@code targetAreaBlocks2} use: width is a regular hexagon's own corner-to-corner span, circumradius = width/2. */
    private static double hexagonAreaForWidth(int widthBlocks) {
        double circumradius = widthBlocks / 2.0;
        return (3.0 * Math.sqrt(3.0) / 2.0) * circumradius * circumradius;
    }

    private static Map<MacroCellPos, BiomeId> solveAndRasterizeOverworld(long worldSeed) {
        double sizeBlocks = 325; // matches TriangleFieldGeometryRealization.HEX_SIZE_BLOCKS - kept in sync manually, see that constant's own doc
        int meshBoxMax = 9000;
        int realMin = 1500;
        int realMax = 7500;
        MacroGridConfig gridConfig = new MacroGridConfig(4, 512);

        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(sizeBlocks, 0, 0, meshBoxMax, meshBoxMax);
        FaceAdjacency.Adjacency adjacency = FaceAdjacency.of(mesh.faces());
        Set<Integer> freeFaceIndices = nonHullTouchingFaceIndices(mesh, adjacency);
        assertTrue(freeFaceIndices.size() > 50, "expected a real-sized mesh, got " + freeFaceIndices.size() + " free faces");

        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments = TriangleIdentitySizeAssignment.solve(
            adjacency, freeFaceIndices, Map.of(), ModBiomeGraph.OVERWORLD, worldSeed
        ).orElseThrow(() -> new AssertionError("expected a real hex mesh at this scale to be solvable against ModBiomeGraph.OVERWORLD (worldSeed=" + worldSeed + ")"));

        Map<Integer, Point2D> displacedVertices = WeightedVertexPlacement.displacedVertexPositions(mesh, adjacency, assignments, sizeBlocks, 6 * gridConfig.macroCellSizeBlocks()); // matches TriangleFieldGeometryRealization.MIN_EDGE_LENGTH_MACRO_CELLS
        List<MacroCellPos> cells = overworldTestCells(gridConfig, realMin, realMin, realMax, realMax);
        return EdgeDisplacementRasterizer.rasterize(cells, assignments, mesh, adjacency, displacedVertices, Map.of(), Map.of(), gridConfig, worldSeed);
    }

    /**
     * Real replacement for the abstract ring check ({@link
     * BiomeGraph#requireEveryLocalNeighborhoodSolvable}'s doc explains why that check,
     * even in its corrected realizable-ring form, still tests far more than the CSP could
     * ever reach at real biome-catalog scale - measured directly: 47,623 of 201,552
     * realizable 6-rings in this exact graph have no universal candidate, not evidence
     * anything is actually broken). This test instead explores REACHABLE local
     * neighborhoods directly: take a real fully-solved mesh (so every face's surrounding
     * context is genuine, not hypothetical), then for EVERY non-hull face, re-solve that
     * face plus its own real mesh neighbors together (a small cluster, matching a face's
     * real hex neighborhood) against real fixed context from everything just outside that
     * cluster, using a DIFFERENT worldSeed per cluster so the CSP explores a genuinely
     * different candidate ordering than the one that built the base mesh - "replace this
     * neighborhood with another legal one and continue" for every real neighborhood in a
     * real mesh, not an abstract combinatorial superset. A failure here names a real
     * reproducible dead end - an actual reachable local arrangement, not a hypothetical
     * one.
     *
     * <p>Repeats across {@code BASE_GENERATION_COUNT} independent base generations, not
     * just one - a single base solve only ever produces the ONE specific set of real
     * neighborhoods its own candidate ordering happened to build; a different base seed
     * produces a genuinely different set of real neighborhoods to check, so more base
     * generations means more DISTINCT real neighborhoods actually tested, not just more
     * re-checks of the same ones.
     */
    private static final int OVERWORLD_REACHABILITY_BASE_GENERATION_COUNT = 5;

    @Test
    void overworldSurvivesLocalReassignmentAcrossEveryRealNeighborhood() {
        HexMeshBuilder.HexMesh mesh = buildRealStressTestMesh();
        FaceAdjacency.Adjacency adjacency = FaceAdjacency.of(mesh.faces());
        Set<Integer> freeFaceIndices = nonHullTouchingFaceIndices(mesh, adjacency);
        assertTrue(freeFaceIndices.size() > 50, "expected a real-sized mesh, got " + freeFaceIndices.size() + " free faces");

        int checkedClusters = 0;
        for (int baseGeneration = 0; baseGeneration < OVERWORLD_REACHABILITY_BASE_GENERATION_COUNT; baseGeneration++) {
            long baseSeed = 2026L + baseGeneration * 1_000_003L; // spread bases well apart, not adjacent to the trial seeds each derives
            Map<Integer, BiomeId> baseBiomes = solveRealBaseMesh(adjacency, freeFaceIndices, baseSeed);

            for (int center : freeFaceIndices) {
                Set<Integer> cluster = clusterAround(center, adjacency, freeFaceIndices);
                Map<Integer, BiomeId> fixedContext = fixedContextSurrounding(cluster, adjacency, baseBiomes);
                assertClusterHasALegalAssignment(adjacency, cluster, fixedContext, trialSeedFor(baseSeed, center));
                checkedClusters++;
            }
        }
        assertTrue(checkedClusters > 50, "expected a real number of neighborhoods actually checked, got " + checkedClusters);
    }

    private static HexMeshBuilder.HexMesh buildRealStressTestMesh() {
        double sizeBlocks = 325; // matches TriangleFieldGeometryRealization.HEX_SIZE_BLOCKS - kept in sync manually, see that constant's own doc
        int meshBoxMax = 9000;
        return HexMeshBuilder.build(sizeBlocks, 0, 0, meshBoxMax, meshBoxMax);
    }

    private static Set<Integer> nonHullTouchingFaceIndices(HexMeshBuilder.HexMesh mesh, FaceAdjacency.Adjacency adjacency) {
        Set<Integer> freeFaceIndices = new HashSet<>();
        for (int faceIndex = 0; faceIndex < mesh.faces().size(); faceIndex++) {
            if (!adjacency.hullTouchingFaceIndices().contains(faceIndex)) {
                freeFaceIndices.add(faceIndex);
            }
        }
        return freeFaceIndices;
    }

    /** One real fully-solved mesh, collapsed down to just its per-face biome - the genuine surrounding context every cluster below gets stress-tested against. */
    private static Map<Integer, BiomeId> solveRealBaseMesh(FaceAdjacency.Adjacency adjacency, Set<Integer> freeFaceIndices, long baseSeed) {
        Map<Integer, TriangleIdentitySizeAssignment.Assignment> baseAssignments = TriangleIdentitySizeAssignment.solve(
            adjacency, freeFaceIndices, Map.of(), ModBiomeGraph.OVERWORLD, baseSeed
        ).orElseThrow(() -> new AssertionError("base mesh itself failed to solve - can't stress-test neighborhoods that don't exist"));
        Map<Integer, BiomeId> baseBiomes = new HashMap<>();
        baseAssignments.forEach((faceIndex, assignment) -> baseBiomes.put(faceIndex, assignment.biome()));
        return baseBiomes;
    }

    /** A face plus its own real mesh neighbors - a face's own real hex neighborhood - restricted to non-hull-touching faces, since re-deciding a hull-touching neighbor isn't this test's concern. */
    private static Set<Integer> clusterAround(int center, FaceAdjacency.Adjacency adjacency, Set<Integer> freeFaceIndices) {
        Set<Integer> cluster = new HashSet<>(adjacency.neighborFaceIndices().getOrDefault(center, Set.of()));
        cluster.add(center);
        cluster.retainAll(freeFaceIndices);
        return cluster;
    }

    /** Every real neighbor of the cluster that isn't itself part of it, held at its base-solve biome - the genuine surrounding context the cluster must stay legal against. */
    private static Map<Integer, BiomeId> fixedContextSurrounding(Set<Integer> cluster, FaceAdjacency.Adjacency adjacency, Map<Integer, BiomeId> baseBiomes) {
        Map<Integer, BiomeId> fixedContext = new HashMap<>();
        for (int clusterFace : cluster) {
            for (int outerNeighbor : adjacency.neighborFaceIndices().getOrDefault(clusterFace, Set.of())) {
                if (!cluster.contains(outerNeighbor)) {
                    fixedContext.put(outerNeighbor, baseBiomes.get(outerNeighbor));
                }
            }
        }
        return fixedContext;
    }

    /** Different per cluster, and different from {@code baseSeed} itself - so the CSP explores a genuinely different candidate ordering than whatever built the base mesh, not just re-deriving the same answer. */
    private static long trialSeedFor(long baseSeed, int center) {
        return baseSeed + center + 1;
    }

    private static void assertClusterHasALegalAssignment(
        FaceAdjacency.Adjacency adjacency, Set<Integer> cluster, Map<Integer, BiomeId> fixedContext, long trialSeed
    ) {
        Optional<Map<Integer, TriangleIdentitySizeAssignment.Assignment>> resolved = TriangleIdentitySizeAssignment.solve(
            adjacency, cluster, fixedContext, ModBiomeGraph.OVERWORLD, trialSeed
        );
        assertTrue(
            resolved.isPresent(),
            "real neighborhood " + cluster + " (real fixed context " + fixedContext + ") has no legal assignment - a genuine, reachable dead end"
        );
    }

    private static List<MacroCellPos> overworldTestCells(MacroGridConfig gridConfig, int minX, int minZ, int maxX, int maxZ) {
        int cellSize = gridConfig.macroCellSizeBlocks();
        int minCellX = Math.floorDiv(minX, cellSize);
        int maxCellX = Math.floorDiv(maxX - 1, cellSize);
        int minCellZ = Math.floorDiv(minZ, cellSize);
        int maxCellZ = Math.floorDiv(maxZ - 1, cellSize);
        List<MacroCellPos> cells = new ArrayList<>();
        for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
            for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                cells.add(new MacroCellPos(cellX, cellZ));
            }
        }
        return cells;
    }
}
