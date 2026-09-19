package com.mineutze.worldgen.export;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.ModBiomeGraph;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Geometric follow-up to {@link UniversalSelfAdjacencyExperiment}'s identity-only result
 * (3118 -> 2573 minimal unsolvable partial neighborhoods, but the worst size-2 pure-pair
 * conflicts unchanged at 288 either way - self-adjacency can't rescue two biomes with
 * nothing in common). That result never touched geometry at all. This runs the EXACT SAME
 * real-solver check {@link GeometricFeasibilityProof} runs against the real graph, but
 * against the hypothetical universal-self-adjacency graph instead - same production
 * TriangleIdentitySizeAssignment.solve call, same exact width-extreme reduction, no
 * duplicated logic (see {@link GeometricFeasibilityProof#runProof}).
 *
 * <p>Reusing the real solver here matters specifically because self-adjacency changes
 * more than the abstract candidate list - a candidate that equals one of the ring's own
 * biomes now forms a PAIRED felt region with it ({@code SelfAdjacentPairing}), whose two
 * members' widths merely need to SUM into the biome's declared range, asymmetrically
 * ({@code FaceWidthPicker#splitWidthForPair}) - a real geometric consequence an abstract
 * identity-only check can't see at all. The production solve call already handles this
 * internally; this class doesn't need to model pairing by hand.
 */
public final class UniversalSelfAdjacencyGeometricExperiment {

    private static final Path REPORT_PATH = Path.of("doc", "geometric-feasibility-proof-universal-self-adjacency-report.txt");

    private UniversalSelfAdjacencyGeometricExperiment() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        BiomeGraph universalSelfAdjacency = UniversalSelfAdjacencyExperiment.withUniversalSelfAdjacency(ModBiomeGraph.OVERWORLD);
        GeometricFeasibilityProof.runProof(universalSelfAdjacency, REPORT_PATH, "UNIVERSAL SELF-ADJACENCY (hypothetical, every biome selfAdjacencyLimit=1)");
    }
}
