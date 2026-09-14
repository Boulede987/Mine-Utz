package com.mineutze.worldgen.regionsolve.topology.identity;

import com.mineutze.worldgen.regionsolve.topology.EdgeDisplacement;

import com.mineutze.worldgen.regionsolve.topology.HexGeometry;

import com.mineutze.worldgen.regionsolve.topology.Salt;

import com.mineutze.worldgen.regionsolve.topology.placement.WeightedVertexPlacement;

import com.mineutze.worldgen.regionsolve.topology.mesh.FaceAdjacency;

import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeSizeRange;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Draws a width for a face whose identity is already decided - zero identity/legality
 * concern, see {@link TriangleIdentitySizeAssignment} (only caller) for how identity gets
 * decided first via {@link FaceIdentityCsp}. Stays geometry-agnostic for a SINGLE face's
 * own draw ({@link #pickWidth}) - only reasons in width blocks there, never imports real
 * hexagon area math (see {@link #BASELINE_WIDTH_BLOCKS}).
 *
 * <p><b>Self-adjacent GROUP splitting is the one deliberate exception (2026-09-14, real bug
 * found and fixed).</b> A first cut summed raw WIDTHS across a group's members - correct
 * for exactly 2 members (matches the real user rule: widths merely need to SUM into the
 * biome's declared range), but area scales as the SQUARE of width, so N equal-width shares
 * deliver only {@code combinedWidth}²{@code /N} of TOTAL felt area, not {@code
 * combinedWidth}² - a group's real footprint SHRANK as more members joined, exactly
 * backwards from "spread a big biome's demand across more couches without shrinking it."
 * {@link #splitWidthForGroup} now distributes AREA (via {@link HexGeometry}, converting
 * back to width only for the final per-member value) - the same physical quantity the
 * pre-2026-09-13 {@code combined/sqrt(2)} mechanism preserved, keeping this session's real
 * fix (needs-driven, asymmetric, not forced-equal) instead of reverting it.
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

    // A self-adjacent group's combined AREA (drawn as a width, see splitWidthForGroup, then
    // converted - see this class's own doc for why AREA not raw width) is split across its
    // members ONLY as asymmetrically as real local geometry actually demands (see
    // splitByAchievableCapacity) - real user decision (2026-09-13, generalized 2026-09-14):
    // a group represents ONE felt region whose members' TOTAL footprint must match the
    // biome's declared [min,max] range, not equal shares, but asymmetry should be NEEDS-
    // DRIVEN, not random (a member squeezed by real committed external neighbors gets only
    // what it can actually hold; the rest of the group absorbs the difference) - not a
    // decorative variance generator. This constant is the absolute WIDTH floor any member
    // can ever be pushed to, real squeeze or not - without one, a degenerate split could
    // hand one member a near-zero or negative width. 50 blocks: comfortably above the
    // geometry layer's own absolute minimum edge length (MIN_EDGE_LENGTH_MACRO_CELLS *
    // macroCellSizeBlocks, ~24 blocks at today's MacroGridConfig.DEFAULT) - first cut, tune
    // from real measurement like every other size constant in this codebase.
    private static final int MIN_GROUP_MEMBER_WIDTH_BLOCKS = 50;
    private static final double MIN_GROUP_MEMBER_AREA_BLOCKS2 = areaForWidth(MIN_GROUP_MEMBER_WIDTH_BLOCKS);

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
     * A group member's own absolute legal range (used by {@code
     * TriangleIdentitySizeAssignment#buildResult} for {@code legalMinWidthBlocks}/{@code
     * legalMaxWidthBlocks}) - checking a member against the FULL combined range would be
     * wrong (a real bug an earlier live-path legal-size test actually had), but under the
     * asymmetric AREA split no single member has a fixed share to check against either. This
     * is the LOOSEST WIDTH bound consistent with the real AREA constraint (every member's
     * area sums to at most {@code combinedMaxWidthBlocks}'s own area, and every OTHER member
     * in the group must get at least {@link #MIN_GROUP_MEMBER_AREA_BLOCKS2}) - real
     * per-instance legality is enforced by {@link #splitWidthForGroup}'s own draw, not by
     * this bound alone.
     */
    static int groupMemberLegalMinWidthBlocks(int combinedMinWidthBlocks, int combinedMaxWidthBlocks) {
        return Math.min(MIN_GROUP_MEMBER_WIDTH_BLOCKS, combinedMaxWidthBlocks / 2);
    }

    /** See {@link #groupMemberLegalMinWidthBlocks} - symmetric ceiling, in AREA terms then converted back to width, generalized to a group of {@code groupSize} members (was hardcoded to 2, "a pair"). */
    static int groupMemberLegalMaxWidthBlocks(int combinedMaxWidthBlocks, int groupSize) {
        double combinedMaxArea = areaForWidth(combinedMaxWidthBlocks);
        double flooredOthersArea = MIN_GROUP_MEMBER_AREA_BLOCKS2 * (groupSize - 1);
        double maxShareArea = Math.max(combinedMaxArea - flooredOthersArea, combinedMaxArea / groupSize);
        return widthForArea(maxShareArea);
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
     * Draws ONE combined width for a self-adjacent GROUP (same mechanism as {@link
     * #pickWidth}, from the SAME biome {@code range}, keyed canonically over EVERY member so
     * identical regardless of which one is visited first) and splits it across the group -
     * see class doc: self-adjacent faces read as ONE felt region spanning N hexes, not N
     * independently-full-target instances (strictly worse - N full demands on the local
     * neighborhood instead of one).
     *
     * <p>Real user decision (2026-09-13, refined 2026-09-14 from a pair to any group size):
     * the group's widths merely need to SUM into the biome's declared {@code [min,max]}
     * range, not each independently occupy an equal share - the split itself is NEEDS-
     * DRIVEN, not random (see {@link #splitByAchievableCapacity}): a member only gets pushed
     * toward the floor when its OWN real, already-committed external neighbors genuinely
     * can't spare more room, and the REST of the group absorbs the difference. A group with
     * no real external squeeze anywhere splits evenly. "Keep adding couches as long as
     * there isn't enough space" (real user framing, 2026-09-14): a biome that can grow very
     * large no longer has to demand that whole size from one local neighborhood - splitting
     * across as many real cells as it happened to land self-adjacent to spreads the demand
     * thin enough that none of them individually gets squeezed.
     *
     * @param groupMembers        every OTHER member of {@code faceIndex}'s own group
     *                             (excludes {@code faceIndex} itself - added back internally
     *                             for a canonical, order-independent member list).
     * @param achievableRangeByFace each member's own real achievable width range from ITS
     *                             OWN external committed neighbors (excludes fellow group
     *                             members by construction - free faces in this same batch,
     *                             never themselves "committed" yet) - missing entry means
     *                             unconstrained. Same map {@link
     *                             TriangleIdentitySizeAssignment#solve}'s mesh-aware overload
     *                             already computes for CSP domain-pruning - reused here, not
     *                             recomputed.
     */
    static int splitWidthForGroup(
        BiomeSizeRange range, long worldSeed, int faceIndex, Set<Integer> groupMembers, BiomeId biome,
        FaceAdjacency.Adjacency adjacency, Map<Integer, Integer> committedWidthBlocksByFace,
        Map<Integer, AchievableWidthRange> achievableRangeByFace
    ) {
        List<Integer> orderedMembers = new ArrayList<>(groupMembers);
        orderedMembers.add(faceIndex);
        Collections.sort(orderedMembers);

        long hash = worldSeed ^ PAIR_SIZE_PICK_SALT;
        List<Integer> committedNeighborWidths = new ArrayList<>();
        for (int member : orderedMembers) {
            hash = hash * HASH_MULTIPLIER + member;
            collectCommittedNeighborWidths(member, adjacency, committedWidthBlocksByFace, committedNeighborWidths);
        }
        hash = hash * HASH_MULTIPLIER + biome.hashCode();
        Bias bias = biasFor(range, committedNeighborWidths);
        int combinedWidth = pickWidthFromHash(range, hash, bias);
        double combinedArea = areaForWidth(combinedWidth);

        Map<Integer, Double> areaShares = splitByAchievableCapacity(combinedArea, orderedMembers, achievableRangeByFace);
        return widthForArea(areaShares.get(faceIndex));
    }

    /**
     * Allocates {@code combinedArea} across a self-adjacent group's own members by REAL
     * geometric need, not chance - water-filling / max-min fairness, the direct N-way
     * generalization of this method's own former 2-member version. AREA, not raw width (see
     * class doc for the real bug this fixes - N equal-WIDTH shares deliver only {@code 1/N}
     * of the group's true total area, shrinking a big biome's real footprint the more it
     * gets split). Each member's "room" is its own achievable width range's {@code
     * maxWidthBlocks} CONVERTED to area (how much its real external neighbors could let it
     * hold), or the full {@code combinedArea} if it has no achievable-range entry
     * (unconstrained).
     *
     * <p>Members are processed tightest-room-first: a member whose room can't even cover an
     * EQUAL share of what's left gets exactly its own room (floor-clamped), and the
     * remaining budget is re-split evenly across the remaining, roomier members. Once a
     * member's room comfortably covers the current equal share, every member from there on
     * (sorted ascending, so all of them too) genuinely has enough room for that share -
     * ends the water-filling there, no further iteration needed. If every member is
     * genuinely room-constrained, the realized SUM may fall short of {@code combinedArea} -
     * a real, honest outcome (don't force more than geometry can support), not a bug, same
     * precedent as this method's own former 2-member "both constrained" case.
     */
    private static Map<Integer, Double> splitByAchievableCapacity(
        double combinedArea, List<Integer> orderedMembers, Map<Integer, AchievableWidthRange> achievableRangeByFace
    ) {
        Map<Integer, Double> room = new LinkedHashMap<>();
        for (int member : orderedMembers) {
            room.put(member, roomFor(achievableRangeByFace.get(member), combinedArea));
        }
        List<Integer> byRoomAscending = new ArrayList<>(orderedMembers);
        byRoomAscending.sort(Comparator.<Integer>comparingDouble(room::get).thenComparingInt(m -> m));

        Map<Integer, Double> shares = new LinkedHashMap<>();
        double remainingArea = combinedArea;
        int remainingCount = orderedMembers.size();
        int cursor = 0;
        while (cursor < byRoomAscending.size() && remainingCount > 1) {
            int member = byRoomAscending.get(cursor);
            double equalShare = remainingArea / remainingCount;
            if (room.get(member) >= equalShare) {
                break; // this member, and every roomier one after it (ascending sort), has enough for an equal split - stop water-filling
            }
            double share = clampShare(room.get(member), remainingArea, remainingCount);
            shares.put(member, share);
            remainingArea -= share;
            remainingCount--;
            cursor++;
        }
        double evenShare = Math.max(MIN_GROUP_MEMBER_AREA_BLOCKS2, remainingCount > 0 ? remainingArea / remainingCount : 0);
        double remainder = remainingCount > 0 ? remainingArea - evenShare * remainingCount : 0;
        for (int i = cursor; i < byRoomAscending.size(); i++) {
            int member = byRoomAscending.get(i);
            boolean isLast = i == byRoomAscending.size() - 1;
            shares.put(member, evenShare + (isLast ? remainder : 0)); // remainder goes to the roomiest member - always safe
        }
        return shares;
    }

    /** A member's "room" in AREA terms: its own achievable width range's ceiling converted to area, or the full combined pool if it has no real external constraint (unconstrained). */
    private static double roomFor(AchievableWidthRange achievable, double combinedArea) {
        return achievable == null ? combinedArea : areaForWidth(achievable.maxWidthBlocks());
    }

    private static double clampShare(double room, double remainingArea, int remainingCount) {
        double floor = MIN_GROUP_MEMBER_AREA_BLOCKS2;
        double ceiling = Math.max(floor, remainingArea - floor * (remainingCount - 1));
        return Math.max(floor, Math.min(room, ceiling));
    }

    /** {@code widthBlocks}' own real hexagon area (circumradius = half-width, matching this codebase's standing width-to-circumradius convention) - the ONE deliberate crossing into real geometry math this class otherwise avoids, see class doc. */
    private static double areaForWidth(double widthBlocks) {
        return HexGeometry.areaForCircumradius(HexGeometry.circumradiusForWidth(widthBlocks));
    }

    /** Inverse of {@link #areaForWidth}. */
    private static int widthForArea(double areaBlocks2) {
        return (int) Math.round(HexGeometry.widthForCircumradius(HexGeometry.circumradiusForArea(areaBlocks2)));
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
