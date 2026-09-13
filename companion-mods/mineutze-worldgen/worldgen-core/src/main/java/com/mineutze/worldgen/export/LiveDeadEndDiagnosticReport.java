package com.mineutze.worldgen.export;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;
import com.mineutze.worldgen.biomegraph.ModBiomeGraph;

import com.mineutze.worldgen.regionsolve.MacroGridConfig;

import com.mineutze.worldgen.regionsolve.livepath.DecidedFaceStore;
import com.mineutze.worldgen.regionsolve.livepath.DecidedVertexStore;
import com.mineutze.worldgen.regionsolve.livepath.SeedMeshCache;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real, REACHABLE dead ends only - runs the actual live query path
 * ({@link SeedMeshCache}) over a dense grid across many seeds, same
 * methodology as the already-existing (JUnit-only, no standalone report)
 * {@code ModBiomeGraphTest.diagnoseAllReachableDeadEndsAcrossManySeeds} -
 * a genuinely different, much smaller, much more actionable list than
 * {@link BiomeGraphCompletenessReport}'s own exhaustive ABSTRACT sweep
 * (3,118 minimal combinations as of this writing - real, but most could
 * never actually occur as a real face's own neighbor context; this report
 * only ever lists ones a real query pattern actually hit).
 *
 * <p>Self-explanatory on purpose - user's own explicit request: no need to
 * come back and ask what an entry means. Every entry gets a plain-language
 * explanation AND one concrete, exemplary {@code AdjacencyRule} to close
 * it, picked by finding whichever existing catalog biome is ALREADY legal
 * against the most members of the conflict (same "closest bridge" logic a
 * human would reach for) - not a guarantee that exact rule is the right
 * THEMATIC choice (that's still a real game-design call, see this report's
 * own closing note), just a concrete, correct, verifiable starting point.
 *
 * <p>Run: {@code ./gradlew :worldgen-core:liveDeadEndDiagnosticReport}
 */
public final class LiveDeadEndDiagnosticReport {

    private static final int SEED_TRIAL_COUNT = 50;
    private static final long SEED_BASE = 2026L;
    private static final double HEX_SIZE_BLOCKS = 325; // matches TriangleFieldGeometryRealization.HEX_SIZE_BLOCKS
    private static final double AREA_HALF_WIDTH_BLOCKS = 1500;
    private static final double QUERY_SPACING_BLOCKS = HEX_SIZE_BLOCKS * 0.9;

    // Placeholder only - the real blend width is a real-content/game-design call (existing
    // rules in ModBiomeGraph.OVERWORLD range roughly 6-15 depending on how gradual the
    // two biomes' own transition should read) - never applied automatically, always a
    // starting number for a human to adjust via the Excel planner, same discipline
    // BiomeGraphCompletenessReport's own proposed fixes already follow.
    private static final int EXAMPLE_BLEND_WIDTH_BLOCKS = 10;

    private static final Pattern FIXED_FACE_BIOMES_PATTERN = Pattern.compile("attempt2FixedFaceBiomes=\\{([^}]*)}");

    private static final Path REPORT_FILE = Path.of("..", "doc", "live-dead-end-diagnostic-report.txt");

    private LiveDeadEndDiagnosticReport() {
    }

    public static void main(String[] args) {
        BiomeGraph graph = ModBiomeGraph.OVERWORLD;
        System.out.println("Sweeping " + SEED_TRIAL_COUNT + " seeds over a dense query grid against the real live path (SeedMeshCache) - this runs real CSP solves, may take a minute...");
        long startNanos = System.nanoTime();
        List<Set<BiomeId>> deadEnds = findRealReachableDeadEnds(graph);
        long durationMillis = (System.nanoTime() - startNanos) / 1_000_000;
        System.out.println("Done in " + durationMillis + "ms. Found " + deadEnds.size() + " distinct real, reachable dead-end neighbor combination(s).");

        String report = buildReport(graph, deadEnds);
        writeReport(REPORT_FILE, report);
        System.out.println("Wrote " + REPORT_FILE.toAbsolutePath() + " - self-contained, read it directly, every entry explains itself.");
        System.out.println();
        System.out.print(report);
    }

