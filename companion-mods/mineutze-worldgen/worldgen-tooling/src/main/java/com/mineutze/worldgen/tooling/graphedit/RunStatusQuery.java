package com.mineutze.worldgen.tooling.graphedit;

import java.util.OptionalInt;

/**
 * {@code /api/run?[run=R&]from=N}: page wants lines from index N of run R. Another run is current (other tab started
 * one, or first poll without R) = page gets that run from line 0.
 */
record RunStatusQuery(OptionalInt runNumber, int fromIndex) {

    private static final String RUN_KEY = "run";
    private static final String FROM_KEY = "from";

    RunStatusQuery {
        if (fromIndex < 0) {
            throw new IllegalArgumentException("RunStatusQuery: from must be >= 0, got " + fromIndex);
        }
    }

    /** Fails loud on a missing from, unknown key or non-number: a silent default would duplicate or drop lines. */
    static RunStatusQuery parse(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            throw new IllegalArgumentException("RunStatusQuery: expected [run=R&]from=N, got no query");
        }
        OptionalInt runNumber = OptionalInt.empty();
        OptionalInt fromIndex = OptionalInt.empty();
        for (String parameter : rawQuery.split("&")) {
            String[] keyAndValue = parameter.split("=", 2);
            int value = numberIn(keyAndValue, rawQuery);
            switch (keyAndValue[0]) {
                case RUN_KEY -> runNumber = OptionalInt.of(value);
                case FROM_KEY -> fromIndex = OptionalInt.of(value);
                default -> throw new IllegalArgumentException("RunStatusQuery: unknown key '" + keyAndValue[0] + "' in '" + rawQuery + "'");
            }
        }
        if (fromIndex.isEmpty()) {
            throw new IllegalArgumentException("RunStatusQuery: no from=N in '" + rawQuery + "'");
        }
        return new RunStatusQuery(runNumber, fromIndex.getAsInt());
    }

    private static int numberIn(String[] keyAndValue, String rawQuery) {
        if (keyAndValue.length != 2) {
            throw new IllegalArgumentException("RunStatusQuery: '" + keyAndValue[0] + "' has no value in '" + rawQuery + "'");
        }
        try {
            return Integer.parseInt(keyAndValue[1]);
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException("RunStatusQuery: '" + keyAndValue[1] + "' is not a number in '" + rawQuery + "'", notANumber);
        }
    }

    /** Index to read from in the given run: the page's own index only if it asked about that very run. */
    int fromIndexIn(GradleRun run) {
        return runNumber.isPresent() && runNumber.getAsInt() == run.number() ? fromIndex : 0;
    }
}
