package com.mineutze.worldgen.regionsolve.field;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoherentNoise2DTest {

    @Test
    void sameSeedAndInputProduceTheSameValue() {
        CoherentNoise2D noise = new CoherentNoise2D(42L);
        assertEquals(noise.sample(12.34, 56.78), noise.sample(12.34, 56.78));
    }

    @Test
    void differentSeedsProduceDifferentValues() {
        CoherentNoise2D a = new CoherentNoise2D(1L);
        CoherentNoise2D b = new CoherentNoise2D(2L);
        assertTrue(Math.abs(a.sample(10, 10) - b.sample(10, 10)) > 1e-9);
    }

    @Test
    void sampleStaysWithinUnitRange() {
        CoherentNoise2D noise = new CoherentNoise2D(7L);
        for (double x = -50; x < 50; x += 1.3) {
            for (double z = -50; z < 50; z += 1.7) {
                double value = noise.sample(x, z);
                assertTrue(value >= -1.0 && value <= 1.0, "sample(" + x + "," + z + ") = " + value + " out of [-1,1]");
            }
        }
    }

    @Test
    void isContinuousNotJustContinuousAtLatticePoints() {
        // Nearby points must produce nearby values - this is the whole property the
        // deleted per-vertex-jitter attempts lacked. A tiny step should never produce
        // a large jump; a real discontinuity here would mean the interpolation is
        // broken, not just "noisy".
        CoherentNoise2D noise = new CoherentNoise2D(99L);
        double step = 0.01;
        for (double x = 0; x < 20; x += 0.37) {
            double here = noise.sample(x, 5.0);
            double nearby = noise.sample(x + step, 5.0);
            assertTrue(Math.abs(here - nearby) < 0.05, "discontinuity near x=" + x + ": " + here + " -> " + nearby);
        }
    }

    @Test
    void fbmAlsoStaysWithinUnitRange() {
        CoherentNoise2D noise = new CoherentNoise2D(123L);
        for (double x = -30; x < 30; x += 2.1) {
            for (double z = -30; z < 30; z += 2.3) {
                double value = noise.fbm(x, z, 4);
                assertTrue(value >= -1.0 && value <= 1.0, "fbm(" + x + "," + z + ") = " + value + " out of [-1,1]");
            }
        }
    }
}