    /** Every DISTINCT fixed-neighbor-biome set that actually produced a real {@code IllegalStateException} dead end somewhere in the sweep - same parsing this report's own doc points at, matching {@code ModBiomeGraphTest}'s own established methodology exactly (same seeds, same grid, same exception shape). */
    private static List<Set<BiomeId>> findRealReachableDeadEnds(BiomeGraph graph) {
        Set<Set<BiomeId>> distinct = new LinkedHashSet<>();
        for (int trial = 0; trial < SEED_TRIAL_COUNT; trial++) {
            long worldSeed = SEED_BASE + trial;
            DecidedFaceStore faceStore = new DecidedFaceStore();
            DecidedVertexStore vertexStore = new DecidedVertexStore();
            SeedMeshCache cache = new SeedMeshCache(graph, MacroGridConfig.DEFAULT, worldSeed, faceStore, vertexStore);
            for (double x = -AREA_HALF_WIDTH_BLOCKS; x <= AREA_HALF_WIDTH_BLOCKS; x += QUERY_SPACING_BLOCKS) {
                for (double z = -AREA_HALF_WIDTH_BLOCKS; z <= AREA_HALF_WIDTH_BLOCKS; z += QUERY_SPACING_BLOCKS) {
                    try {
                        cache.biomeAt(MacroGridConfig.DEFAULT.cellContaining(x, z));
                    } catch (IllegalStateException e) {
                        parseFixedFaceBiomes(e.getMessage()).ifPresent(distinct::add);
                    }
                }
            }
        }
        return new ArrayList<>(distinct);
    }

    private static java.util.Optional<Set<BiomeId>> parseFixedFaceBiomes(String exceptionMessage) {
        Matcher matcher = FIXED_FACE_BIOMES_PATTERN.matcher(exceptionMessage);
        if (!matcher.find()) {
            return java.util.Optional.empty(); // a genuinely different dead-end shape (rare) - not this report's concern, skipped rather than mis-parsed
        }
        Set<BiomeId> biomes = new TreeSet<>();
        for (String entry : matcher.group(1).split(", ")) {
            String[] keyValue = entry.split("=");
            if (keyValue.length != 2) {
                continue;
            }
            String[] parts = keyValue[1].split(":");
            biomes.add(new BiomeId(parts[0], parts[1]));
        }
        return java.util.Optional.of(biomes);
    }

    /** One candidate biome's own fit against a conflict - how many members it's ALREADY legal against, and exactly which ones it's still missing. */
    private record CandidateFit(BiomeId biome, int legalCount, List<BiomeId> missing) {
    }

    private static String buildReport(BiomeGraph graph, List<Set<BiomeId>> deadEnds) {
        List<BiomeId> allBiomeIds = graph.nodes().stream().map(BiomeNode::id).sorted().toList();
        StringBuilder out = new StringBuilder();
        out.append("Real, reachable dead ends found by sweeping ").append(SEED_TRIAL_COUNT)
            .append(" seeds over a dense query grid against the real live path.\n")
            .append(deadEnds.size()).append(" distinct combination(s) - each one is a REAL neighbor context a real query actually hit, not a theoretical one.\n")
            .append("Most of these are pure ADJACENCY gaps (no biome is even legal here) - but NOT all: an entry marked \"NOT an adjacency gap\" below ")
            .append("means a fully adjacency-legal candidate already exists, so that specific dead end is SIZE-driven instead (checked directly per entry, not assumed).\n\n");

        int index = 1;
        for (Set<BiomeId> deadEnd : deadEnds) {
            out.append(index).append(". ").append(deadEnd).append('\n');
            out.append("   Problem: a real query hit this exact already-decided neighbor context and had no legal biome to place there.\n");
            appendProposedFix(out, graph, allBiomeIds, deadEnd);
            out.append('\n');
            index++;
        }

        out.append("Every proposed fix above is a real, verifiable STARTING POINT (closest existing bridge biome, computed directly), not an automatic answer - ")
            .append("whether the suggested pair makes thematic sense is a real game-design call, same as the already-fixed stony_shore/snowy_beach gap. ")
            .append("Add the rule via the Excel planner, regenerate ModBiomeGraph, re-run this report to confirm it's gone.\n");
        return out.toString();
    }

