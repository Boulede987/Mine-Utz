package com.mineutze.worldgen.tooling.graphedit;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Stand-in for a gradle run in GradleRun tests. Args: lineCount delayMillis exitCode [spawnChild]. With spawnChild it
 * first starts a child JVM that sleeps forever and prints "child <pid>", so Stop's process-tree kill can be checked.
 */
public final class LinePrinter {

    static final String CHILD_PREFIX = "child ";
    private static final long FOREVER_MILLIS = 600_000;
    private static final String SLEEP_FOREVER = "sleepForever";
    private static final String SPAWN_CHILD = "spawnChild";
    private static final int LINES_FOR_EVER = 1000;
    private static final int SECOND_MILLIS = 1000;

    private LinePrinter() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length == 1 && args[0].equals(SLEEP_FOREVER)) {
            Thread.sleep(FOREVER_MILLIS);
            return;
        }
        if (args.length > 3 && args[3].equals(SPAWN_CHILD)) {
            spawnSleepingChild();
        }
        printLines(Integer.parseInt(args[0]), Long.parseLong(args[1]));
        System.exit(Integer.parseInt(args[2]));
    }

    private static void spawnSleepingChild() throws IOException {
        Process child = new ProcessBuilder(javaCommand(SLEEP_FOREVER)).start();
        printFlushed(CHILD_PREFIX + child.pid());
    }

    private static void printLines(int lineCount, long delayMillis) throws InterruptedException {
        for (int line = 1; line <= lineCount; line++) {
            printFlushed("line " + line);
            Thread.sleep(delayMillis);
        }
    }

    private static void printFlushed(String line) {
        System.out.println(line);
        System.out.flush();
    }

    /** Prints lineCount lines at once, then exits with exitCode. */
    static List<String> printsAndExits(int lineCount, int exitCode) {
        return javaCommand(String.valueOf(lineCount), "0", String.valueOf(exitCode));
    }

    /** One line a second, lineCount lines, exit 0. */
    static List<String> printsSlowly(int lineCount) {
        return javaCommand(String.valueOf(lineCount), String.valueOf(SECOND_MILLIS), "0");
    }

    /** Outlives any test: must be stopped. */
    static List<String> printsForever() {
        return printsSlowly(LINES_FOR_EVER);
    }

    /** printsForever, plus a sleeping child JVM whose pid is the first line. */
    static List<String> printsForeverWithChild() {
        List<String> command = new ArrayList<>(printsForever());
        command.add(SPAWN_CHILD);
        return command;
    }

    private static List<String> javaCommand(String... arguments) {
        List<String> command = new ArrayList<>(List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"), LinePrinter.class.getName()
        ));
        command.addAll(List.of(arguments));
        return command;
    }
}
