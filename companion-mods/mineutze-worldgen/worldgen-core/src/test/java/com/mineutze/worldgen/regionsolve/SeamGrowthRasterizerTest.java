package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.AdjacencyRule;
import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import static com.mineutze.worldgen.regionsolve.TestBiomeNodes.id;
import static com.mineutze.worldgen.regionsolve.TestBiomeNodes.node;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct small-fixture tests for the growth primitives ({@code isClaimable}, {@code
 * offerNeighbors}, {@code bestFaceFor}, {@code wouldPinchAnAlreadyDecidedNeighbor}) that
 * were previously only reachable through a full {@link TriangleFieldGeometryRealization}
 * pipeline run or a real production export - see the test-coverage audit this session
 * that flagged this gap. These four became package-private specifically so this file
 * could exist.
 */
class SeamGrowthRasterizerTest {

    private static final BiomeId DESERT = id("desert");
    private static final BiomeId SAVANNA = id("savanna");
    private static final BiomeId OCEAN = id("ocean");

    /**
     * OCEAN deliberately isn't a registered node here - it only needs to exist as a
     * {@link BiomeId} so {@code isAllowed} correctly default-denies it (no rule matches),
     * without also having to give it a real bridging path to satisfy {@link
     * BiomeGraph}'s own construction-time reachability check (it's only used as a
     * "some illegal neighbor" stand-in in these tests, never a real assigned biome).
     */
    private static BiomeGraph graph() {
        return new BiomeGraph(
            List.of(node("desert", 100, 200), node("savanna", 100, 200)),
            List.of(AdjacencyRule.allow(DESERT, SAVANNA, 10))
        );
    }

    private static SeamGrowthRasterizer.FaceBookkeeping bookkeeping(int faceIndex, int claimedCount, int target, MacroCellPos seed) {
        Map<Integer, Integer> targetCells = new HashMap<>(Map.of(faceIndex, target));
        Map<Integer, Integer> claimedCountByFace = new HashMap<>(Map.of(faceIndex, claimedCount));
        Map<Integer, MacroCellPos> seedCellByFace = new HashMap<>(Map.of(faceIndex, seed));
        return new SeamGrowthRasterizer.FaceBookkeeping(targetCells, claimedCountByFace, seedCellByFace, Map.of());
    }

    @Test
    void isClaimableRejectsAnIllegalAdjacency() {
        MacroCellPos cell = new MacroCellPos(0, 0);
        Map<MacroCellPos, BiomeId> decided = new HashMap<>();
        decided.put(new MacroCellPos(1, 0), OCEAN); // Moore neighbor, desert-ocean has no rule

        boolean claimable = SeamGrowthRasterizer.isClaimable(cell, DESERT, 1, decided, bookkeeping(1, 0, 10, cell), graph());

        assertFalse(claimable, "desert has no AdjacencyRule against ocean - claiming next to it must be rejected");
    }

    @Test
    void isClaimableExemptsCohesionBelowMinSameBiomeNeighbors() {
        MacroCellPos cell = new MacroCellPos(0, 0);
        Map<MacroCellPos, BiomeId> decided = new HashMap<>(); // zero same-biome neighbors anywhere
        // claimedCountByFace < CellPlacementRules.MIN_SAME_BIOME_NEIGHBORS - the bootstrap window
        SeamGrowthRasterizer.FaceBookkeeping bookkeeping = bookkeeping(1, 0, 10, cell);

        boolean claimable = SeamGrowthRasterizer.isClaimable(cell, DESERT, 1, decided, bookkeeping, graph());

        assertTrue(claimable, "a face below MIN_SAME_BIOME_NEIGHBORS claimed cells can't possibly satisfy cohesion yet (pigeonhole) - must be exempt, not rejected");
    }

    @Test
    void isClaimableEnforcesCohesionOnceMinSameBiomeNeighborsReached() {
        MacroCellPos cell = new MacroCellPos(0, 0);
        Map<MacroCellPos, BiomeId> decided = new HashMap<>(); // zero same-biome neighbors - fails cohesion
        // claimedCountByFace already AT the threshold - exemption window has closed
        SeamGrowthRasterizer.FaceBookkeeping bookkeeping = bookkeeping(1, CellPlacementRules.MIN_SAME_BIOME_NEIGHBORS, 10, cell);

        boolean claimable = SeamGrowthRasterizer.isClaimable(cell, DESERT, 1, decided, bookkeeping, graph());

        assertFalse(claimable, "past the bootstrap window, a cell with no same-biome Moore support must fail cohesion");
    }

    @Test
    void isClaimableRejectsACompletedPinch() {
        MacroCellPos cell = new MacroCellPos(0, 0);
        Map<MacroCellPos, BiomeId> decided = new HashMap<>();
        decided.put(new MacroCellPos(0, -1), SAVANNA); // orthogonal neighbor, legal but different biome
        decided.put(new MacroCellPos(0, -2), OCEAN); // that neighbor's own opposite-side cell, also different from it
        // Still in the bootstrap window so cohesion doesn't also reject this - isolates the pinch check.
        SeamGrowthRasterizer.FaceBookkeeping bookkeeping = bookkeeping(1, 0, 10, cell);

        boolean claimable = SeamGrowthRasterizer.isClaimable(cell, DESERT, 1, decided, bookkeeping, graph());

        assertFalse(claimable, "both sides of the north-south axis are already decided and different from the neighbor - claiming here completes a pinch");
    }

