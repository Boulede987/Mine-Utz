package com.mineutze.worldgen.tooling.graphedit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifyRequestTest {

    private static void assertRefused(Executable request, String reason) {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, request);
        assertTrue(refused.getMessage().contains(reason), refused.getMessage());
    }

    @Test
    void readsRanksAndFingerprint() {
        assertEquals(new VerifyRequest(List.of(2, 5), "abc"), VerifyRequest.parse("{\"ranks\": [2, 5], \"fingerprint\": \"abc\"}"));
    }

    @Test
    void passesPicksAndFingerprintToGradleAsOneArgument() {
        assertEquals("--args=2,5:abc", new VerifyRequest(List.of(2, 5), "abc").gradleArgument());
    }

    @Test
    void theVerifyRunReadsBackWhatTheServerPassed() {
        VerifyRequest request = new VerifyRequest(List.of(2, 5), "abc");

        assertEquals(request, VerifyRequest.fromArgument(request.gradleArgument().substring("--args=".length())));
    }

    @Test
    void refusesAFractionalRank() {
        assertRefused(() -> VerifyRequest.parse("{\"ranks\": [2.5], \"fingerprint\": \"abc\"}"), "whole number");
    }

    @Test
    void refusesARankBeyondTheIntegerRange() {
        assertRefused(() -> VerifyRequest.parse("{\"ranks\": [1e12], \"fingerprint\": \"abc\"}"), "whole number");
    }

    @Test
    void refusesARankThatIsNotANumber() {
        assertRefused(() -> VerifyRequest.parse("{\"ranks\": [\"2\"], \"fingerprint\": \"abc\"}"), "whole number");
    }

    @Test
    void refusesMissingRanks() {
        assertRefused(() -> VerifyRequest.parse("{\"fingerprint\": \"abc\"}"), "ranks");
    }

    @Test
    void refusesNoPickedRank() {
        assertRefused(() -> VerifyRequest.parse("{\"ranks\": [], \"fingerprint\": \"abc\"}"), "no law picked");
    }

    @Test
    void refusesAMissingFingerprint() {
        assertRefused(() -> VerifyRequest.parse("{\"ranks\": [2]}"), "fingerprint");
    }

    /** Page clicked Verify before a ranking loaded. */
    @Test
    void refusesANullFingerprint() {
        assertRefused(() -> VerifyRequest.parse("{\"ranks\": [2], \"fingerprint\": null}"), "fingerprint");
    }

    @Test
    void refusesAFingerprintThatIsNotText() {
        assertRefused(() -> VerifyRequest.parse("{\"ranks\": [2], \"fingerprint\": 7}"), "fingerprint");
    }

    @Test
    void refusesABodyThatIsNotAnObject() {
        assertRefused(() -> VerifyRequest.parse("[2]"), "JSON object");
    }

    @Test
    void refusesARankBelowOne() {
        assertRefused(() -> VerifyRequest.fromArgument("0,4:abc"), "count from 1");
    }

    @Test
    void refusesAnEmptyArgument() {
        assertRefused(() -> VerifyRequest.fromArgument(""), "ranks:fingerprint");
    }

    @Test
    void refusesAnArgumentWithoutFingerprint() {
        assertRefused(() -> VerifyRequest.fromArgument("1,4"), "ranks:fingerprint");
    }

    @Test
    void refusesATrailingComma() {
        assertRefused(() -> VerifyRequest.fromArgument("1,4,:abc"), "not a number");
    }

    @Test
    void refusesAnEmptyRankInTheMiddle() {
        assertRefused(() -> VerifyRequest.fromArgument("1,,4:abc"), "not a number");
    }

    @Test
    void refusesSpacesAroundRanks() {
        assertRefused(() -> VerifyRequest.fromArgument(" 1,4:abc"), "not a number");
    }
}
