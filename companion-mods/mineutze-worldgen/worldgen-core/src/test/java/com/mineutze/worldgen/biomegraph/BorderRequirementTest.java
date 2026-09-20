package com.mineutze.worldgen.biomegraph;

import com.mineutze.worldgen.biomegraph.BorderRequirement.AllOf;
import com.mineutze.worldgen.biomegraph.BorderRequirement.AnyOf;
import com.mineutze.worldgen.biomegraph.BorderRequirement.Share;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BorderRequirementTest {

    private static BorderRequirement parse(String text) {
        return BorderRequirement.parse(text).orElseThrow();
    }

    @Test
    void aBlankTextIsNoRequirement() {
        assertEquals(Optional.empty(), BorderRequirement.parse(""));
        assertEquals(Optional.empty(), BorderRequirement.parse("   \t"));
    }

    @Test
    void oneGroupWithAMinimumIsASingleShare() {
        assertEquals(new Share("oceans", 2), parse("oceans >= 2"));
    }

    @Test
    void aBiomeIdCanBeTheSubject() {
        assertEquals(new Share("minecraft:ocean", 1), parse("minecraft:ocean >= 1"));
    }

    @Test
    void andJoinsShares() {
        assertEquals(new AllOf(List.of(new Share("oceans", 2), new Share("forests", 1))), parse("oceans >= 2 and forests >= 1"));
    }

    @Test
    void orJoinsShares() {
        assertEquals(new AnyOf(List.of(new Share("oceans", 2), new Share("rivers", 1))), parse("oceans >= 2 or rivers >= 1"));
    }

    @Test
    void andBindsTighterThanOr() {
        assertEquals(
            new AnyOf(List.of(new AllOf(List.of(new Share("a", 1), new Share("b", 1))), new Share("c", 1))),
            parse("a >= 1 and b >= 1 or c >= 1")
        );
    }

    @Test
    void parenthesesOverridePrecedence() {
        assertEquals(
            new AllOf(List.of(new Share("a", 1), new AnyOf(List.of(new Share("b", 1), new Share("c", 1))))),
            parse("a >= 1 and (b >= 1 or c >= 1)")
        );
    }

    @Test
    void aNestedAndInsideAnAndIsOneFlatList() {
        assertEquals(
            new AllOf(List.of(new Share("a", 1), new Share("b", 1), new Share("c", 1), new Share("d", 1))),
            parse("a >= 1 and (b >= 1 and c >= 1) and d >= 1")
        );
    }

    @Test
    void aNestedOrInsideAnOrIsOneFlatList() {
        assertEquals(
            new AnyOf(List.of(new Share("a", 1), new Share("b", 1), new Share("c", 1))),
            parse("a >= 1 or (b >= 1 or c >= 1)")
        );
    }

    @Test
    void redundantParenthesesAroundAShareChangeNothing() {
        assertEquals(new Share("oceans", 2), parse("((oceans >= 2))"));
    }

    @Test
    void theCanonicalTextParenthesizesAnOrInsideAnAnd() {
        assertEquals("a >= 1 and (b >= 1 or c >= 1)", parse("a>=1 and(b>=1 or c>=1)").toString());
    }

    @Test
    void theCanonicalTextLeavesAnAndInsideAnOrBare() {
        assertEquals("a >= 1 and b >= 1 or c >= 1", parse("(a >= 1 and b >= 1) or c >= 1").toString());
    }

    @Test
    void parsingTheCanonicalTextGivesTheSameRequirement() {
        BorderRequirement original = parse("(a >= 2 or b >= 1) and (c >= 3 or (d >= 1 and e >= 1))");

        assertEquals(original, parse(original.toString()));
    }

    @Test
    void subjectsListsEveryNameOnceInOrderOfAppearance() {
        assertEquals(List.of("a", "b", "c"), parse("a >= 1 and (b >= 1 or a >= 2) and c >= 1").subjects());
    }

    @Test
    void aMissingMinimumIsRejectedSayingWhere() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> BorderRequirement.parse("oceans and forests >= 1"));

        assertTrue(failure.getMessage().contains("'>='"), failure.getMessage());
    }

    @Test
    void anUnclosedParenthesisIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> BorderRequirement.parse("(a >= 1 or b >= 1"));
    }

    @Test
    void aStrayClosingParenthesisIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> BorderRequirement.parse("a >= 1)"));
    }

    @Test
    void aTrailingOperatorIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> BorderRequirement.parse("a >= 1 and"));
    }

    @Test
    void aNonNumericMinimumIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> BorderRequirement.parse("a >= many"));
    }

    @Test
    void aReservedWordCannotBeASubject() {
        assertThrows(IllegalArgumentException.class, () -> BorderRequirement.parse("and >= 1"));
    }

    @Test
    void aShareMustAskForAtLeastOneEdge() {
        assertThrows(IllegalArgumentException.class, () -> BorderRequirement.parse("a >= 0"));
    }

    @Test
    void emptyParenthesesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> BorderRequirement.parse("()"));
    }

    @Test
    void anUnexpectedCharacterIsRejectedNamingIt() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> BorderRequirement.parse("a >= 1 & b >= 1"));

        assertTrue(failure.getMessage().contains("&"), failure.getMessage());
    }
}
