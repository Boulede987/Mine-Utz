package com.mineutze.worldgen.tooling.graphedit;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphEditorVerifyTest {

    @Test
    void readsThePicksAndTheirRanking() {
        assertEquals(Optional.of(new VerifyRequest(List.of(1, 4, 7), "abc")), GraphEditorVerify.requestFrom(new String[] {"1,4,7:abc"}));
    }

    @Test
    void noArgumentMeansTheDefaultPicks() {
        assertEquals(Optional.empty(), GraphEditorVerify.requestFrom(new String[] {}));
    }

    @Test
    void refusesMoreThanOneArgument() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> GraphEditorVerify.requestFrom(new String[] {"1:abc", "4:abc"}));

        assertTrue(refused.getMessage().contains("one argument"), refused.getMessage());
    }
}
