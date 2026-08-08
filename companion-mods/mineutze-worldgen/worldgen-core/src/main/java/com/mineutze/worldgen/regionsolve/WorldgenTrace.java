package com.mineutze.worldgen.regionsolve;

/**
 * Ad-hoc per-cell claim tracing for diagnosing shape defects (e.g. why a
 * specific cell ended up a one-cell-wide pinch point) — not a real logging
 * framework on purpose, this module stays dependency-free (see
 * {@code worldgen-core}'s build.gradle comment). Off by default so normal
 * runs/tests stay quiet; set the {@code MINEUTZE_WORLDGEN_TRACE} environment
 * variable to any value to enable. Deliberately left in the code rather than
 * stripped after use — cheap to keep, next shape investigation won't need to
 * re-add it from scratch.
 */
final class WorldgenTrace {

    private static final boolean ENABLED = System.getenv("MINEUTZE_WORLDGEN_TRACE") != null;

    private WorldgenTrace() {
    }

    static void log(String message) {
        if (ENABLED) {
            System.out.println("[trace] " + message);
        }
    }
}
