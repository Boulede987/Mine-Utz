package com.mineutze.worldgen.tooling.graphedit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReliabilityRequestTest {

    private static void assertRefused(Executable request, String reason) {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, request);
        assertTrue(refused.getMessage().contains(reason), refused.getMessage());
    }

    @Test
    void readsTheMinutes() {
        assertEquals(Duration.ofMinutes(25), ReliabilityRequest.parse("{\"minutes\": 25}").wallTime());
    }

    @Test
    void passesTheMinutesToGradleAsOneArgument() {
        assertEquals("--args=25", ReliabilityRequest.parse("{\"minutes\": 25}").gradleArgument());
    }

    @Test
    void theRunReadsBackWhatTheServerPassed() {
        ReliabilityRequest request = ReliabilityRequest.parse("{\"minutes\": 25}");

        assertEquals(request, ReliabilityRequest.fromArgument(request.gradleArgument().substring("--args=".length())));
    }

    @Test
    void refusesZeroMinutes() {
        assertRefused(() -> ReliabilityRequest.parse("{\"minutes\": 0}"), "1 to 120");
    }

    @Test
    void refusesMoreThanTwoHours() {
        assertRefused(() -> ReliabilityRequest.parse("{\"minutes\": 121}"), "1 to 120");
    }

    @Test
    void refusesFractionalMinutes() {
        assertRefused(() -> ReliabilityRequest.parse("{\"minutes\": 2.5}"), "whole number");
    }

    @Test
    void refusesMissingMinutes() {
        assertRefused(() -> ReliabilityRequest.parse("{}"), "minutes");
    }

    @Test
    void refusesANonNumberArgument() {
        assertRefused(() -> ReliabilityRequest.fromArgument("ten"), "not a number");
    }
}
