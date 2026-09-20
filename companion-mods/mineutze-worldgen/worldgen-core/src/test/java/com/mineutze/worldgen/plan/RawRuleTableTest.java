package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.EdgeData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.GroupData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.NodeData;
import com.mineutze.worldgen.biomegraph.BiomeId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

import static com.mineutze.worldgen.plan.ToroidalTestTables.A;
import static com.mineutze.worldgen.plan.ToroidalTestTables.B;
import static com.mineutze.worldgen.plan.ToroidalTestTables.C;
import static com.mineutze.worldgen.plan.ToroidalTestTables.D;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How a catalog's groups and requirement texts are resolved into rules about actual biomes. */
class RawRuleTableTest {

    private static NodeData node(BiomeId biome, String requirement) {
        return new NodeData(biome.namespace(), biome.path(), 500, 800, Integer.MAX_VALUE, requirement);
    }

    private static RawRuleTable table(String requirementOfA, GroupData... groups) {
        BiomeGraphData data = new BiomeGraphData(
            List.of(node(A, requirementOfA), node(B, ""), node(C, ""), node(D, "")),
            List.of(new EdgeData("test", "a", "test", "b", 10), new EdgeData("test", "a", "test", "c", 10), new EdgeData("test", "a", "test", "d", 10)),
            List.of(groups)
        );
        return RawRuleTable.from(data);
    }

    private static GroupData group(String name, BiomeId... members) {
        return new GroupData(name, java.util.Arrays.stream(members).map(BiomeId::toString).toList());
    }

    private static ToIntFunction<BiomeId> edges(Map<BiomeId, Integer> byBiome) {
        return biome -> byBiome.getOrDefault(biome, 0);
    }

    @Test
    void aBiomeWithoutARequirementHasNone() {
        assertTrue(table("").requirementOf(A).isEmpty());
    }

    @Test
    void aBlankRequirementIsNone() {
        assertTrue(table("   ").requirementOf(A).isEmpty());
    }

    @Test
    void aBiomeSubjectIsAGroupOfJustThatBiome() {
        ResolvedBorderRequirement requirement = table("test:b >= 3").requirementOf(A).orElseThrow();

        assertEquals(1, requirement.shortfall(edges(Map.of(B, 2))));
        assertEquals(0, requirement.shortfall(edges(Map.of(B, 3))));
    }

    @Test
    void aGroupSubjectSumsTheEdgesOfAllItsMembers() {
        ResolvedBorderRequirement requirement = table("waters >= 5", group("waters", B, C)).requirementOf(A).orElseThrow();

        assertEquals(1, requirement.shortfall(edges(Map.of(B, 2, C, 2))));
        assertEquals(0, requirement.shortfall(edges(Map.of(B, 2, C, 3))));
        assertEquals(5, requirement.shortfall(edges(Map.of(D, 9))), "edges with a biome outside the group count for nothing");
    }

    @Test
    void allOfAddsTheShortfallsOfItsParts() {
        ResolvedBorderRequirement requirement = table("test:b >= 3 and test:c >= 2").requirementOf(A).orElseThrow();

        assertEquals(3 + 2, requirement.shortfall(edges(Map.of())));
        assertEquals(1, requirement.shortfall(edges(Map.of(B, 2, C, 2))));
        assertEquals(0, requirement.shortfall(edges(Map.of(B, 3, C, 2))));
    }

    @Test
    void anyOfCostsTheSmallestShortfallOfItsParts() {
        ResolvedBorderRequirement requirement = table("test:b >= 3 or test:c >= 2").requirementOf(A).orElseThrow();

        assertEquals(2, requirement.shortfall(edges(Map.of())));
        assertEquals(1, requirement.shortfall(edges(Map.of(B, 2))));
        assertEquals(0, requirement.shortfall(edges(Map.of(C, 5))));
    }

    @Test
    void nestedAlternativesInsideAnAndAreEachPriced() {
        ResolvedBorderRequirement requirement = table("test:b >= 2 and (test:c >= 3 or test:d >= 1)").requirementOf(A).orElseThrow();

        assertEquals(2 + 1, requirement.shortfall(edges(Map.of())));
        assertEquals(1, requirement.shortfall(edges(Map.of(B, 2, C, 2))));
        assertEquals(0, requirement.shortfall(edges(Map.of(B, 2, D, 1))));
    }

