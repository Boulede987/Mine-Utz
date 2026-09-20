package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;

/**
 * A {@link com.mineutze.worldgen.biomegraph.BorderRequirement} whose subjects have been turned into
 * actual biomes (2026-09-20): a {@link Share} is "at least {@code minEdges} cell-edges shared, in
 * total, with these biomes". This is the plain reference reading of a requirement; the search and the
 * map verifier each measure borders their own way and are checked against it.
 */
sealed interface ResolvedBorderRequirement {

    /** At least {@code minEdges} shared cell-edges with the {@code biomes} taken together; {@code subject} is the name it was written with. */
    record Share(String subject, List<BiomeId> biomes, int minEdges) implements ResolvedBorderRequirement {
        public Share {
            biomes = List.copyOf(biomes);
        }

        @Override
        public String toString() {
            return print(this);
        }
    }

    record AllOf(List<ResolvedBorderRequirement> parts) implements ResolvedBorderRequirement {
        public AllOf {
            parts = List.copyOf(parts);
        }

        @Override
        public String toString() {
            return print(this);
        }
    }

    record AnyOf(List<ResolvedBorderRequirement> parts) implements ResolvedBorderRequirement {
        public AnyOf {
            parts = List.copyOf(parts);
        }

        @Override
        public String toString() {
            return print(this);
        }
    }

    /**
     * How many shared edges are still missing, given how many edges are shared with each biome: a share
     * is short by what it lacks, an "and" by the sum of its parts, an "or" by its best part. Zero means met.
     */
    default int shortfall(ToIntFunction<BiomeId> sharedEdgesWith) {
        return switch (this) {
            case Share share -> Math.max(0, share.minEdges() - share.biomes().stream().mapToInt(sharedEdgesWith).sum());
            case AllOf all -> all.parts().stream().mapToInt(part -> part.shortfall(sharedEdgesWith)).sum();
            case AnyOf any -> any.parts().stream().mapToInt(part -> part.shortfall(sharedEdgesWith)).min().orElseThrow();
        };
    }

    /** Whether the requirement holds when each edge asked for is worth {@code blocksPerEdge} blocks of border and {@code sharedBlocksWith} says how much border is really shared with each biome. */
    default boolean isMetBy(ToDoubleFunction<BiomeId> sharedBlocksWith, double blocksPerEdge) {
        return switch (this) {
            case Share share -> share.biomes().stream().mapToDouble(sharedBlocksWith).sum() >= share.minEdges() * blocksPerEdge;
            case AllOf all -> all.parts().stream().allMatch(part -> part.isMetBy(sharedBlocksWith, blocksPerEdge));
            case AnyOf any -> any.parts().stream().anyMatch(part -> part.isMetBy(sharedBlocksWith, blocksPerEdge));
        };
    }

    /** Every share in the requirement, left to right. */
    default List<Share> leaves() {
        List<Share> found = new ArrayList<>();
        collectLeaves(this, found);
        return found;
    }

    private static void collectLeaves(ResolvedBorderRequirement requirement, List<Share> found) {
        switch (requirement) {
            case Share share -> found.add(share);
            case AllOf all -> all.parts().forEach(part -> collectLeaves(part, found));
            case AnyOf any -> any.parts().forEach(part -> collectLeaves(part, found));
        }
    }

    /** The text form, with each share's subject as it was written. */
    static String print(ResolvedBorderRequirement requirement) {
        return switch (requirement) {
            case Share share -> share.subject() + " >= " + share.minEdges();
            case AllOf all -> String.join(" and ", all.parts().stream().map(part -> part instanceof Share ? print(part) : "(" + print(part) + ")").toList());
            case AnyOf any -> String.join(" or ", any.parts().stream().map(part -> part instanceof AnyOf ? "(" + print(part) + ")" : print(part)).toList());
        };
    }
}
