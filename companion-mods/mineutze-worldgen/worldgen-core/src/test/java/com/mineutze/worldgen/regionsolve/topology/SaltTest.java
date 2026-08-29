package com.mineutze.worldgen.regionsolve.topology;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class SaltTest {

    @Test
    void sameNameProducesTheSameSalt() {
        assertEquals(Salt.of("EdgeDisplacement.wiggle"), Salt.of("EdgeDisplacement.wiggle"));
    }

    @Test
    void differentNamesProduceDifferentSalts() {
        assertNotEquals(Salt.of("EdgeDisplacement.wiggle"), Salt.of("FaceSizeFeasibility.bias"));
    }
}
