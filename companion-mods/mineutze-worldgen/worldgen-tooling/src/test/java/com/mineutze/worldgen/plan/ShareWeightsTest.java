package com.mineutze.worldgen.plan;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShareWeightsTest {

    @Test
    void scalesEachShareByTheLeastCommonMultipleOfTheMinimums() {
        Optional<ShareWeights> weights = ShareWeights.of(List.of(2, 3, 4), 1_000, 100);

        assertEquals(12, weights.orElseThrow().patchWeight());
        assertEquals(List.of(6, 4, 3), weights.orElseThrow().shareWeights());
    }

    /** Review finding 2026-10-31: weights times bounds can overflow int inside Choco and flip a solvable graph to CONTRADICTION. */
    @Test
    void refusesWeightsWhoseTermsCouldOverflowAnInt() {
        Optional<ShareWeights> weights = ShareWeights.of(List.of(29, 31, 37, 41, 43), 4_800, 1_600);

        assertEquals(Optional.empty(), weights);
    }

    @Test
    void aSingleShareKeepsWeightOne() {
        ShareWeights weights = ShareWeights.of(List.of(5), 4_800, 1_600).orElseThrow();

        assertEquals(List.of(1), weights.shareWeights());
        assertEquals(5, weights.patchWeight());
    }
}
