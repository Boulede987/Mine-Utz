package com.mineutze.worldgen.tooling.graphedit;

import java.util.List;
import java.util.Optional;

/** One run at a time: each uses every core, overlapping runs starve each other. */
final class CurrentRun {

    private Optional<GradleRun> current = Optional.empty();

    /** Empty while another run is still going. */
    synchronized Optional<GradleRun> start(String label, List<String> command) {
        if (current.isPresent() && !current.get().finished()) {
            return Optional.empty();
        }
        current = Optional.of(GradleRun.start(label, command));
        return current;
    }

    /** Latest run, finished or not: reloaded page resumes it. */
    synchronized Optional<GradleRun> current() {
        return current;
    }
}
