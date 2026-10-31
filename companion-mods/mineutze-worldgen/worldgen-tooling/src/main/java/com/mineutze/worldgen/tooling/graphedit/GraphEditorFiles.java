package com.mineutze.worldgen.tooling.graphedit;

import java.nio.file.Path;

/**
 * Where the editor's own data lives - relative to the working directory a gradle task
 * runs from ({@code worldgen-core}, see build.gradle's {@code graphEditor} task registration).
 *
 * <p>Real user decision (2026-09-15): now the SAME file production loads from ({@link
 * com.mineutze.worldgen.biomegraph.ModBiomeGraph#OVERWORLD}, via {@link
 * com.mineutze.worldgen.biomegraph.BiomeGraphJson#loadResource} as a bundled classpath
 * resource, addressed there as {@code "/biomegraph/biome-graph.json"} - a literal, not a
 * reference to {@link #DATA_FILE}, since production code must not depend on this tooling
 * package) - moved under {@code src/main/resources} on purpose so the graph editor's own
 * dev-time file writes ARE what ships in the mod jar, one source of truth, no separate
 * sync/copy step to drift out of sync.
 */
final class GraphEditorFiles {

    static final Path DATA_FILE = Path.of("src", "main", "resources", "biomegraph", "biome-graph.json");

    private GraphEditorFiles() {
    }
}
