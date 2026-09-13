package com.mineutze.worldgen.export;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.ModBiomeGraph;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Regenerates {@code doc/biome-graph-completeness-cache.txt} - EXHAUSTIVE (not sampled)
 * list of every MINIMAL biome combo with no universal legal candidate against {@link
 * ModBiomeGraph#OVERWORLD} (see {@link BiomeGraph#findMinimalUnsolvablePartialNeighborhoods}
 * for why "minimal" - raw non-minimal version found 1.8M+ redundant supersets). Cached
 * against {@link BiomeGraph#rulesFingerprint}: no rule change = no-op read, not recompute.
 * Rules only change via user's own Excel planner - this tool never edits {@code
 * ModBiomeGraph}, only reports on it.
 *
 * <p>Run: {@code ./gradlew :worldgen-core:biomeGraphCompletenessReport} after any rule
 * change, refresh cache file, review result, fix worthwhile conflicts via Excel planner -
 * same as already fixed ({@code stony_peaks}/{@code snowy_beach}/{@code cold_ocean}).
 */
public final class BiomeGraphCompletenessReport {

    private static final int MAX_NEIGHBORS = 6; // real hex mesh's own max face degree
    // "..": JavaExec + test task working dir defaults to worldgen-core/, not mod root
    // where doc/ lives (alongside DESIGN.md, IDEAS.txt, Excel planner) - confirmed, not
    // assumed (earlier version without ".." silently created stray worldgen-core/doc/).
    private static final Path CACHE_FILE = Path.of("..", "doc", "biome-graph-completeness-cache.txt");

    private BiomeGraphCompletenessReport() {
    }

    public static void main(String[] args) {
        BiomeGraph graph = ModBiomeGraph.OVERWORLD;
        String fingerprint = graph.rulesFingerprint();

        List<List<BiomeId>> minimalConflicts;
        Optional<CachedReport> existing = readCache(CACHE_FILE);
        if (existing.isPresent() && existing.get().fingerprint().equals(fingerprint)) {
            System.out.println("Cache up to date (fingerprint " + fingerprint + ") - no rule change since last run, nothing recomputed.");
            minimalConflicts = existing.get().conflicts();
        } else {
            System.out.println("Fingerprint changed (or no cache yet) - recomputing against the real graph...");
            long startNanos = System.nanoTime();
            minimalConflicts = graph.findMinimalUnsolvablePartialNeighborhoods(MAX_NEIGHBORS);
            long durationMillis = (System.nanoTime() - startNanos) / 1_000_000;
            System.out.println("Done in " + durationMillis + "ms.");
            writeCache(CACHE_FILE, fingerprint, minimalConflicts);
            System.out.println("Wrote " + CACHE_FILE.toAbsolutePath() + " - review it, and commit it alongside any real rule change.");
        }

        printSummary(minimalConflicts);
    }

    /**
     * Real "why" feedback, not just raw count - size breakdown (2-biome gaps = zero shared
     * neighbor; 3+ = every smaller combo inside is fine, only whole group has no bridge) +
     * frequency ranking (fix biome appearing in most conflicts first via Excel planner, for
     * most impact per fix, instead of picking at random).
     */
    private static void printSummary(List<List<BiomeId>> minimalConflicts) {
        Map<Integer, Long> countBySize = new TreeMap<>();
        Map<BiomeId, Integer> conflictCountByBiome = new HashMap<>();
        for (List<BiomeId> conflict : minimalConflicts) {
            countBySize.merge(conflict.size(), 1L, Long::sum);
            for (BiomeId member : conflict) {
                conflictCountByBiome.merge(member, 1, Integer::sum);
            }
        }

        System.out.println();
        System.out.println(minimalConflicts.size() + " total minimal, placeable, unsolvable combination(s).");
        System.out.println("By size (2 = a pure pair with zero shared neighbor; 3+ = every smaller piece inside it is fine, only the whole group has no bridge):");
        countBySize.forEach((size, count) -> System.out.println("  size " + size + ": " + count));

        System.out.println();
        System.out.println("Top 15 biomes by how many minimal conflicts they appear in (fix this biome's own neighbor list first for the most impact):");
        conflictCountByBiome.entrySet().stream()
            .sorted(Comparator.<Map.Entry<BiomeId, Integer>>comparingInt(Map.Entry::getValue).reversed())
            .limit(15)
            .forEach(entry -> System.out.println("  " + entry.getKey() + ": appears in " + entry.getValue() + " conflict(s)"));
    }

    /** Read-only check for a caller (e.g. JUnit test) that must never recompute silently - fails loud if cache missing/stale. */
    public static boolean isCacheUpToDate(BiomeGraph graph, Path cacheFile) {
        return readCache(cacheFile).map(cached -> cached.fingerprint().equals(graph.rulesFingerprint())).orElse(false);
    }

    public static Path defaultCacheFile() {
        return CACHE_FILE;
    }

    record CachedReport(String fingerprint, List<List<BiomeId>> conflicts) {
    }

    public static Optional<CachedReport> readCache(Path cacheFile) {
        if (!Files.exists(cacheFile)) {
            return Optional.empty();
        }
        try {
            List<String> lines = Files.readAllLines(cacheFile, StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                return Optional.empty();
            }
            String fingerprint = lines.get(0);
            List<List<BiomeId>> conflicts = new ArrayList<>();
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i).strip();
                if (line.isEmpty()) {
                    continue;
                }
                List<BiomeId> members = new ArrayList<>();
                for (String token : line.split(",\\s*")) {
                    String[] parts = token.split(":", 2);
                    members.add(new BiomeId(parts[0], parts[1]));
                }
                conflicts.add(members);
            }
            return Optional.of(new CachedReport(fingerprint, conflicts));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed reading " + cacheFile, e);
        }
    }

    private static void writeCache(Path cacheFile, String fingerprint, List<List<BiomeId>> conflicts) {
        StringBuilder out = new StringBuilder();
        out.append(fingerprint).append('\n');
        for (List<BiomeId> conflict : conflicts) {
            out.append(String.join(", ", conflict.stream().map(BiomeId::toString).toList())).append('\n');
        }
        try {
            if (cacheFile.getParent() != null) {
                Files.createDirectories(cacheFile.getParent());
            }
            Files.writeString(cacheFile, out.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed writing " + cacheFile, e);
        }
    }
}
