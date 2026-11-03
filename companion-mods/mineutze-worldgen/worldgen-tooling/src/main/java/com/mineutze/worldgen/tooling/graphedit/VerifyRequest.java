package com.mineutze.worldgen.tooling.graphedit;

import com.mineutze.worldgen.biomegraph.MinimalJson;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Picked ranks (from 1) and the fingerprint of the ranking they were picked on: from the page's POST body to the Verify
 * run's one gradle argument {@code 4,5,6:<fingerprint>}, so the run re-checks the ranking it actually reads.
 */
record VerifyRequest(List<Integer> ranks, String fingerprint) {

    private static final String RANKS_KEY = "ranks";
    private static final String FINGERPRINT_KEY = "fingerprint";
    private static final String RANK_SEPARATOR = ",";
    /** Not in a hex fingerprint, and plain in cmd.exe (unlike ';' or '&'). */
    private static final String FINGERPRINT_SEPARATOR = ":";
    private static final String GRADLE_ARGS_PREFIX = "--args=";

    /** Shape only; whether the picks are allowed is TightnessVerify.checkPicks' job. */
    VerifyRequest {
        Objects.requireNonNull(ranks, "ranks");
        ranks = List.copyOf(ranks);
        if (ranks.isEmpty()) {
            throw new IllegalArgumentException("VerifyRequest: no law picked - tick at least one in the ranking");
        }
        if (ranks.stream().anyMatch(rank -> rank < 1)) {
            throw new IllegalArgumentException("VerifyRequest: ranks count from 1, got " + ranks);
        }
        if (fingerprint == null || fingerprint.isBlank() || fingerprint.contains(FINGERPRINT_SEPARATOR)) {
            throw new IllegalArgumentException("VerifyRequest: no ranking fingerprint - reload the ranking (run a Solve check first if there is none)");
        }
    }

    static VerifyRequest parse(String json) {
        if (!(MinimalJson.parse(json) instanceof Map<?, ?> body)) {
            throw new IllegalArgumentException("VerifyRequest: expected a JSON object with " + RANKS_KEY + " and " + FINGERPRINT_KEY);
        }
        if (!(body.get(RANKS_KEY) instanceof List<?> ranks)) {
            throw new IllegalArgumentException("VerifyRequest: no " + RANKS_KEY + " list");
        }
        if (!(body.get(FINGERPRINT_KEY) instanceof String fingerprint)) {
            throw new IllegalArgumentException("VerifyRequest: no " + FINGERPRINT_KEY + " text");
        }
        return new VerifyRequest(ranks.stream().map(VerifyRequest::wholeNumber).toList(), fingerprint);
    }

    private static int wholeNumber(Object rank) {
        if (!(rank instanceof Double number) || number != Math.rint(number) || Math.abs(number) > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("VerifyRequest: rank " + rank + " is not a whole number");
        }
        return number.intValue();
    }

    /** One token, no spaces: survives gradlew.bat's argument handling unquoted. */
    String gradleArgument() {
        return GRADLE_ARGS_PREFIX + ranks.stream().map(String::valueOf).collect(Collectors.joining(RANK_SEPARATOR)) + FINGERPRINT_SEPARATOR + fingerprint;
    }

    /** The Verify run's side of gradleArgument (the value after --args=). */
    static VerifyRequest fromArgument(String argument) {
        String[] ranksAndFingerprint = argument.split(FINGERPRINT_SEPARATOR, -1);
        if (ranksAndFingerprint.length != 2) {
            throw new IllegalArgumentException("VerifyRequest: expected ranks:fingerprint like 1,4,7:<hex>, got '" + argument + "'");
        }
        List<Integer> ranks = Arrays.stream(ranksAndFingerprint[0].split(RANK_SEPARATOR, -1)).map(rank -> rankIn(rank, argument)).toList();
        return new VerifyRequest(ranks, ranksAndFingerprint[1]);
    }

    private static int rankIn(String rank, String argument) {
        try {
            return Integer.parseInt(rank);
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException("VerifyRequest: rank '" + rank + "' is not a number in '" + argument + "' (expected like 1,4,7:<hex>)", notANumber);
        }
    }
}
