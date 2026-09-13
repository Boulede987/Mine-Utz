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

    /**
     * Self-adjacent pair's felt area split evenly BETWEEN 2 members - area scales as the
     * SQUARE of width for any uniformly-scaled 2D shape, so halving area means each face's
     * width is combined width divided by this (sqrt(2)), not by 2. Shared by both places a
     * pair's combined width gets split: actual draw ({@link #splitWidthForPair}) and a
     * paired face's resulting absolute legal range (see {@link #pairShareOf}, used by
     * {@code TriangleIdentitySizeAssignment#buildResult}).
     */
    private static final double SELF_ADJACENT_PAIR_WIDTH_DIVISOR = Math.sqrt(2);

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
     * Self-adjacent pair's own share (half by AREA, not width - see {@link
     * #SELF_ADJACENT_PAIR_WIDTH_DIVISOR}) of a combined width or bound. Used for both the
     * drawn width ({@link #splitWidthForPair}) and a paired face's resulting absolute legal
     * min/max ({@code TriangleIdentitySizeAssignment#buildResult}) - checking a split half
     * against the FULL range would be wrong, a real bug an earlier live-path legal-size
     * test actually had.
     */
    static int pairShareOf(int combinedWidthOrBound) {
        return (int) Math.round(combinedWidthOrBound / SELF_ADJACENT_PAIR_WIDTH_DIVISOR);
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
     * identical regardless of which face is visited first) and splits it evenly - see class
     * doc: two self-adjacent faces read as ONE felt region across two hexes, not two
     * independently-full-target instances (strictly worse - two full demands on the local
     * neighborhood instead of one).
     *
     * <p>Split even by AREA, not width - area scales as the SQUARE of width for any
     * uniformly-scaled 2D shape (true regardless of the real hexagon area formula, which
     * this class deliberately never imports - see {@link #BASELINE_WIDTH_BLOCKS}), so
     * halving felt area means each face's width is combined width divided by {@code
     * sqrt(2)}, not 2. A paired face's width typically lands BELOW its biome's official
     * {@code minWidthBlocks} - expected: per-face number means "this face's share of a
     * shared felt region," not "standalone target." The COMBINED width (recoverable as
     * {@code width * sqrt(2)}) is what stays within the biome's declared range.
     */
    static int splitWidthForPair(
        BiomeSizeRange range, long worldSeed, int faceIndex, int partnerFaceIndex, BiomeId biome,
        FaceAdjacency.Adjacency adjacency, Map<Integer, Integer> committedWidthBlocksByFace
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
        return pairShareOf(combinedWidth);
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
