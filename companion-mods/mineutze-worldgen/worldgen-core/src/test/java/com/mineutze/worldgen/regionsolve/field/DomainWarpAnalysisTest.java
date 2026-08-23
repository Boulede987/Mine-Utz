package com.mineutze.worldgen.regionsolve.field;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DomainWarpAnalysisTest {

    private static final double SAFETY_MARGIN = 0.2;

    @Test
    void identityWarpNeverFolds() {
        DomainWarp warp = new DomainWarp(new CoherentNoise2D(1L), new CoherentNoise2D(2L), 0, 1, 1);
        assertTrue(DomainWarpAnalysis.isFoldFreeWithMargin(warp, -500, 500, 10, SAFETY_MARGIN));
    }

    /**
     * The whole point of the field spike: {@link WarpTuning#RECOMMENDED_STRONG} must
     * stay fold-free with real margin, checked over a real region-scale span (not just
     * the small 500x500 spike canvas) and across more than one worldSeed, so nobody can
     * silently push this constant back into fold territory later without this test
     * catching it - the failure mode this guards against is exactly the isolated-speck
     * artifact seen in an earlier (rejected) candidate that looked clean in one render
     * but measured well past the fold threshold.
     */
    @Test
    void recommendedStrongTuningStaysFoldFreeAcrossSeeds() {
        for (long worldSeed : new long[]{0L, 1L, 42L, 1_000_000L, -777L}) {
            CoherentNoise2D noiseX = new CoherentNoise2D(worldSeed ^ 0x1234_5678_9ABC_DEF0L);
            CoherentNoise2D noiseZ = new CoherentNoise2D(worldSeed ^ 0x0FED_CBA9_8765_4321L);
            WarpTuning tuning = WarpTuning.RECOMMENDED_STRONG;
            DomainWarp warp = new DomainWarp(noiseX, noiseZ, tuning.amplitude(), tuning.frequency(), tuning.octaves());

            double minDeterminant = DomainWarpAnalysis.minJacobianDeterminant(warp, -2000, 2000, 8.0);
            assertTrue(
                minDeterminant > SAFETY_MARGIN,
                "worldSeed " + worldSeed + ": min Jacobian determinant " + minDeterminant + " is at or below the " + SAFETY_MARGIN + " fold-safety margin"
            );
        }
    }

    /**
     * {@link AdaptiveWarpTuning#RECOMMENDED_ADAPTIVE} pushes strongAmplitude (30) past
     * what a FLAT constant could safely use at this frequency (25, {@link
     * WarpTuning#RECOMMENDED_STRONG}'s own floor) — a modest but real gain, only possible
     * because {@link SafetyField} dials it back toward the floor exactly where needed.
     * Verifies the WHOLE combined function (amplitude modulation's own contribution to
     * the local Jacobian included, not just the underlying noise's), same rigor as the
     * flat tuning's own test above.
     */
    @Test
    void recommendedAdaptiveTuningStaysFoldFreeAcrossSeeds() {
        AdaptiveWarpTuning tuning = AdaptiveWarpTuning.RECOMMENDED_ADAPTIVE;
        double fieldMargin = tuning.dilateRadiusCells() * tuning.latticeSpacing() + tuning.latticeSpacing();

        for (long worldSeed : new long[]{0L, 1L, 42L, 1_000_000L, -777L}) {
            CoherentNoise2D noiseX = new CoherentNoise2D(worldSeed ^ 0x1234_5678_9ABC_DEF0L);
            CoherentNoise2D noiseZ = new CoherentNoise2D(worldSeed ^ 0x0FED_CBA9_8765_4321L);

            DomainWarp riskReference = new DomainWarp(noiseX, noiseZ, tuning.strongAmplitude(), tuning.frequency(), tuning.octaves());
            SafetyField safetyField = new SafetyField(
                riskReference, -2000 - fieldMargin, 2000 + fieldMargin, tuning.latticeSpacing(),
                tuning.dangerDeterminant(), tuning.comfortableDeterminant(), tuning.dilateRadiusCells()
            );
            AdaptiveDomainWarp adaptive = new AdaptiveDomainWarp(noiseX, noiseZ, tuning.floorAmplitude(), tuning.strongAmplitude(), tuning.frequency(), tuning.octaves(), safetyField);

            double minDeterminant = DomainWarpAnalysis.minJacobianDeterminant(adaptive, -2000, 2000, 8.0);
            assertTrue(
                minDeterminant > SAFETY_MARGIN,
                "worldSeed " + worldSeed + ": adaptive min Jacobian determinant " + minDeterminant + " is at or below the " + SAFETY_MARGIN + " fold-safety margin"
            );
        }
    }

    /**
     * Fold-safety alone isn't enough to catch a regression: an earlier (rejected)
     * candidate tuning was ALSO fold-safe with a huge margin, but only because its safety
     * field dampened almost the entire map down to the floor amplitude — visually
     * indistinguishable from the flat tuning, defeating the whole point. This asserts the
     * shipped tuning actually keeps most of the map near full strength, not just
     * technically-safe-because-everywhere-is-dampened.
     */
    @Test
    void recommendedAdaptiveTuningKeepsMostOfTheMapNearFullStrength() {
        AdaptiveWarpTuning tuning = AdaptiveWarpTuning.RECOMMENDED_ADAPTIVE;
        double fieldMargin = tuning.dilateRadiusCells() * tuning.latticeSpacing() + tuning.latticeSpacing();
        CoherentNoise2D noiseX = new CoherentNoise2D(42L ^ 0x1234_5678_9ABC_DEF0L);
        CoherentNoise2D noiseZ = new CoherentNoise2D(42L ^ 0x0FED_CBA9_8765_4321L);

        DomainWarp riskReference = new DomainWarp(noiseX, noiseZ, tuning.strongAmplitude(), tuning.frequency(), tuning.octaves());
        SafetyField safetyField = new SafetyField(
            riskReference, -2048 - fieldMargin, 2048 + fieldMargin, tuning.latticeSpacing(),
            tuning.dangerDeterminant(), tuning.comfortableDeterminant(), tuning.dilateRadiusCells()
        );

        double sum = 0;
        int count = 0;
        int nearFull = 0;
        for (double x = 0; x < 2048; x += 32) {
            for (double z = 0; z < 2048; z += 32) {
                double safety = safetyField.safetyAt(x, z);
                sum += safety;
                count++;
                if (safety > 0.9) {
                    nearFull++;
                }
            }
        }
        double meanSafety = sum / count;
        double fractionNearFull = (double) nearFull / count;
        assertTrue(meanSafety > 0.8, "mean safety " + meanSafety + " is too low - the adaptive tuning is dampening far more of the map than intended");
        assertTrue(fractionNearFull > 0.7, "fraction near full strength " + fractionNearFull + " is too low - same regression as above");
    }

    @Test
    void aKnownTooStrongTuningDoesFold() {
        // amplitude=40, frequency=0.01, octaves=3 - looked clean in the field spike's own
        // early render but measures past the fold threshold; kept here as a negative
        // control so isFoldFreeWithMargin's own logic is proven to actually detect a fold,
        // not just always pass.
        CoherentNoise2D noiseX = new CoherentNoise2D(42L ^ 0x1234_5678_9ABC_DEF0L);
        CoherentNoise2D noiseZ = new CoherentNoise2D(42L ^ 0x0FED_CBA9_8765_4321L);
        DomainWarp warp = new DomainWarp(noiseX, noiseZ, 40, 0.01, 3);
        assertFalse(DomainWarpAnalysis.isFoldFreeWithMargin(warp, -2000, 2000, 8.0, SAFETY_MARGIN));
    }
}
