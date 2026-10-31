package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Laws that cannot all hold together; dropping any one of them removes this contradiction. */
record LawConflict(List<GraphLaw> laws) {

    LawConflict {
        Objects.requireNonNull(laws, "laws");
        if (laws.isEmpty()) {
            throw new IllegalArgumentException("LawConflict: a conflict needs at least one law");
        }
        laws = List.copyOf(laws);
    }

    private static final int SMALLEST_GROUPED_PAIR_COUNT = 3;

    /** One line per law; >= 3 pair laws sharing one biome folded into one line so a hub biome does not flood the report. */
    List<String> describeLines() {
        List<IllegalPairLaw> pairs = laws.stream().filter(law -> law instanceof IllegalPairLaw).map(law -> (IllegalPairLaw) law).toList();
        Optional<BiomeId> hub = hubOf(pairs);
        List<String> lines = new ArrayList<>();
        hub.ifPresent(shared -> lines.add(shared + " is not a legal neighbour of " + partnersOf(shared, pairs).size() + " biomes: " + String.join(", ", partnersOf(shared, pairs))));
        laws.stream().filter(law -> !isFolded(law, hub)).forEach(law -> lines.add(law.describe()));
        return lines;
    }

    /** Biome in the most pair laws, if >= the folding threshold; ties to the smallest name, for stable output. */
    private static Optional<BiomeId> hubOf(List<IllegalPairLaw> pairs) {
        Map<BiomeId, Long> pairCountByBiome = pairs.stream().flatMap(pair -> Stream.of(pair.first(), pair.second()))
            .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        Comparator<Map.Entry<BiomeId, Long>> mostPairsThenSmallestName = Map.Entry.<BiomeId, Long>comparingByValue()
            .thenComparing(entry -> entry.getKey().toString(), Comparator.reverseOrder());
        return pairCountByBiome.entrySet().stream().max(mostPairsThenSmallestName).filter(entry -> entry.getValue() >= SMALLEST_GROUPED_PAIR_COUNT).map(Map.Entry::getKey);
    }

    private static List<String> partnersOf(BiomeId hub, List<IllegalPairLaw> pairs) {
        return pairs.stream().filter(pair -> touches(pair, hub)).map(pair -> (pair.first().equals(hub) ? pair.second() : pair.first()).toString()).sorted().toList();
    }

    private static boolean isFolded(GraphLaw law, Optional<BiomeId> hub) {
        return law instanceof IllegalPairLaw pair && hub.filter(shared -> touches(pair, shared)).isPresent();
    }

    private static boolean touches(IllegalPairLaw pair, BiomeId biome) {
        return pair.first().equals(biome) || pair.second().equals(biome);
    }
}
