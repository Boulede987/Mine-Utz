package com.mineutze.worldgen.coherence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A world's biome map must come out identical on every machine and every run (2026-09-20): the
 * same world seed on another CPU or JVM must give the same pattern and the same borders, or newly
 * generated chunks would seam against old ones. {@code Math.sin/cos/exp/log/pow/hypot...} may differ
 * between JVMs and CPUs by one unit in the last place; only {@code StrictMath} (or arithmetic built
 * from {@code + - * /} and {@code sqrt}, which Java 17+ makes exactly reproducible) is safe. These
 * rules are checked on the SOURCE of every production class, so a careless {@code Math.sin} added
 * later fails here instead of surfacing as a seam in somebody's world.
 */
class ReproducibilityRulesTest {

    /** Every class that takes part in producing a world's biome map. Study tools and pictures are deliberately not listed. */
    private static final List<String> PRODUCTION_CLASSES = List.of(
        "BiomeMap", "BiomeMapRasterizer", "BiomeMapVerifier", "ClusterSizeModel", "DeterministicTrig", "DivergenceFreeWarp", "HexTorusGeometry", "ParallelSeedSearch",
        "PatternClusters", "RawRuleTable", "RealizationSelector", "RealizedBiomeField", "ShapeRule", "ToroidalGrid", "ToroidalPos", "ToroidalSizedLocalSearch",
        "ToroidalSizedPatternGenerator"
    );

    private static final Path SOURCE_DIRECTORY = Path.of("src", "main", "java", "com", "mineutze", "worldgen", "coherence");

    private static final Pattern NON_REPRODUCIBLE_MATH = Pattern.compile("(?<![A-Za-z])Math\\.(sin|cos|tan|asin|acos|atan|atan2|sinh|cosh|tanh|exp|expm1|log|log10|log1p|pow|cbrt|hypot|random)\\s*\\(");
    private static final Pattern UNSEEDED_RANDOM = Pattern.compile("new\\s+(java\\.util\\.)?(Random|SplittableRandom)\\s*\\(\\s*\\)");
    private static final Pattern WALL_CLOCK = Pattern.compile("System\\.(currentTimeMillis|nanoTime)\\s*\\(");

    private static String codeOf(String className) throws IOException {
        String text = Files.readString(SOURCE_DIRECTORY.resolve(className + ".java"));
        return text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    private static List<String> violations(Pattern forbidden) throws IOException {
        List<String> found = new ArrayList<>();
        for (String className : PRODUCTION_CLASSES) {
            Matcher matcher = forbidden.matcher(codeOf(className));
            while (matcher.find()) {
                found.add(className + ": " + matcher.group());
            }
        }
        return found;
    }

    @Test
    void everyListedProductionClassExists() {
        for (String className : PRODUCTION_CLASSES) {
            assertTrue(Files.exists(SOURCE_DIRECTORY.resolve(className + ".java")), className + " is listed but its source was not found - was it renamed or moved?");
        }
    }

    @Test
    void noProductionClassCallsAMathFunctionThatMayDifferBetweenMachines() throws IOException {
        List<String> found = violations(NON_REPRODUCIBLE_MATH);

        assertTrue(found.isEmpty(), "use StrictMath or DeterministicTrig instead: " + found);
    }

    @Test
    void noProductionClassUsesAnUnseededRandomGenerator() throws IOException {
        List<String> found = violations(UNSEEDED_RANDOM);

        assertTrue(found.isEmpty(), "every random draw must come from the world seed: " + found);
    }

    @Test
    void noProductionClassReadsTheClockBecauseTheResultWouldDependOnWhenItRan() throws IOException {
        List<String> found = violations(WALL_CLOCK);

        assertTrue(found.isEmpty(), "results must not depend on time: " + found);
    }
}
