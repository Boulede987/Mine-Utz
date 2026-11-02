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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Coarse Solve check ranking for Verify. Fingerprint-keyed: another graph's laws may be gone. Written atomically: a Verify never reads half a file. */
final class TightnessFile {

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
        Map<String, Object> json = parse(file);
        String fingerprint = WorldBiomePlan.fingerprintOf(catalog);
        if (!fingerprint.equals(stringOf(json.get("fingerprint")))) {
            throw new RankingUnavailable("The saved ranking (" + file.toAbsolutePath() + ") is for a different version of the graph: run the Solve check again on the current graph");
        }
        RawRuleTable table = RawRuleTable.from(catalog);
        List<RankedObstacle> entries = listOf(json.get("obstacles")).stream().map(entry -> fromJson(asMap(entry), table)).toList();
        return new TightnessRanking(entries, intOf(json.get("failedAttempts")));
    }

    private static Map<String, Object> parse(Path file) {
        String text;
        try {
            text = Files.readString(file);
        } catch (NoSuchFileException missing) {
            throw new RankingUnavailable("No saved ranking at " + file.toAbsolutePath() + ": run the Solve check first (it saves one when it ends UNPROVEN)");
        } catch (IOException failure) {
            throw new UncheckedIOException("TightnessFile: cannot read " + file.toAbsolutePath(), failure);
        }
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
            case RequiredBorderLaw border -> putKind(json, "requiredBorder", border.biome());
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
            case "requiredBorder" -> requiredBorderOf(biomeOf(json.get("biome")), table);
            case "missingBiome" -> new MissingBiome(biomeOf(json.get("biome")));
            case "shapeLimit" -> new ShapeLimit(biomeOf(json.get("biome")));
            default -> throw new IllegalStateException("TightnessFile: unknown obstacle kind " + json.get("kind"));
        };
        return new RankedObstacle(obstacle, intOf(json.get("attemptsBroken")));
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
