package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.MinimalJson;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Coarse Solve check ranking for Verify, keyed by graph fingerprint: read on an edited graph, only laws the graph still
 * states are kept. Written atomically: a Verify never reads half a file.
 */
final class TightnessFile {

    private static final String REQUIRED_BORDER_KIND = "requiredBorder";
    /** Required border key (resolved member biomes included), so a later edit to it or to a group it names can be seen. */
    private static final String REQUIREMENT_KEY = "requirement";

    private TightnessFile() {
    }

    static void write(Path file, TightnessRanking ranking, BiomeGraphData catalog) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("fingerprint", WorldBiomePlan.fingerprintOf(catalog));
        json.put("failedAttempts", ranking.failedAttempts());
        json.put("obstacles", ranking.entries().stream().map(TightnessFile::toJson).toList());
        try {
            Path folder = file.toAbsolutePath().getParent();
            Files.createDirectories(folder);
            Path temporary = Files.createTempFile(folder, "tightness", ".tmp");
            Files.writeString(temporary, MinimalJson.writeObject(json));
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            throw new UncheckedIOException("TightnessFile: cannot write " + file.toAbsolutePath(), failure);
        }
    }

    static TightnessRanking read(Path file, BiomeGraphData catalog) {
        return readSaved(file, catalog).ranking();
    }

    /** File read once: the fingerprint covers exactly the bytes the ranking was parsed from. */
    static SavedRanking readSaved(Path file, BiomeGraphData catalog) {
        String text = readText(file);
        Map<String, Object> json = parse(text, file);
        String graphFingerprint = WorldBiomePlan.fingerprintOf(catalog);
        String fingerprint = RankingFingerprint.of(text, graphFingerprint);
        List<?> stored = listOf(json.get("obstacles"));
        int failedAttempts = intOf(json.get("failedAttempts"));
        RawRuleTable table = RawRuleTable.from(catalog);
        if (graphFingerprint.equals(stringOf(json.get("fingerprint")))) {
            List<RankedObstacle> entries = stored.stream().map(entry -> fromJson(asMap(entry), table)).toList();
            return new SavedRanking(new TightnessRanking(entries, failedAttempts), fingerprint, true, 0);
        }
        List<RankedObstacle> stillStated = stillStatedOn(catalog, table, stored, file);
        return new SavedRanking(new TightnessRanking(stillStated, failedAttempts), fingerprint, false, stored.size() - stillStated.size());
    }

    /** Same settings as the editor's Solve check, which wrote the ranking. */
    private static List<RankedObstacle> stillStatedOn(BiomeGraphData catalog, RawRuleTable table, List<?> stored, Path file) {
        StatedLaws stated = StatedLaws.of(catalog, PlanSettings.DEFAULT);
        List<RankedObstacle> kept = new ArrayList<>();
        for (Object entry : stored) {
            onEditedGraph(asMap(entry), table, file).filter(ranked -> stated.states(ranked.obstacle())).ifPresent(kept::add);
        }
        return kept;
    }

    /** Empty: a required border the edit changed or removed, seen by its stored text. */
    private static Optional<RankedObstacle> onEditedGraph(Map<String, Object> json, RawRuleTable table, Path file) {
        if (!REQUIRED_BORDER_KIND.equals(stringOf(json.get("kind")))) {
            return Optional.of(fromJson(json, table));
        }
        if (!(json.get(REQUIREMENT_KEY) instanceof String storedText)) {
            throw new RankingUnavailable("The saved ranking (" + file.toAbsolutePath() + ") predates stored required-border texts and the graph changed since: run the Solve check again");
        }
        BiomeId biome = biomeOf(json.get("biome"));
        return table.requirementOf(biome).filter(current -> requirementKey(current).equals(storedText))
            .map(current -> new RankedObstacle(new LawObstacle(new RequiredBorderLaw(biome, current)), intOf(json.get("attemptsBroken"))));
    }

    private static String readText(Path file) {
        try {
            return Files.readString(file);
        } catch (NoSuchFileException missing) {
            throw new RankingUnavailable("No saved ranking at " + file.toAbsolutePath() + ": run the Solve check first (it saves one whenever an attempt failed)");
        } catch (IOException failure) {
            throw new UncheckedIOException("TightnessFile: cannot read " + file.toAbsolutePath(), failure);
        }
    }

    private static Map<String, Object> parse(String text, Path file) {
        try {
            return asMap(MinimalJson.parse(text));
        } catch (IllegalArgumentException | IndexOutOfBoundsException corrupt) {
            throw new IllegalStateException("TightnessFile: " + file.toAbsolutePath() + " is not valid JSON: " + corrupt.getMessage(), corrupt);
        }
    }

    private static Map<String, Object> toJson(RankedObstacle ranked) {
        Map<String, Object> json = new LinkedHashMap<>();
        switch (ranked.obstacle()) {
            case LawObstacle law -> putLaw(json, law.law());
            case MissingBiome missing -> putKind(json, "missingBiome", missing.biome());
            case ShapeLimit shape -> putKind(json, "shapeLimit", shape.biome());
        }
        json.put("attemptsBroken", ranked.attemptsBroken());
        return json;
    }

    private static void putLaw(Map<String, Object> json, GraphLaw law) {
        switch (law) {
            case IllegalPairLaw pair -> {
                json.put("kind", "illegalPair");
                json.put("first", pair.first().toString());
                json.put("second", pair.second().toString());
            }
            case SizeRangeLaw size -> {
                putKind(json, "sizeRange", size.biome());
                json.put("minCells", size.minCells());
                json.put("maxCells", size.maxCells());
            }
            case RequiredBorderLaw border -> {
                putKind(json, REQUIRED_BORDER_KIND, border.biome());
                json.put(REQUIREMENT_KEY, requirementKey(border.requirement()));
            }
        }
    }

    private static void putKind(Map<String, Object> json, String kind, BiomeId biome) {
        json.put("kind", kind);
        json.put("biome", biome.toString());
    }

    private static RankedObstacle fromJson(Map<String, Object> json, RawRuleTable table) {
        Obstacle obstacle = switch (stringOf(json.get("kind"))) {
            case "illegalPair" -> new LawObstacle(new IllegalPairLaw(biomeOf(json.get("first")), biomeOf(json.get("second"))));
            case "sizeRange" -> new LawObstacle(new SizeRangeLaw(biomeOf(json.get("biome")), intOf(json.get("minCells")), intOf(json.get("maxCells"))));
            case REQUIRED_BORDER_KIND -> requiredBorderOf(biomeOf(json.get("biome")), table);
            case "missingBiome" -> new MissingBiome(biomeOf(json.get("biome")));
            case "shapeLimit" -> new ShapeLimit(biomeOf(json.get("biome")));
            default -> throw new IllegalStateException("TightnessFile: unknown obstacle kind " + json.get("kind"));
        };
        return new RankedObstacle(obstacle, intOf(json.get("attemptsBroken")));
    }

    /** Own canonical form, not the display print: a print tweak must not read as an edit; members resolved, so group edits show. */
    private static String requirementKey(ResolvedBorderRequirement requirement) {
        return switch (requirement) {
            case ResolvedBorderRequirement.Share share -> "share(" + share.biomes().stream().map(BiomeId::toString).sorted().toList() + ">=" + share.minEdges() + ")";
            case ResolvedBorderRequirement.AllOf all -> "all(" + all.parts().stream().map(TightnessFile::requirementKey).toList() + ")";
            case ResolvedBorderRequirement.AnyOf any -> "any(" + any.parts().stream().map(TightnessFile::requirementKey).toList() + ")";
        };
    }

    private static Obstacle requiredBorderOf(BiomeId biome, RawRuleTable table) {
        ResolvedBorderRequirement requirement = table.requirementOf(biome)
            .orElseThrow(() -> new IllegalStateException("TightnessFile: ranking names a required border of " + biome + ", which the graph no longer has"));
        return new LawObstacle(new RequiredBorderLaw(biome, requirement));
    }

    private static BiomeId biomeOf(Object value) {
        String key = stringOf(value);
        int separator = key.indexOf(':');
        if (separator <= 0 || separator == key.length() - 1) {
            throw new IllegalStateException("TightnessFile: not a namespace:path biome id: " + key);
        }
        return new BiomeId(key.substring(0, separator), key.substring(separator + 1));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalStateException("TightnessFile: expected a JSON object, got " + value);
        }
        return (Map<String, Object>) map;
    }

    private static List<?> listOf(Object value) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalStateException("TightnessFile: expected a JSON array, got " + value);
        }
        return list;
    }

    private static String stringOf(Object value) {
        if (!(value instanceof String text)) {
            throw new IllegalStateException("TightnessFile: expected a string, got " + value);
        }
        return text;
    }

    /** Counts only: finite, whole, 0..Integer.MAX_VALUE (MinimalJson parses 1e999 to Infinity, and intValue would saturate). */
    private static int intOf(Object value) {
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("TightnessFile: expected a whole number, got " + value);
        }
        double parsed = number.doubleValue();
        if (!Double.isFinite(parsed) || parsed != Math.rint(parsed) || parsed < 0 || parsed > Integer.MAX_VALUE) {
            throw new IllegalStateException("TightnessFile: expected a count from 0 to " + Integer.MAX_VALUE + ", got " + value);
        }
        return (int) parsed;
    }
}
