package com.mineutze.worldgen.coherence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DivergenceFreeWarpTest {

    private static final HexTorusGeometry GEOMETRY = new HexTorusGeometry(20, 20, 300);
    private static final DivergenceFreeWarp.Settings SETTINGS = new DivergenceFreeWarp.Settings(24, 60, 1_500, 30, 0.8);

    private static DivergenceFreeWarp warp(long seed) {
        return new DivergenceFreeWarp(GEOMETRY, seed, SETTINGS);
    }

    private static double[] displacementAt(DivergenceFreeWarp warp, double x, double y) {
        double[] out = new double[2];
        warp.displace(x, y, out);
        return out;
    }

    /** The world repeats every tile, so the displacement must too - or the map would tear along the tile seams. */
    @Test
    void displacementRepeatsAfterEachTileVector() {
        DivergenceFreeWarp warp = warp(1);
        double x = 2345.6;
        double y = 1987.1;

        double[] here = displacementAt(warp, x, y);
        double[] afterA = displacementAt(warp, x + GEOMETRY.tileVectorAX(), y + GEOMETRY.tileVectorAY());
        double[] afterB = displacementAt(warp, x + GEOMETRY.tileVectorBX(), y + GEOMETRY.tileVectorBY());

        assertEquals(here[0], afterA[0], 1e-6);
        assertEquals(here[1], afterA[1], 1e-6);
        assertEquals(here[0], afterB[0], 1e-6);
        assertEquals(here[1], afterB[1], 1e-6);
    }

    /** No net expansion or compression anywhere: dx/dx + dy/dy = 0, which is what keeps regions' areas from drifting to first order. */
    @Test
    void displacementHasZeroDivergence() {
        DivergenceFreeWarp warp = warp(2);
        double step = 0.01;

        for (double x = 100; x < 3_000; x += 731.3) {
            for (double y = 100; y < 3_000; y += 617.9) {
                double dxdx = (displacementAt(warp, x + step, y)[0] - displacementAt(warp, x - step, y)[0]) / (2 * step);
                double dydy = (displacementAt(warp, x, y + step)[1] - displacementAt(warp, x, y - step)[1]) / (2 * step);
                assertEquals(0.0, dxdx + dydy, 1e-6, "at " + x + "," + y);
            }
        }
    }

    @Test
    void jacobianDeterminantMatchesFiniteDifferencesOfTheDisplacement() {
        DivergenceFreeWarp warp = warp(3);
        double step = 0.01;
        double x = 1_777.7;
        double y = 2_222.2;

        double dxdx = (displacementAt(warp, x + step, y)[0] - displacementAt(warp, x - step, y)[0]) / (2 * step);
        double dxdy = (displacementAt(warp, x, y + step)[0] - displacementAt(warp, x, y - step)[0]) / (2 * step);
        double dydx = (displacementAt(warp, x + step, y)[1] - displacementAt(warp, x - step, y)[1]) / (2 * step);
        double dydy = (displacementAt(warp, x, y + step)[1] - displacementAt(warp, x, y - step)[1]) / (2 * step);
        double expected = (1 + dxdx) * (1 + dydy) - dxdy * dydx;

        assertEquals(expected, warp.jacobianDeterminant(x, y), 1e-6);
    }

    @Test
    void aWarpWithNoWavesDisplacesNothing() {
        DivergenceFreeWarp none = DivergenceFreeWarp.none(GEOMETRY);

        double[] displacement = displacementAt(none, 500, 500);

        assertEquals(0.0, displacement[0], 0.0);
        assertEquals(0.0, displacement[1], 0.0);
        assertEquals(1.0, none.jacobianDeterminant(500, 500), 0.0);
    }

    @Test
    void sameSeedGivesTheSameWarpAndDifferentSeedsDiffer() {
        double[] first = displacementAt(warp(7), 1_000, 1_000);
        double[] same = displacementAt(warp(7), 1_000, 1_000);
        double[] other = displacementAt(warp(8), 1_000, 1_000);

        assertEquals(first[0], same[0], 0.0);
        assertEquals(first[1], same[1], 0.0);
        assertFalse(first[0] == other[0] && first[1] == other[1]);
    }

    @Test
    void displacementNeverExceedsTheSumOfTheWaveAmplitudes() {
        DivergenceFreeWarp warp = warp(4);

        for (double x = 0; x < 4_000; x += 313.7) {
            double[] displacement = displacementAt(warp, x, 2 * x + 5);
            assertTrue(Math.hypot(displacement[0], displacement[1]) <= warp.sumOfAmplitudes() + 1e-9);
        }
    }

    @Test
    void strongerSettingsDisplaceMoreThanWeakerOnes() {
        DivergenceFreeWarp weak = new DivergenceFreeWarp(GEOMETRY, 5, new DivergenceFreeWarp.Settings(24, 60, 1_500, 5, 0.8));
        DivergenceFreeWarp strong = new DivergenceFreeWarp(GEOMETRY, 5, new DivergenceFreeWarp.Settings(24, 60, 1_500, 50, 0.8));

        assertTrue(strong.sumOfAmplitudes() > 5 * weak.sumOfAmplitudes());
    }

    /** Every wave starts at a random phase, so the field does not collapse to zero (and a fixed, symmetric look) at the tile origin. */
    @Test
    void wavesStartAtRandomPhasesSoTheOriginIsNotSpecial() {
        double[] atOrigin = displacementAt(warp(11), 0, 0);

        assertTrue(Math.hypot(atOrigin[0], atOrigin[1]) > 0.01, "displacement at the origin was " + atOrigin[0] + "," + atOrigin[1]);
    }

    private static DivergenceFreeWarp flowWarp(long seed, double displacement, int flowSteps) {
        return new DivergenceFreeWarp(GEOMETRY, seed, new DivergenceFreeWarp.Settings(40, 40, 1_200, displacement, 0.8, flowSteps));
    }

    /** Numerical determinant of the sampling map x -> x + displacement(x): the local area scale, whatever the warp mode. */
    private static double mapDeterminant(DivergenceFreeWarp warp, double x, double y) {
        double step = 0.25;
        double[] right = displacementAt(warp, x + step, y);
        double[] left = displacementAt(warp, x - step, y);
        double[] up = displacementAt(warp, x, y + step);
        double[] down = displacementAt(warp, x, y - step);
        double dxdx = 1 + (right[0] - left[0]) / (2 * step);
        double dxdy = (up[0] - down[0]) / (2 * step);
        double dydx = (right[1] - left[1]) / (2 * step);
        double dydy = 1 + (up[1] - down[1]) / (2 * step);
        return dxdx * dydy - dxdy * dydx;
    }

    /** The simple one-step displacement folds where its Jacobian goes negative; the flow of the same field must keep area scale 1 exactly there. */
    @Test
    void flowMapKeepsAreaScaleOneEvenWhereTheSimpleDisplacementFolds() {
        DivergenceFreeWarp simple = flowWarp(21, 35, 0);
        DivergenceFreeWarp flow = flowWarp(21, 35, 16);
        double foldX = Double.NaN;
        double foldY = Double.NaN;
        for (double x = 0; x < 3_000 && Double.isNaN(foldX); x += 23.7) {
            for (double y = 0; y < 3_000; y += 23.9) {
                if (simple.jacobianDeterminant(x, y) < 0) {
                    foldX = x;
                    foldY = y;
                    break;
                }
            }
        }
        assertFalse(Double.isNaN(foldX), "this seed and strength must fold somewhere, or the test proves nothing");

        assertEquals(1.0, mapDeterminant(flow, foldX, foldY), 0.02);
        assertEquals(1.0, mapDeterminant(flow, 1_234, 567), 0.02);
        assertEquals(1.0, mapDeterminant(flow, 2_500, 2_100), 0.02);
    }

    @Test
    void flowOfAWeakFieldIsCloseToTheSimpleDisplacement() {
        DivergenceFreeWarp simple = flowWarp(22, 0.3, 0);
        DivergenceFreeWarp flow = flowWarp(22, 0.3, 4);

        double[] byOneStep = displacementAt(simple, 900, 1_400);
        double[] byFlow = displacementAt(flow, 900, 1_400);

        assertEquals(byOneStep[0], byFlow[0], 0.02);
        assertEquals(byOneStep[1], byFlow[1], 0.02);
    }

    @Test
    void flowDisplacementRepeatsAfterEachTileVector() {
        DivergenceFreeWarp flow = flowWarp(23, 35, 8);
        double x = 2_010.5;
        double y = 1_333.3;

        double[] here = displacementAt(flow, x, y);
        double[] afterA = displacementAt(flow, x + GEOMETRY.tileVectorAX(), y + GEOMETRY.tileVectorAY());
        double[] afterB = displacementAt(flow, x + GEOMETRY.tileVectorBX(), y + GEOMETRY.tileVectorBY());

        assertEquals(here[0], afterA[0], 1e-6);
        assertEquals(here[1], afterA[1], 1e-6);
        assertEquals(here[0], afterB[0], 1e-6);
        assertEquals(here[1], afterB[1], 1e-6);
    }

    @Test
    void moreFlowStepsConvergeOnTheSameAnswer() {
        double[] coarse = displacementAt(flowWarp(24, 35, 8), 1_500, 800);
        double[] fine = displacementAt(flowWarp(24, 35, 32), 1_500, 800);

        assertEquals(fine[0], coarse[0], 0.05);
        assertEquals(fine[1], coarse[1], 0.05);
    }

    @Test
    void negativeFlowStepsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DivergenceFreeWarp.Settings(24, 60, 1_500, 30, 0.8, -1));
    }

    @Test
    void theRequestedNumberOfWavesIsUsed() {
        assertEquals(24, warp(6).waveCount());
    }

    @Test
    void differentSeedsPickDifferentWaves() {
        assertNotEquals(warp(9).sumOfAmplitudes(), warp(10).sumOfAmplitudes());
    }

    @Test
    void rejectsSettingsThatCannotDescribeAWarp() {
        assertThrows(IllegalArgumentException.class, () -> new DivergenceFreeWarp.Settings(0, 60, 1_500, 30, 0.8));
        assertThrows(IllegalArgumentException.class, () -> new DivergenceFreeWarp.Settings(24, 0, 1_500, 30, 0.8));
        assertThrows(IllegalArgumentException.class, () -> new DivergenceFreeWarp.Settings(24, 500, 100, 30, 0.8));
        assertThrows(IllegalArgumentException.class, () -> new DivergenceFreeWarp.Settings(24, 60, 1_500, -1, 0.8));
        assertThrows(IllegalArgumentException.class, () -> new DivergenceFreeWarp.Settings(24, 60, 1_500, 30, -0.1));
    }
}
