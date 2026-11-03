package com.mineutze.worldgen.tooling.graphedit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

record RunSnapshot(int runNumber, String label, List<String> newLines, int nextIndex, OptionalInt exitCode, boolean stopped) {

    RunSnapshot {
        newLines = List.copyOf(newLines);
    }

    /** Exit code read once, before lines: run seen finished always comes with all its output. */
    static RunSnapshot of(GradleRun run, int fromIndex) {
        OptionalInt exitCode = run.exitCode();
        List<String> newLines = run.linesFrom(fromIndex);
        return new RunSnapshot(run.number(), run.label(), newLines, fromIndex + newLines.size(), exitCode, run.stopped());
    }

    boolean finished() {
        return exitCode.isPresent();
    }

    Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("run", runNumber);
        json.put("label", label);
        json.put("lines", newLines);
        json.put("next", nextIndex);
        json.put("finished", finished());
        exitCode.ifPresent(code -> json.put("exitCode", code));
        json.put("stopped", stopped);
        return json;
    }
}
