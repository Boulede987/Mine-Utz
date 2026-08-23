package com.mineutze.worldgen.regionsolve.field;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafetyFieldTest {

    @Test
    void everywhereIsFullySafeWhenTheReferenceWarpIsTheIdentity() {
        Warp2D identity = (x, z) -> new double[]{x, z};
        SafetyField field = new SafetyField(identity, -100, 100, 20, 0.3, 0.6, 0);
        for (double x = -90; x <= 90; x += 17) {
            for (double z = -90; z <= 90; z += 17) {
                assertEquals(1.0, field.safetyAt(x, z), 1e-9, "identity warp has determinant 1.0 everywhere, well above comfortable");
            }
        }
    }

    @Test
    void staysWithinZeroToOneEverywhereForARealWarp() {
        DomainWarp risky = new DomainWarp(new CoherentNoise2D(5L), new CoherentNoise2D(6L), 40, 0.01, 3);
        SafetyField field = new SafetyField(risky, -500, 500, 20, 0.3, 0.6, 8);
        for (double x = -450; x < 450; x += 23) {
            for (double z = -450; z < 450; z += 23) {
                double safety = field.safetyAt(x, z);
                assertTrue(safety >= 0.0 && safety <= 1.0, "safetyAt(" + x + "," + z + ") = " + safety + " out of [0,1]");
            }
        }
    }

    @Test
    void queryOutsidePrecomputedSquareThrows() {
        Warp2D identity = (x, z) -> new double[]{x, z};
        SafetyField field = new SafetyField(identity, -100, 100, 20, 0.3, 0.6, 0);
        assertThrows(IllegalArgumentException.class, () -> field.safetyAt(1000, 0));
    }

    @Test
    void rejectsComfortableAtOrBelowDanger() {
        Warp2D identity = (x, z) -> new double[]{x, z};
        assertThrows(IllegalArgumentException.class, () -> new SafetyField(identity, -100, 100, 20, 0.5, 0.5, 0));
    }

    /**
     * The bug this session actually hit: averaging a raw determinant field dilutes a
     * sharp danger spike with its safe neighbors, making a genuinely risky point look
     * artificially safer. A min-filter (dilateRadiusCells) must NEVER report higher
     * safety than the plain undilated field at the same point - it can only ever widen
     * danger, never shrink it.
     */
    @Test
    void dilationNeverReportsSaferThanTheUndilatedField() {
        DomainWarp risky = new DomainWarp(new CoherentNoise2D(9L), new CoherentNoise2D(10L), 40, 0.01, 3);
        SafetyField undilated = new SafetyField(risky, -500, 500, 20, 0.3, 0.6, 0);
        SafetyField dilated = new SafetyField(risky, -500, 500, 20, 0.3, 0.6, 6);
        for (double x = -450; x < 450; x += 31) {
            for (double z = -450; z < 450; z += 31) {
                assertTrue(
                    dilated.safetyAt(x, z) <= undilated.safetyAt(x, z) + 1e-9,
                    "dilated safety at (" + x + "," + z + ") exceeded undilated - dilation must only ever widen danger"
                );
            }
        }
    }
}
