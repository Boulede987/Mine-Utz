package com.mineutze.worldgen.regionsolve.topology.identity;

import com.mineutze.worldgen.regionsolve.topology.EdgeDisplacement;

import com.mineutze.worldgen.regionsolve.topology.Salt;

import com.mineutze.worldgen.regionsolve.topology.placement.WeightedVertexPlacement;

import com.mineutze.worldgen.regionsolve.topology.mesh.FaceAdjacency;

import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeSizeRange;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Draws a width for a face whose identity is already decided - zero identity/legality
 * concern, see {@link TriangleIdentitySizeAssignment} (only caller) for how identity gets
 * decided first via {@link FaceIdentityCsp}. Deliberately geometry-agnostic: never imports
 * real hexagon area math (see {@link #BASELINE_WIDTH_BLOCKS}), only reasons in width blocks.
 */
final class FaceWidthPicker {

    private static final long SIZE_PICK_SALT = Salt.of("TriangleIdentitySizeAssignment.sizePick");
    // Separate key space from SIZE_PICK_SALT on purpose - self-adjacent pair's combined
    // width drawn once, keyed by BOTH faces canonically (see splitWidthForPair), never by
    // a single faceIndex like an independent pick - reusing SIZE_PICK_SALT would risk a
    // paired face's split width correlating with its own independent pick, not meaningful.
    private static final long PAIR_SIZE_PICK_SALT = Salt.of("TriangleIdentitySizeAssignment.pairSizePick");

    /** Classic hash-spreading multiplier (same convention as {@code java.util.Objects.hash}, and {@code EdgeDisplacement}/{@code WeightedVertexPlacement}'s {@code HASH_MULTIPLIER}). */
    private static final long HASH_MULTIPLIER = 31;

    // A self-adjacent pair's combined width (drawn once, see splitWidthForPair) is split
    // between the two members ONLY as asymmetrically as real local geometry actually demands
    // (see splitByAchievableCapacity) - real user decision (2026-09-13): the pair represents
    // ONE felt region whose members' widths merely SUM into the biome's declared [min,max]
    // range, not two equal halves, but asymmetry should be NEEDS-DRIVEN, not random (a
    // member squeezed by real committed external neighbors gets only what it can actually
    // hold; its partner absorbs the rest) - not a decorative variance generator. This
    // constant is the absolute floor either member can ever be pushed to, real squeeze or
    // not - without one, a degenerate split could hand one member a near-zero or negative
    // width. 50 blocks: comfortably above the geometry layer's own absolute minimum edge
    // length (MIN_EDGE_LENGTH_MACRO_CELLS * macroCellSizeBlocks, ~24 blocks at today's
    // MacroGridConfig.DEFAULT) - first cut, tune from real measurement like every other size
    // constant in this codebase.
    private static final int MIN_GROUP_MEMBER_WIDTH_BLOCKS = 50;

    // Matches WeightedVertexPlacement's baseline circumradius (HEX_SIZE_BLOCKS=325, see
    // TriangleFieldGeometryRealization for why 650) - reference point a face's chosen
    // width is implicitly measured against once geometry runs. Duplicated (not imported -
    // stays geometry-agnostic otherwise) just so pickWidth can tell if a biome's WHOLE
    // range sits below it.
    private static final double BASELINE_WIDTH_BLOCKS = 650;
    // Skews uniform [0,1) draw toward 1 via Math.pow(uniform, BIAS_EXPONENT) - 0.5 moves
    // median from 0.5 to ~0.71, real but not total skew (still real variance, not "always
    // max"). Tune from real SizeFidelityMeasurement/fail-loud-test numbers, not guessed -
    // same discipline as other size/geometry constants.
    private static final double SIZE_PICK_BASELINE_BIAS_EXPONENT = 0.5;

    private FaceWidthPicker() {
    }

    /**
     * A paired face's own absolute legal range (used by {@code
     * TriangleIdentitySizeAssignment#buildResult} for {@code legalMinWidthBlocks}/{@code
     * legalMaxWidthBlocks}) - checking a member against the FULL combined range would be
     * wrong (a real bug an earlier live-path legal-size test actually had), but under the
     * asymmetric sum split neither member has a fixed share to check against either. This
     * is the LOOSEST bound consistent with the real constraint (both members' widths sum to
     * at most {@code combinedRange}'s max, and this member's own partner must get at least
     * {@link #MIN_GROUP_MEMBER_WIDTH_BLOCKS}) - real per-instance legality is enforced by
     * {@link #splitWidthForPair}'s own draw, not by this bound alone.
     */
    static int pairedMemberLegalMinWidthBlocks(int combinedMinWidthBlocks, int combinedMaxWidthBlocks) {
        return Math.min(MIN_GROUP_MEMBER_WIDTH_BLOCKS, combinedMaxWidthBlocks / 2);
    }

    /** See {@link #pairedMemberLegalMinWidthBlocks} - symmetric ceiling. */
    static int pairedMemberLegalMaxWidthBlocks(int combinedMaxWidthBlocks) {
        return Math.max(combinedMaxWidthBlocks - MIN_GROUP_MEMBER_WIDTH_BLOCKS, combinedMaxWidthBlocks / 2);
    }

    /**
     * A biome whose ENTIRE range sits below {@link #BASELINE_WIDTH_BLOCKS} has its pick
     * skewed toward its MAXIMUM instead of plain uniform. At/above baseline stays unbiased.
     * See {@link #biasFor} for the committed-neighbor signal. See DECISIONS.md 2026-09-11
     * "pickWidth biases its draw" for the measured evidence.
     */
    static int pickWidth(
        BiomeSizeRange range, long worldSeed, int faceIndex, BiomeId biome,
        FaceAdjacency.Adjacency adjacency, Map<Integer, Integer> committedWidthBlocksByFace
    ) {
        long hash = worldSeed ^ SIZE_PICK_SALT;
        hash = hash * HASH_MULTIPLIER + faceIndex;
        hash = hash * HASH_MULTIPLIER + biome.hashCode();
        List<Integer> committedNeighborWidths = new ArrayList<>();
        collectCommittedNeighborWidths(faceIndex, adjacency, committedWidthBlocksByFace, committedNeighborWidths);
        return pickWidthFromHash(range, hash, biasFor(range, committedNeighborWidths));
    }

    /**
     * Draws ONE combined width for a self-adjacent pair (same mechanism as {@link
     * #pickWidth}, from the SAME biome {@code range}, keyed canonically over both faces so
     * identical regardless of which face is visited first) and splits it between the two
     * members - see class doc: two self-adjacent faces read as ONE felt region across two
     * hexes, not two independently-full-target instances (strictly worse - two full demands
     * on the local neighborhood instead of one).
     *
     * <p>Real user decision (2026-09-13, refined): the pair's two widths merely need to SUM
     * into the biome's declared {@code [min,max]} range, not each independently occupy an
     * equal (previously: area-equal, {@code combined/sqrt(2)} each) share - BUT the split
     * itself is NEEDS-DRIVEN, not random (see {@link #splitByAchievableCapacity}): a member
     * only gets pushed toward the floor when its OWN real, already-committed external
     * neighbors genuinely can't spare more room, and its partner absorbs the rest. Two
     * members with no real external squeeze split evenly, same as before this change - a
     * self-adjacent pair is a completability escape hatch for a genuinely tight local
     * neighborhood, not a decorative size-variance generator.
     *
     * @param faceAchievableRange   {@code faceIndex}'s own real achievable width range from
     *                              its EXTERNAL committed neighbors (excludes {@code
     *                              partnerFaceIndex} by construction - a fellow free face in
     *                              this same batch, never itself "committed" yet) - {@code
     *                              null} if it has none (unconstrained). Same map {@link
     *                              TriangleIdentitySizeAssignment#solve}'s mesh-aware
     *                              overload already computes for CSP domain-pruning - reused
     *                              here, not recomputed.
     * @param partnerAchievableRange the partner's own equivalent range.
     */
    static int splitWidthForPair(
        BiomeSizeRange range, long worldSeed, int faceIndex, int partnerFaceIndex, BiomeId biome,
        FaceAdjacency.Adjacency adjacency, Map<Integer, Integer> committedWidthBlocksByFace,
        AchievableWidthRange faceAchievableRange, AchievableWidthRange partnerAchievableRange
    ) {
        int lowerFace = Math.min(faceIndex, partnerFaceIndex);
        int higherFace = Math.max(faceIndex, partnerFaceIndex);
        long hash = worldSeed ^ PAIR_SIZE_PICK_SALT;
        hash = hash * HASH_MULTIPLIER + lowerFace;
        hash = hash * HASH_MULTIPLIER + higherFace;
        hash = hash * HASH_MULTIPLIER + biome.hashCode();
        // Pair's own external committed neighbors, from BOTH faces' adjacency (excluding
        // pair members themselves - not committed to each other, the fresh pair deciding now).
        List<Integer> committedNeighborWidths = new ArrayList<>();
        collectCommittedNeighborWidths(faceIndex, adjacency, committedWidthBlocksByFace, committedNeighborWidths);
        collectCommittedNeighborWidths(partnerFaceIndex, adjacency, committedWidthBlocksByFace, committedNeighborWidths);
        Bias bias = biasFor(range, committedNeighborWidths);
        int combinedWidth = pickWidthFromHash(range, hash, bias);

        AchievableWidthRange lowerAchievable = faceIndex == lowerFace ? faceAchievableRange : partnerAchievableRange;
        AchievableWidthRange higherAchievable = faceIndex == lowerFace ? partnerAchievableRange : faceAchievableRange;
        int[] shares = splitByAchievableCapacity(combinedWidth, lowerAchievable, higherAchievable);
        return faceIndex == lowerFace ? shares[0] : shares[1];
    }

    /**
     * Allocates {@code combinedWidth} between two members by REAL geometric need, not
     * chance. Each member's "room" is its own achievable range's {@code maxWidthBlocks} (how
     * wide its real external neighbors could let it get), or the full {@code combinedWidth}
     * if it has no achievable-range entry (no external committed neighbor - unconstrained).
     *
     * <ul>
     *     <li>Both members comfortably room for an even half: split evenly - no real squeeze,
     *     no reason to be asymmetric. This is the common case (most faces have no external
     *     committed neighbor yet, or a generous one) - deliberately reduces to the OLD,
     *     pre-2026-09-13 equal-split behavior whenever there's no real signal to do
     *     otherwise.</li>
     *     <li>Exactly one member is genuinely room-constrained below half: give it only what
     *     it can actually hold (clamped to {@link #MIN_GROUP_MEMBER_WIDTH_BLOCKS}), the other
     *     absorbs the remainder - this is the actual completability escape hatch a self-
     *     adjacent pair exists for.</li>
     *     <li>BOTH members are room-constrained below half: give each its own real room: the
     *     realized combined width may fall short of the drawn {@code combinedWidth} - a real,
     *     honest outcome (don't force more than geometry can support), not a bug.</li>
     * </ul>
     *
     * @return {@code {lowerFaceShare, higherFaceShare}}, summing to {@code combinedWidth}
     * except in the both-constrained case above.
     */
    private static int[] splitByAchievableCapacity(int combinedWidth, AchievableWidthRange lowerAchievable, AchievableWidthRange higherAchievable) {
        int half = combinedWidth / 2;
        int lowerRoom = roomFor(lowerAchievable, combinedWidth);
        int higherRoom = roomFor(higherAchievable, combinedWidth);
        boolean lowerConstrained = lowerRoom < half;
        boolean higherConstrained = higherRoom < half;

        if (!lowerConstrained && !higherConstrained) {
            return new int[] {half, combinedWidth - half};
        }
        if (lowerConstrained && !higherConstrained) {
            int lowerShare = clampShare(lowerRoom, combinedWidth);
            return new int[] {lowerShare, combinedWidth - lowerShare};
        }
        if (higherConstrained && !lowerConstrained) {
            int higherShare = clampShare(higherRoom, combinedWidth);
            return new int[] {combinedWidth - higherShare, higherShare};
        }
        // Both genuinely constrained - give each its own real room; sum may fall short of
        // combinedWidth, an honest outcome, see this method's own doc.
        return new int[] {clampShare(lowerRoom, combinedWidth), clampShare(higherRoom, combinedWidth)};
    }

    /** A member's "room": its own achievable range's ceiling, or the full combined pool if it has no real external constraint (unconstrained). */
    private static int roomFor(AchievableWidthRange achievable, int combinedWidth) {
        return achievable == null ? combinedWidth : achievable.maxWidthBlocks();
    }

    private static int clampShare(int room, int combinedWidth) {
        int floor = MIN_GROUP_MEMBER_WIDTH_BLOCKS;
        int ceiling = Math.max(floor, combinedWidth - floor);
        return Math.max(floor, Math.min(room, ceiling));
    }

    private static void collectCommittedNeighborWidths(
        int faceIndex, FaceAdjacency.Adjacency adjacency, Map<Integer, Integer> committedWidthBlocksByFace, List<Integer> out
    ) {
        for (int neighbor : adjacency.neighborFaceIndices().getOrDefault(faceIndex, Set.of())) {
            Integer committedWidth = committedWidthBlocksByFace.get(neighbor);
            if (committedWidth != null) {
                out.add(committedWidth);
            }
        }
    }

    /**
     * If ANY real committed neighbor's width is below this biome's range midpoint, bias the
     * draw {@code TOWARD_MIN} instead - a tight neighbor (small, permanent, can't give back
     * room) means drawing from the top of this biome's range asks more than the local
     * neighborhood can supply, not hypothetical since neighbor's width is real and locked
     * in. Simple, non-geometric heuristic (min committed neighbor vs. range midpoint)
     * rather than a real shared-edge/area budget solve - stays geometry-agnostic by design,
     * same discipline as the self-adjacent pair split. Falls through to the existing
     * tier-based check when no committed context, or committed neighbors exist but none tight.
     */
    private static Bias biasFor(BiomeSizeRange range, List<Integer> committedNeighborWidths) {
        if (!committedNeighborWidths.isEmpty()) {
            int minCommittedNeighborWidth = Collections.min(committedNeighborWidths);
            double rangeMidpoint = (range.minWidthBlocks() + range.maxWidthBlocks()) / 2.0;
            if (minCommittedNeighborWidth < rangeMidpoint) {
                return Bias.TOWARD_MIN;
            }
        }
        return range.maxWidthBlocks() <= BASELINE_WIDTH_BLOCKS ? Bias.TOWARD_MAX : Bias.UNIFORM;
    }

    /** Shared hash-to-width mechanics for both {@link #pickWidth} (one face, its own full range) and {@link #splitWidthForPair} (one pair, a combined width later split) - only what key gets hashed, and which {@link Bias}, differ between the two callers. */
    private static int pickWidthFromHash(BiomeSizeRange range, long hash, Bias bias) {
        if (range.minWidthBlocks() == range.maxWidthBlocks()) {
            return range.minWidthBlocks();
        }
        double uniform = new Random(hash).nextDouble();
        double fraction = switch (bias) {
            case TOWARD_MAX -> Math.pow(uniform, SIZE_PICK_BASELINE_BIAS_EXPONENT);
            case TOWARD_MIN -> 1 - Math.pow(1 - uniform, SIZE_PICK_BASELINE_BIAS_EXPONENT);
            case UNIFORM -> uniform;
        };
        int span = range.maxWidthBlocks() - range.minWidthBlocks();
        return range.minWidthBlocks() + (int) Math.round(fraction * span);
    }
}