    @Test
    void theBiomeItselfInsideAGroupNeverCountsAsItsOwnNeighbor() {
        ResolvedBorderRequirement requirement = table("mixed >= 2", group("mixed", A, B)).requirementOf(A).orElseThrow();

        assertEquals(2, requirement.shortfall(edges(Map.of(A, 9))));
        assertEquals(0, requirement.shortfall(edges(Map.of(B, 2))));
    }

    @Test
    void aGroupOfOnlyTheBiomeItselfIsRejectedSayingSo() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> table("me >= 1", group("me", A)));

        assertTrue(failure.getMessage().contains("only test:a itself"), failure.getMessage());
    }

    @Test
    void isMetByComparesSharedBlocksAgainstTheEdgesTimesTheLengthPerEdge() {
        ResolvedBorderRequirement requirement = table("waters >= 2", group("waters", B, C)).requirementOf(A).orElseThrow();

        assertTrue(requirement.isMetBy(biome -> biome.equals(B) ? 100 : biome.equals(C) ? 100 : 0, 100));
        assertFalse(requirement.isMetBy(biome -> biome.equals(B) ? 100 : biome.equals(C) ? 99 : 0, 100));
    }

    @Test
    void isMetByNeedsEveryPartOfAnAndButOnlyOnePartOfAnOr() {
        ResolvedBorderRequirement both = table("test:b >= 1 and test:c >= 1").requirementOf(A).orElseThrow();
        ResolvedBorderRequirement either = table("test:b >= 1 or test:c >= 1").requirementOf(A).orElseThrow();
        java.util.function.ToDoubleFunction<BiomeId> onlyB = biome -> biome.equals(B) ? 10 : 0;

        assertFalse(both.isMetBy(onlyB, 10));
        assertTrue(either.isMetBy(onlyB, 10));
    }

    @Test
    void theRequirementPrintsAsItsCanonicalText() {
        assertEquals("waters >= 2 and (test:d >= 1 or test:c >= 1)", table("waters>=2 and (test:d>=1 or test:c>=1)", group("waters", B)).requirementOf(A).orElseThrow().toString());
    }

    @Test
    void anUnknownGroupIsRejectedNamingTheBiomeAndTheGroup() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> table("waters >= 1"));

        assertTrue(failure.getMessage().contains("test:a") && failure.getMessage().contains("waters"), failure.getMessage());
    }

    @Test
    void anUnknownBiomeSubjectIsRejected() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> table("test:zzz >= 1"));

        assertTrue(failure.getMessage().contains("test:zzz"), failure.getMessage());
    }

    @Test
    void aGroupHoldingAnUnknownBiomeIsRejected() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> table("", new GroupData("waters", List.of("test:zzz"))));

        assertTrue(failure.getMessage().contains("waters") && failure.getMessage().contains("test:zzz"), failure.getMessage());
    }

    @Test
    void twoGroupsWithTheSameNameAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> table("", group("waters", B), group("waters", C)));
    }

    @Test
    void aGroupNameThatLooksLikeABiomeIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> table("", group("a:b", B)));
    }

    @Test
    void aGroupNamedLikeAKeywordIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> table("", group("and", B)));
    }

    @Test
    void aMalformedRequirementTextIsRejectedNamingTheBiome() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> table("test:b >="));

        assertTrue(failure.getMessage().contains("test:a"), failure.getMessage());
    }

    @Test
    void aGroupMayBeEmptyAndThenNothingCountsTowardsIt() {
        ResolvedBorderRequirement requirement = table("nobody >= 2", new GroupData("nobody", List.of())).requirementOf(A).orElseThrow();

        assertEquals(2, requirement.shortfall(edges(Map.of(B, 9))));
    }

    @Test
    void aSubjectResolvesToItsBiomesForValidation() {
        RawRuleTable table = table("test:b >= 1 or waters >= 1", group("waters", C, D));

        assertEquals(List.of(List.of(B), List.of(C, D)), table.requirementOf(A).orElseThrow().leaves().stream().map(ResolvedBorderRequirement.Share::biomes).toList());
    }
}
