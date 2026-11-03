package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Laws Verify measures. Cap: user decision, DECISIONS 2026-11-03 "Verify measures the laws the user picks". */
record VerifyPicks(List<LoosenedGraph> loosenings, List<String> skipped) {

    static final int MAX_PICKS = 3;

    VerifyPicks {
        loosenings = List.copyOf(loosenings);
        skipped = List.copyOf(skipped);
    }

    /** Ranks count from 1, as the editor shows them. @throws IllegalArgumentException picks refused, message says why */
    static VerifyPicks of(TightnessRanking ranking, BiomeGraphData catalog, List<Integer> ranks) {
        requireCountInRange(ranks);
        requireNoDuplicate(ranks);
        List<LoosenedGraph> loosenings = new ArrayList<>();
        for (int rank : ranks) {
            loosenings.add(loosenedAt(rank, ranking, catalog));
        }
        return new VerifyPicks(loosenings, List.of());
    }

    /**
     * No picks given (Verify run by hand): first MAX_PICKS loosenable, naming the obstacles passed over. Empty
     * loosenings when nothing is loosenable: the caller reports "nothing to measure" with the skipped list.
     */
    static VerifyPicks topLoosenable(TightnessRanking ranking, BiomeGraphData catalog) {
        List<LoosenedGraph> loosenings = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (RankedObstacle ranked : ranking.entries()) {
            if (loosenings.size() == MAX_PICKS) {
                break;
            }
            sortObstacle(ranked, catalog, loosenings, skipped);
        }
        return new VerifyPicks(loosenings, skipped);
    }

    private static void sortObstacle(RankedObstacle ranked, BiomeGraphData catalog, List<LoosenedGraph> loosenings, List<String> skipped) {
        Optional<LoosenedGraph> loosened = Loosening.of(ranked.obstacle(), catalog);
        loosened.ifPresentOrElse(loosenings::add, () -> skipped.add(ranked.obstacle().describe()));
    }

    private static void requireCountInRange(List<Integer> ranks) {
        if (ranks.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("VerifyPicks: a missing rank in " + ranks);
        }
        if (ranks.isEmpty() || ranks.size() > MAX_PICKS) {
            throw new IllegalArgumentException("VerifyPicks: pick 1 to " + MAX_PICKS + " laws, got " + ranks.size() + " " + ranks);
        }
    }

    private static void requireNoDuplicate(List<Integer> ranks) {
        if (new HashSet<>(ranks).size() != ranks.size()) {
            throw new IllegalArgumentException("VerifyPicks: the same law picked twice in " + ranks);
        }
    }

    private static LoosenedGraph loosenedAt(int rank, TightnessRanking ranking, BiomeGraphData catalog) {
        if (rank < 1 || rank > ranking.entries().size()) {
            throw new IllegalArgumentException("VerifyPicks: rank " + rank + " outside the ranking 1.." + ranking.entries().size());
        }
        Obstacle obstacle = ranking.entries().get(rank - 1).obstacle();
        return Loosening.of(obstacle, catalog).orElseThrow(
            () -> new IllegalArgumentException("VerifyPicks: rank " + rank + " (" + obstacle.describe() + ") cannot be loosened in the graph"));
    }
}
