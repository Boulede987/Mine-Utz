package com.mineutze.worldgen.regionsolve.mesh;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mineutze.worldgen.regionsolve.WorldgenTrace;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Absorbs a same-identity connected component whose total area falls short
 * of its own biome's declared minimum (see {@code BiomeSizeRange}) into
 * whichever legal neighboring identity borders it most — a genuinely
 * undersized patch shouldn't stand in as a real instance of that biome, the
 * same principle the pixel-level {@code PatchSizeEnforcer} was built for
 * (still live today — {@code MeshGeometryRealization} calls it directly as
 * a post-rasterization safety net, since this class only sees MESH-face
 * area, before displacement/rasterization/repair can shrink a component).
 * This mesh-level check didn't exist before the mesh rewrite — a real,
 * pre-existing gap; user feedback on the seed-42 comparison ("patches that
 * seem illegally small... like only 5 cells") is what surfaced it.
 *
 * <p>Runs once, right after {@link MeshGapFiller} and before {@link
 * EdgeDisplacement} — after fill so every face is already assigned (a
 * meaningful "is this component undersized" check needs the FINAL
 * component boundaries, not a mid-fill snapshot), before displacement so
 * the shape being displaced is already the real, final one (an undersized
 * patch absorbed later would just get displaced for nothing).
 */
public final class MeshPatchSizeEnforcer {

    // Sanity cap on merge passes, same fixed-point-loop-cap role as this pipeline's
    // other repair passes - merging one undersized patch can itself leave a neighbor
    // undersized (rare, but a small patch stealing area could shrink another below its
    // own minimum), so this needs to iterate to a real fixed point, not just run once.
    private static final int MAX_PASSES = 20;

    private MeshPatchSizeEnforcer() {
    }

    public static void enforceMinimumPatchSize(FineMesh mesh, MeshAssignment assignment, BiomeGraph graph, MacroGridConfig grid) {
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            if (!absorbUndersizedComponentsOnce(mesh, assignment, graph, grid)) {
                return;
            }
        }
    }

    private static boolean absorbUndersizedComponentsOnce(FineMesh mesh, MeshAssignment assignment, BiomeGraph graph, MacroGridConfig grid) {
        Map<Integer, BiomeId> faceIdentity = assignment.faceIdentity();
        Set<Integer> fixedFaceIds = assignment.fixedFaceIds();
        boolean absorbedAny = false;

        for (Set<Integer> component : sameIdentityComponents(mesh, faceIdentity, fixedFaceIds)) {
            BiomeId biome = faceIdentity.get(component.iterator().next());
            double area = component.stream().mapToDouble(faceId -> MeshAreas.area(mesh, mesh.face(faceId))).sum();
            double minArea = MeshGapFiller.minAreaFor(biome, grid, graph);
            if (area >= minArea) {
                continue;
            }

            Optional<BiomeId> replacement = bestLegalReplacement(mesh, component, faceIdentity, graph);
            if (replacement.isEmpty()) {
                continue; // no legal neighbor to absorb into - leave it, same "drop, don't force" philosophy as everywhere else here
            }

            for (int faceId : component) {
                faceIdentity.put(faceId, replacement.get());
            }
            WorldgenTrace.log("MESH PATCHSIZE absorb " + component.size() + "-face " + biome + " component (area=" + area + " < min=" + minArea + ") into " + replacement.get());
            absorbedAny = true;
        }

        return absorbedAny;
    }

    private static List<Set<Integer>> sameIdentityComponents(FineMesh mesh, Map<Integer, BiomeId> faceIdentity, Set<Integer> fixedFaceIds) {
        Set<Integer> visited = new LinkedHashSet<>(fixedFaceIds);
        List<Set<Integer>> components = new java.util.ArrayList<>();
        for (int start : faceIdentity.keySet()) {
            if (visited.contains(start)) {
                continue;
            }
            Set<Integer> component = floodFillSameIdentity(mesh, start, faceIdentity, fixedFaceIds);
            visited.addAll(component);
            components.add(component);
        }
        return components;
    }

    private static Set<Integer> floodFillSameIdentity(FineMesh mesh, int start, Map<Integer, BiomeId> faceIdentity, Set<Integer> fixedFaceIds) {
        BiomeId biome = faceIdentity.get(start);
        Set<Integer> visited = new LinkedHashSet<>();
        Deque<Integer> stack = new ArrayDeque<>(List.of(start));
        visited.add(start);
        while (!stack.isEmpty()) {
            int current = stack.pop();
            for (int neighbor : mesh.edgeAdjacentFaces(current)) {
                if (!fixedFaceIds.contains(neighbor) && biome.equals(faceIdentity.get(neighbor)) && visited.add(neighbor)) {
                    stack.push(neighbor);
                }
            }
        }
        return visited;
    }

    /**
     * Among every identity bordering {@code component} (edge-adjacent,
     * from outside it), the one that's legal for the WHOLE component to
     * become — every one of its faces' external edge AND diagonal
     * neighbors must accept it, not just the specific face touching the
     * majority-vote winner — tie-broken by how much of the component's own
     * boundary that identity already touches.
     */
    private static Optional<BiomeId> bestLegalReplacement(FineMesh mesh, Set<Integer> component, Map<Integer, BiomeId> faceIdentity, BiomeGraph graph) {
        Map<BiomeId, Long> borderingCounts = new LinkedHashMap<>();
        for (int faceId : component) {
            for (int neighborFaceId : mesh.edgeAdjacentFaces(faceId)) {
                if (!component.contains(neighborFaceId)) {
                    BiomeId neighborBiome = faceIdentity.get(neighborFaceId);
                    if (neighborBiome != null) {
                        borderingCounts.merge(neighborBiome, 1L, Long::sum);
                    }
                }
            }
        }

        return borderingCounts.keySet().stream()
            .filter(candidate -> isLegalForWholeComponent(mesh, component, candidate, faceIdentity, graph))
            .max(Comparator.<BiomeId>comparingLong(borderingCounts::get).thenComparing(Comparator.naturalOrder()));
    }

    private static boolean isLegalForWholeComponent(FineMesh mesh, Set<Integer> component, BiomeId candidate, Map<Integer, BiomeId> faceIdentity, BiomeGraph graph) {
        for (int faceId : component) {
            for (int neighborFaceId : mesh.edgeAdjacentFaces(faceId)) {
                if (component.contains(neighborFaceId)) {
                    continue;
                }
                BiomeId neighborBiome = faceIdentity.get(neighborFaceId);
                if (neighborBiome != null && !graph.isAllowed(candidate, neighborBiome)) {
                    return false;
                }
            }
            for (int diagonalFaceId : MeshDiagonalPairs.diagonalPartnersOf(mesh, faceId)) {
                if (component.contains(diagonalFaceId)) {
                    continue;
                }
                BiomeId diagonalBiome = faceIdentity.get(diagonalFaceId);
                if (diagonalBiome != null && !graph.isAllowed(candidate, diagonalBiome)) {
                    return false;
                }
            }
        }
        return true;
    }
}
