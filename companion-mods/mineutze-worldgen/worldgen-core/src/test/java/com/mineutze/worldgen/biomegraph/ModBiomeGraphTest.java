package com.mineutze.worldgen.biomegraph;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModBiomeGraphTest {

    @Test
    void overworldHasTheExpectedNodeCount() {
        assertEquals(66, ModBiomeGraph.OVERWORLD.nodes().size());
    }
}
