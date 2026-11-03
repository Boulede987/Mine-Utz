package com.mineutze.worldgen.tooling.graphedit;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One gradle run (tests, Solve check, Verify), output kept line by line as printed so the editor can show it live.
 * Lives in the server, not the page: a reload neither kills nor restarts it.
 */
final class GradleRun {

    private static final AtomicInteger NEXT_NUMBER = new AtomicInteger(1);

    private final int number;
    private final String label;
    private final Process process;
    private final List<String> lines = Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean stopped = new AtomicBoolean();
    private volatile OptionalInt exitCode = OptionalInt.empty();

    private GradleRun(String label, Process process) {
        this.number = NEXT_NUMBER.getAndIncrement();
        this.label = label;
        this.process = process;
    }

    /** JAVA_HOME = this JVM's: wrapper's JVM check runs on child's inherited JAVA_HOME, OS default may be too old. */
    static GradleRun start(String label, List<String> command) {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        Process process;
        try {
            process = builder.start();
        } catch (IOException failure) {
            throw new UncheckedIOException("GradleRun: could not start " + label + ": " + command, failure);
        }
        GradleRun run = new GradleRun(label, process);
        Thread reader = new Thread(run::readUntilExit, "gradle-run-output");
        reader.setDaemon(true);
        reader.start();
        return run;
    }

    /** Exit code set last, and always: finished run has all its output, and a reader failure cannot leave it running. */
    private void readUntilExit() {
        try {
            copyOutputLines();
        } catch (IOException | RuntimeException failure) {
            recordFailure("output stream broke", failure);
        } finally {
            exitCode = OptionalInt.of(awaitExit());
        }
    }

    private void copyOutputLines() throws IOException {
        try (BufferedReader output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            output.lines().forEach(lines::add);
        } catch (UncheckedIOException failure) {
            throw failure.getCause();
        }
    }

    /** Interrupt = nobody waits any more: kill so the run still ends with a real exit code. */
    private int awaitExit() {
        try {
            return process.waitFor();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            recordFailure("interrupted while waiting for exit, process killed", interrupted);
            process.destroyForcibly();
            return process.onExit().join().exitValue();
        }
    }

    private void recordFailure(String what, Throwable failure) {
        StringWriter stackTrace = new StringWriter();
        failure.printStackTrace(new PrintWriter(stackTrace));
        lines.add("GradleRun: " + label + ": " + what + ":");
        lines.addAll(stackTrace.toString().lines().toList());
    }

    int number() {
        return number;
    }

    String label() {
        return label;
    }

    int lineCount() {
        return lines.size();
    }

    List<String> linesFrom(int index) {
        synchronized (lines) {
            if (index < 0 || index > lines.size()) {
                throw new IllegalArgumentException("GradleRun: line index " + index + " outside 0.." + lines.size() + " of run " + number + " (" + label + ")");
            }
            return List.copyOf(lines.subList(index, lines.size()));
        }
    }

    boolean finished() {
        return exitCode.isPresent();
    }

    OptionalInt exitCode() {
        return exitCode;
    }

    boolean stopped() {
        return stopped.get();
    }

    /**
     * False if already over. Parent first so it spawns nothing new; descendants captured before (gradle daemon's build
     * JVMs would keep every core busy after the wrapper died).
     */
    boolean stop() {
        if (!process.isAlive()) {
            return false;
        }
        stopped.set(true);
        List<ProcessHandle> descendants = process.descendants().toList();
        process.destroyForcibly();
        descendants.forEach(ProcessHandle::destroyForcibly);
        return true;
    }
}
