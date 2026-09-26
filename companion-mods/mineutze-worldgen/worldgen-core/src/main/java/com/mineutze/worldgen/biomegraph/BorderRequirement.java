package com.mineutze.worldgen.biomegraph;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What a biome's clusters must border (2026-09-20), written as text like {@code oceans >= 2 and
 * (forests >= 1 or plains >= 1)}. A {@link Share} is "the cluster shares at least N cell-edges with
 * the named group of biomes" (a subject with a colon is one biome, {@code minecraft:ocean}); {@link
 * AllOf} needs every part and {@link AnyOf} needs at least one, so any mix of "one within this
 * group", "and", "or" can be written. {@code and} binds tighter than {@code or}; parentheses group.
 *
 * <p>This class only reads and prints the text. What a subject means (which biomes a group holds) is
 * decided where the catalog is resolved, and how a requirement is met is decided by the generator.
 */
public sealed interface BorderRequirement {

    /** Shares at least {@code minSharedEdges} cell-edges (about 150 blocks each) with any biome of {@code subject}, summed. */
    record Share(String subject, int minSharedEdges) implements BorderRequirement {
        public Share {
            if (minSharedEdges < 1) {
                throw new IllegalArgumentException("BorderRequirement: '" + subject + "' must be asked for at least 1 shared edge, got " + minSharedEdges);
            }
        }

        @Override
        public String toString() {
            return print(this);
        }
    }

    /** Every part must hold. */
    record AllOf(List<BorderRequirement> parts) implements BorderRequirement {
        public AllOf {
            parts = List.copyOf(parts);
            if (parts.isEmpty()) {
                throw new IllegalArgumentException("BorderRequirement: 'and' needs at least one part");
            }
        }

        @Override
        public String toString() {
            return print(this);
        }
    }

    /** At least one part must hold. */
    record AnyOf(List<BorderRequirement> parts) implements BorderRequirement {
        public AnyOf {
            parts = List.copyOf(parts);
            if (parts.isEmpty()) {
                throw new IllegalArgumentException("BorderRequirement: 'or' needs at least one part");
            }
        }

        @Override
        public String toString() {
            return print(this);
        }
    }

    /** The requirement written in {@code text}, or empty for a blank text; throws {@link IllegalArgumentException} saying where the text is wrong. */
    static Optional<BorderRequirement> parse(String text) {
        if (text.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(BorderRequirementParser.parse(text));
    }

    /** The canonical text: {@link #parse} of it gives back an equal requirement. */
    static String print(BorderRequirement requirement) {
        return switch (requirement) {
            case Share share -> share.subject() + " >= " + share.minSharedEdges();
            case AllOf all -> joined(all.parts(), " and ", true);
            case AnyOf any -> joined(any.parts(), " or ", false);
        };
    }

    private static String joined(List<BorderRequirement> parts, String separator, boolean parentIsAnd) {
        List<String> printed = new ArrayList<>();
        for (BorderRequirement part : parts) {
            boolean needsParentheses = parentIsAnd ? !(part instanceof Share) : part instanceof AnyOf;
            printed.add(needsParentheses ? "(" + print(part) + ")" : print(part));
        }
        return String.join(separator, printed);
    }
}