    @Test
    void wouldPinchDetectsBothSidesDecidedAndDifferent() {
        MacroCellPos cell = new MacroCellPos(0, 0);
        Map<MacroCellPos, BiomeId> decided = new HashMap<>();
        decided.put(new MacroCellPos(0, -1), SAVANNA);
        decided.put(new MacroCellPos(0, -2), OCEAN);

        assertTrue(SeamGrowthRasterizer.wouldPinchAnAlreadyDecidedNeighbor(cell, DESERT, decided));
    }

    @Test
    void wouldPinchIsConservativeWhenTheOppositeSideIsStillUndecided() {
        MacroCellPos cell = new MacroCellPos(0, 0);
        Map<MacroCellPos, BiomeId> decided = new HashMap<>();
        decided.put(new MacroCellPos(0, -1), SAVANNA); // opposite side (0, -2) left undecided

        assertFalse(SeamGrowthRasterizer.wouldPinchAnAlreadyDecidedNeighbor(cell, DESERT, decided),
            "an undecided opposite side might still end up same-biome once growth reaches it - must not guess");
    }

    @Test
    void wouldPinchIgnoresASameBiomeNeighbor() {
        MacroCellPos cell = new MacroCellPos(0, 0);
        Map<MacroCellPos, BiomeId> decided = new HashMap<>();
        decided.put(new MacroCellPos(0, -1), DESERT); // same biome as the candidate - can't be newly pinched
        decided.put(new MacroCellPos(0, -2), OCEAN);

        assertFalse(SeamGrowthRasterizer.wouldPinchAnAlreadyDecidedNeighbor(cell, DESERT, decided));
    }

    @Test
    void bestFaceForPrefersTheMostBehindQuota() {
        MacroCellPos cell = new MacroCellPos(0, 0);
        Map<Integer, Integer> targetCells = new HashMap<>(Map.of(1, 10, 2, 10));
        Map<Integer, Integer> claimedCountByFace = new HashMap<>(Map.of(1, 5, 2, 1)); // ratios 0.5 vs 0.1
        Map<Integer, MacroCellPos> seedCellByFace = new HashMap<>(Map.of(1, cell, 2, cell));
        SeamGrowthRasterizer.FaceBookkeeping bookkeeping = new SeamGrowthRasterizer.FaceBookkeeping(targetCells, claimedCountByFace, seedCellByFace, Map.of());

        int best = SeamGrowthRasterizer.bestFaceFor(List.of(1, 2), cell, bookkeeping);

        assertEquals(2, best, "face 2 is further behind its own target (ratio 0.1 vs 0.5) and should win");
    }

    @Test
    void bestFaceForBreaksATieByNearestSeedDistance() {
        MacroCellPos cell = new MacroCellPos(1, 0);
        Map<Integer, Integer> targetCells = new HashMap<>(Map.of(1, 10, 2, 10));
        Map<Integer, Integer> claimedCountByFace = new HashMap<>(Map.of(1, 2, 2, 2)); // equal ratios
        Map<Integer, MacroCellPos> seedCellByFace = new HashMap<>(Map.of(1, new MacroCellPos(0, 0), 2, new MacroCellPos(5, 5)));
        SeamGrowthRasterizer.FaceBookkeeping bookkeeping = new SeamGrowthRasterizer.FaceBookkeeping(targetCells, claimedCountByFace, seedCellByFace, Map.of());

        int best = SeamGrowthRasterizer.bestFaceFor(List.of(1, 2), cell, bookkeeping);

        assertEquals(1, best, "ratios tie - face 1's seed is nearer to the cell and should win the tie-break");
    }

    @Test
    void offerNeighborsPushesBothTheClaimingFaceAndTheBestOtherLegalBiome() {
        MacroCellPos claimedCell = new MacroCellPos(0, 0);
        MacroCellPos neighbor = new MacroCellPos(1, 0);
        Map<MacroCellPos, BiomeId> decided = new HashMap<>();
        decided.put(claimedCell, DESERT);
        Set<MacroCellPos> regionCellSet = new HashSet<>(claimedCell.mooreNeighbors());
        regionCellSet.add(claimedCell);

        Map<Integer, Integer> targetCells = new HashMap<>(Map.of(1, 10, 2, 10));
        Map<Integer, Integer> claimedCountByFace = new HashMap<>(Map.of(1, 1, 2, 0));
        Map<Integer, MacroCellPos> seedCellByFace = new HashMap<>(Map.of(1, claimedCell, 2, new MacroCellPos(2, 0)));
        // Deliberately excludes DESERT's own face group - offerNeighbors' unconditional
        // "push the claiming face itself" step must not depend on facesByBiome at all.
        Map<BiomeId, List<Integer>> facesByBiome = Map.of(SAVANNA, List.of(2));
        SeamGrowthRasterizer.FaceBookkeeping bookkeeping = new SeamGrowthRasterizer.FaceBookkeeping(targetCells, claimedCountByFace, seedCellByFace, facesByBiome);

        PriorityQueue<SeamGrowthRasterizer.CellEntry> queue = new PriorityQueue<>(
            Comparator.comparingDouble(SeamGrowthRasterizer.CellEntry::quotaRatio)
        );
        SeamGrowthRasterizer.offerNeighbors(claimedCell, DESERT, 1, decided, regionCellSet, bookkeeping, graph(), queue);

        List<SeamGrowthRasterizer.CellEntry> forNeighbor = new ArrayList<>(queue).stream()
            .filter(entry -> entry.cell().equals(neighbor))
            .toList();
        assertTrue(forNeighbor.stream().anyMatch(entry -> entry.faceIndex() == 1), "the claiming face's own continuation must be offered");
        assertTrue(forNeighbor.stream().anyMatch(entry -> entry.faceIndex() == 2), "the best other legal-biome face (savanna) must also be offered");
    }
}
