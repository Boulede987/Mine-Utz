package com.mineutze.worldgen.tooling.graphedit;

import com.mineutze.worldgen.biomegraph.MinimalJson;

import java.time.Duration;
import java.util.Map;

/** How long the Reliability run goes (user decision 2026-11-03: 1-120 min): from the page's POST body to the run's one gradle argument. */
record ReliabilityRequest(int minutes) {

    private static final int FEWEST_MINUTES = 1;
    private static final int MOST_MINUTES = 120;
    private static final String MINUTES_KEY = "minutes";
    private static final String GRADLE_ARGS_PREFIX = "--args=";

    ReliabilityRequest {
        if (minutes < FEWEST_MINUTES || minutes > MOST_MINUTES) {
            throw new IllegalArgumentException("ReliabilityRequest: minutes must be " + FEWEST_MINUTES + " to " + MOST_MINUTES + ", got " + minutes);
        }
    }

    static ReliabilityRequest parse(String json) {
        if (!(MinimalJson.parse(json) instanceof Map<?, ?> body) || !(body.get(MINUTES_KEY) instanceof Double minutes)) {
            throw new IllegalArgumentException("ReliabilityRequest: expected a JSON object with a number of " + MINUTES_KEY);
        }
        if (minutes != Math.rint(minutes) || Math.abs(minutes) > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("ReliabilityRequest: " + MINUTES_KEY + " " + minutes + " is not a whole number");
        }
        return new ReliabilityRequest(minutes.intValue());
    }

    /** The run's side of gradleArgument (the value after --args=). */
    static ReliabilityRequest fromArgument(String argument) {
        try {
            return new ReliabilityRequest(Integer.parseInt(argument));
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException("ReliabilityRequest: '" + argument + "' is not a number of minutes", notANumber);
        }
    }

    Duration wallTime() {
        return Duration.ofMinutes(minutes);
    }

    String gradleArgument() {
        return GRADLE_ARGS_PREFIX + minutes;
    }
}
