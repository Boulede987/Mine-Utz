package com.mineutze.worldgen.biomegraph;

import com.mineutze.worldgen.biomegraph.BorderRequirement.AllOf;
import com.mineutze.worldgen.biomegraph.BorderRequirement.AnyOf;
import com.mineutze.worldgen.biomegraph.BorderRequirement.Share;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Reads a {@link BorderRequirement} left to right: or-of-and-of-atoms, an atom being a parenthesized requirement or {@code subject >= number}. */
final class BorderRequirementParser {

    private final String text;
    private int position;

    private BorderRequirementParser(String text) {
        this.text = text;
    }

    static BorderRequirement parse(String text) {
        return new BorderRequirementParser(text).parseWholeText();
    }

    private BorderRequirement parseWholeText() {
        BorderRequirement requirement = parseAnyOf();
        skipSpaces();
        if (position < text.length()) {
            throw failure("unexpected '" + text.charAt(position) + "'");
        }
        return requirement;
    }

    private BorderRequirement parseAnyOf() {
        List<BorderRequirement> alternatives = new ArrayList<>();
        alternatives.add(parseAllOf());
        while (consumeKeyword("or")) {
            alternatives.add(parseAllOf());
        }
        return alternatives.size() == 1 ? alternatives.get(0) : new AnyOf(flatten(alternatives, true));
    }

    private BorderRequirement parseAllOf() {
        List<BorderRequirement> required = new ArrayList<>();
        required.add(parseAtom());
        while (consumeKeyword("and")) {
            required.add(parseAtom());
        }
        return required.size() == 1 ? required.get(0) : new AllOf(flatten(required, false));
    }

    /** Parts of the same kind as the list being built are merged in, so {@code a and (b and c)} is one list of three. */
    private static List<BorderRequirement> flatten(List<BorderRequirement> parts, boolean intoAnyOf) {
        List<BorderRequirement> flat = new ArrayList<>();
        for (BorderRequirement part : parts) {
            addFlattenedPart(flat, part, intoAnyOf);
        }
        return flat;
    }

    private static void addFlattenedPart(List<BorderRequirement> flat, BorderRequirement part, boolean intoAnyOf) {
        if (intoAnyOf && part instanceof AnyOf nested) {
            flat.addAll(nested.parts());
        } else if (!intoAnyOf && part instanceof AllOf nested) {
            flat.addAll(nested.parts());
        } else {
            flat.add(part);
        }
    }

    private BorderRequirement parseAtom() {
        skipSpaces();
        if (position < text.length() && text.charAt(position) == '(') {
            position++;
            BorderRequirement inner = parseAnyOf();
            skipSpaces();
            if (position >= text.length() || text.charAt(position) != ')') {
                throw failure("expected ')'");
            }
            position++;
            return inner;
        }
        String subject = readWord();
        if (subject.isEmpty()) {
            throw failure(position >= text.length() ? "the requirement ends where a group or biome name was expected" : "expected a group or biome name, found '" + text.charAt(position) + "'");
        }
        if (isKeyword(subject)) {
            throw failure("'" + subject + "' is a keyword, not a group or biome name");
        }
        skipSpaces();
        if (!text.startsWith(">=", position)) {
            throw failure("expected '>=' after '" + subject + "'");
        }
        position += 2;
        skipSpaces();
        String number = readWord();
        try {
            return new Share(subject, Integer.parseInt(number));
        } catch (NumberFormatException notANumber) {
            throw failure("expected a whole number of edges after '>=', found '" + number + "'");
        }
    }

    private boolean consumeKeyword(String keyword) {
        skipSpaces();
        int start = position;
        if (readWord().equalsIgnoreCase(keyword)) {
            return true;
        }
        position = start;
        return false;
    }

    private static boolean isKeyword(String word) {
        String lower = word.toLowerCase(Locale.ROOT);
        return lower.equals("and") || lower.equals("or");
    }

    private String readWord() {
        int start = position;
        while (position < text.length() && isWordCharacter(text.charAt(position))) {
            position++;
        }
        return text.substring(start, position);
    }

    private static boolean isWordCharacter(char character) {
        return Character.isLetterOrDigit(character) || character == '_' || character == '.' || character == ':' || character == '/' || character == '-';
    }

    private void skipSpaces() {
        while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
            position++;
        }
    }

    private IllegalArgumentException failure(String reason) {
        return new IllegalArgumentException("BorderRequirement: " + reason + " (at character " + (position + 1) + " of \"" + text + "\")");
    }
}
