package com.mineutze.worldgen.tooling.graphedit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphEditorReliabilityTest {

    @Test
    void readsTheMinutesArgument() {
        assertEquals(new ReliabilityRequest(25), GraphEditorReliability.requestFrom(new String[] {"25"}));
    }

    @Test
    void refusesNoArgument() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> GraphEditorReliability.requestFrom(new String[] {}));

        assertTrue(refused.getMessage().contains("one argument"), refused.getMessage());
    }
}