    private static void appendProposedFix(StringBuilder out, BiomeGraph graph, List<BiomeId> allBiomeIds, Set<BiomeId> deadEnd) {
        List<CandidateFit> ranked = rankCandidates(graph, allBiomeIds, deadEnd);
        int bestLegalCount = ranked.isEmpty() ? 0 : ranked.get(0).legalCount();
        if (bestLegalCount == deadEnd.size()) {
            // Real, checked-not-assumed case: a candidate is ALREADY adjacency-legal
            // against every member, yet the real solve still dead-ended here - pure
            // ADJACENCY can't be the real cause (a legal candidate genuinely exists), so
            // this specific dead end is almost certainly SIZE-driven (the live path's own
            // achievable-range hard exclusion, not this report's own isAllowed-only
            // ranking) - a real gap in what this ranking alone can diagnose, flagged
            // honestly rather than proposing a rule that wouldn't actually fix anything.
            List<CandidateFit> fullyLegal = ranked.stream().filter(candidate -> candidate.legalCount() == deadEnd.size()).toList();
            out.append("   NOT an adjacency gap - ").append(fullyLegal.stream().map(CandidateFit::biome).toList())
                .append(" ").append(fullyLegal.size() == 1 ? "is" : "are").append(" already ADJACENCY-legal against every one of these, ")
                .append("yet the real solve still failed here. The real cause is almost certainly SIZE (the live path's own achievable-range ")
                .append("exclusion given real committed neighbor geometry at this specific spot), not a missing AdjacencyRule - adding one here ")
                .append("would not fix it. Needs direct investigation of the real geometry at this occurrence, not a rule change.\n");
            return;
        }
        out.append("   Confirmed adjacency gap - no biome in the 48-biome catalog is ADJACENCY-legal against all of these simultaneously.\n");
        if (bestLegalCount == 0) {
            out.append("   Proposed fix: no existing biome is even PARTIALLY compatible with this set - ")
                .append("needs a brand new bridging rule (or biome) from scratch, not a simple extension.\n");
            return;
        }
        List<CandidateFit> tiedBest = ranked.stream().filter(candidate -> candidate.legalCount() == bestLegalCount).limit(3).toList();
        for (CandidateFit candidate : tiedBest) {
            out.append("   Proposed fix: ").append(candidate.biome()).append(" is already legal against ")
                .append(candidate.legalCount()).append('/').append(deadEnd.size())
                .append(" of these - missing only ").append(candidate.missing()).append(". Example rule(s) to add:\n");
            for (BiomeId missing : candidate.missing()) {
                out.append("     AdjacencyRule.allow(").append(shortConstantName(candidate.biome())).append(", ")
                    .append(shortConstantName(missing)).append(", ").append(EXAMPLE_BLEND_WIDTH_BLOCKS).append(")\n");
            }
        }
    }

    /** Every catalog biome's own fit against {@code deadEnd}, best (highest legal count) first - the "closest existing bridge" a human would reach for too. */
    private static List<CandidateFit> rankCandidates(BiomeGraph graph, List<BiomeId> allBiomeIds, Set<BiomeId> deadEnd) {
        List<CandidateFit> ranked = new ArrayList<>();
        for (BiomeId candidate : allBiomeIds) {
            if (deadEnd.contains(candidate)) {
                continue; // a member of the conflict itself isn't a meaningful bridge candidate for it
            }
            List<BiomeId> missing = new ArrayList<>();
            int legalCount = 0;
            for (BiomeId member : deadEnd) {
                if (graph.isAllowed(candidate, member)) {
                    legalCount++;
                } else {
                    missing.add(member);
                }
            }
            ranked.add(new CandidateFit(candidate, legalCount, missing));
        }
        ranked.sort(Comparator.comparingInt(CandidateFit::legalCount).reversed());
        return ranked;
    }

    /** {@code minecraft:stony_shore} -> {@code STONY_SHORE} - matches how {@code AdjacencyRule.allow} calls actually read in {@code ModBiomeGraph}, so a proposed fix can be pasted close to as-is. */
    private static String shortConstantName(BiomeId biomeId) {
        return biomeId.path().toUpperCase(java.util.Locale.ROOT);
    }

    private static void writeReport(Path reportFile, String report) {
        try {
            if (reportFile.getParent() != null) {
                Files.createDirectories(reportFile.getParent());
            }
            Files.writeString(reportFile, report, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed writing " + reportFile, e);
        }
    }
}
