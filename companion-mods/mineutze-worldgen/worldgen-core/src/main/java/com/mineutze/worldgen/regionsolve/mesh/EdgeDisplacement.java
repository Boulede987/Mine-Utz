package com.mineutze.worldgen.regionsolve.mesh;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeSizeRange;
import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mineutze.worldgen.regionsolve.topology.IndexedTriangle;
import com.mineutze.worldgen.regionsolve.topology.NodeEdge;
import com.mineutze.worldgen.regionsolve.topology.Point2D;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * The actual visual payoff of the whole mesh rewrite: perturbs the shared
 * boundary between two differently-identified patches so it reads as a
 * wavy, organic edge instead of a straight triangle-facet line — this is
 * what turns a forced separator from a "cheating" ruler-straight corridor
 * into something that reads as an intentional river/coastline landform
 * (see doc/fix_dieas.md and the mesh rewrite's own design plan).
 *
 * <p>Only INTERIOR edges between two different identities get displaced —
 * the outer mesh perimeter (region-boundary ring) and same-identity
 * internal edges are left alone, since region-to-region seam smoothing is
 * a separate, deliberately deferred concern (doc/KNOWN_ISSUES.md #1), not
 * something this pass tries to fix.
 *
 * <p>A vertex only moves if exactly 2 boundary edges meet there and both
 * separate the SAME unordered pair of identities — a "through" point on an
 * otherwise-straight run. Anywhere else (a dead end, or 3+ boundary edges
 * meeting at a multi-patch corner) stays pinned, which gives "corners stay
 * fixed" for free without needing an explicit chain-tracing data
 * structure: a junction is just a vertex this rule never selects. Every
 * vertex belonging to a {@code fixedFaceIds} face also stays pinned —
 * moving the literal position tied to an already-solved neighbor's
 * boundary context is out of scope here (same KNOWN_ISSUES #1 boundary).
 *
 * <p>Displacement is perpendicular to the local through-direction (the
 * vector between the vertex's two boundary-edge neighbors), amplitude
 * {@link #AMPLITUDE_FRACTION} of the local edge length, via this
 * codebase's standard per-{@code (worldSeed, edge, vertex)} deterministic
 * hash idiom — never a persisted {@link Random} stream. Returns only the
 * vertices that actually moved; every other vertex keeps
 * {@link FineMesh#position}'s original value, which is exactly what lets
 * a "far from any boundary, unaffected by displacement" cell take
 * {@code MeshRasterizer}'s cheap fast path.
 */
public final class EdgeDisplacement {

    // 10-15% of local edge length, per doc/fix_dieas.md's own suggestion - placeholder,
    // tune from how organic the result actually looks, like every other shape constant
    // in this codebase.
    private static final double AMPLITUDE_FRACTION = 0.12;
    private static final double AMPLITUDE_DECAY = 0.5;
    private static final int MAX_AREA_CORRECTION_ATTEMPTS = 3;
    private static final double MIN_TANGENT_LENGTH = 1e-6;
    private static final long DISPLACEMENT_SALT = 0xD15C_ACE1L;

    private EdgeDisplacement() {
    }

    private record ThroughVertex(int otherA, int otherB) {
    }

    private record IdentityPair(BiomeId a, BiomeId b) {
        static IdentityPair of(BiomeId a, BiomeId b) {
            return a.compareTo(b) <= 0 ? new IdentityPair(a, b) : new IdentityPair(b, a);
        }
    }

    public static Map<Integer, Point2D> displace(FineMesh mesh, MeshAssignment assignment, BiomeGraph graph, MacroGridConfig grid, long worldSeed) {
        Map<Integer, BiomeId> faceIdentity = assignment.faceIdentity();
        Set<Integer> pinnedVertices = verticesOfFaces(mesh, assignment.fixedFaceIds());
        Map<Integer, List<MeshEdge>> boundaryEdgesByVertex = boundaryEdgesByVertex(mesh, faceIdentity);
        Map<Integer, ThroughVertex> throughVertices = throughVertices(boundaryEdgesByVertex, faceIdentity, pinnedVertices);

        Map<Integer, Point2D> displaced = new LinkedHashMap<>();
        for (Map.Entry<Integer, ThroughVertex> entry : throughVertices.entrySet()) {
            displaced.put(entry.getKey(), displacedPosition(mesh, entry.getKey(), entry.getValue(), AMPLITUDE_FRACTION, worldSeed, 0));
        }

        for (Set<Integer> component : patchComponents(mesh, faceIdentity, assignment.fixedFaceIds())) {
            BiomeId biome = faceIdentity.get(component.iterator().next());
            correctAreaIfNeeded(mesh, component, biome, graph, grid, throughVertices, displaced, worldSeed);
        }

        return displaced;
    }

    private static Set<Integer> verticesOfFaces(FineMesh mesh, Set<Integer> faceIds) {
        Set<Integer> vertices = new LinkedHashSet<>();
        for (int faceId : faceIds) {
            IndexedTriangle face = mesh.face(faceId);
            vertices.add(face.a());
            vertices.add(face.b());
            vertices.add(face.c());
        }
        return vertices;
    }

    private static boolean isBoundaryEdge(MeshEdge edge, Map<Integer, BiomeId> faceIdentity) {
        if (!edge.isInterior()) {
            return false;
        }
        BiomeId first = faceIdentity.get(edge.faceIds().get(0));
        BiomeId second = faceIdentity.get(edge.faceIds().get(1));
        return first != null && second != null && !first.equals(second);
    }

    private static Map<Integer, List<MeshEdge>> boundaryEdgesByVertex(FineMesh mesh, Map<Integer, BiomeId> faceIdentity) {
        Map<Integer, List<MeshEdge>> byVertex = new LinkedHashMap<>();
        for (MeshEdge edge : mesh.edges()) {
            if (!isBoundaryEdge(edge, faceIdentity)) {
                continue;
            }
            byVertex.computeIfAbsent(edge.vertices().a(), key -> new ArrayList<>()).add(edge);
            byVertex.computeIfAbsent(edge.vertices().b(), key -> new ArrayList<>()).add(edge);
        }
        return byVertex;
    }

    private static Map<Integer, ThroughVertex> throughVertices(Map<Integer, List<MeshEdge>> boundaryEdgesByVertex, Map<Integer, BiomeId> faceIdentity, Set<Integer> pinnedVertices) {
        Map<Integer, ThroughVertex> through = new LinkedHashMap<>();
        boundaryEdgesByVertex.forEach((vertexId, incident) -> {
            if (pinnedVertices.contains(vertexId) || incident.size() != 2) {
                return;
            }
            MeshEdge edge1 = incident.get(0);
            MeshEdge edge2 = incident.get(1);
            IdentityPair pair1 = identityPairAcross(edge1, faceIdentity);
            IdentityPair pair2 = identityPairAcross(edge2, faceIdentity);
            if (!pair1.equals(pair2)) {
                return; // a vertex where the separator itself changes which two identities meet - a real corner, stays pinned
            }
            NodeEdge vertices1 = edge1.vertices();
            NodeEdge vertices2 = edge2.vertices();
            int otherA = vertices1.a() == vertexId ? vertices1.b() : vertices1.a();
            int otherB = vertices2.a() == vertexId ? vertices2.b() : vertices2.a();
            through.put(vertexId, new ThroughVertex(otherA, otherB));
        });
        return through;
    }

    private static IdentityPair identityPairAcross(MeshEdge edge, Map<Integer, BiomeId> faceIdentity) {
        BiomeId first = faceIdentity.get(edge.faceIds().get(0));
        BiomeId second = faceIdentity.get(edge.faceIds().get(1));
        return IdentityPair.of(first, second);
    }

    private static double displacedArea(FineMesh mesh, Set<Integer> componentFaceIds, Map<Integer, Point2D> displaced) {
        double total = 0.0;
        for (int faceId : componentFaceIds) {
            IndexedTriangle face = mesh.face(faceId);
            Point2D a = displaced.getOrDefault(face.a(), mesh.position(face.a()));
            Point2D b = displaced.getOrDefault(face.b(), mesh.position(face.b()));
            Point2D c = displaced.getOrDefault(face.c(), mesh.position(face.c()));
            total += MeshAreas.area(a, b, c);
        }
        return total;
    }

    private static void correctAreaIfNeeded(
        FineMesh mesh, Set<Integer> component, BiomeId biome, BiomeGraph graph, MacroGridConfig grid,
        Map<Integer, ThroughVertex> throughVertices, Map<Integer, Point2D> displaced, long worldSeed
    ) {
        BiomeSizeRange sizeRange = graph.node(biome).sizeRange();
        double minArea = MeshAreas.areaForWidthBlocks(sizeRange.minWidthBlocks(), grid);
        double maxArea = MeshAreas.areaForWidthBlocks(sizeRange.maxWidthBlocks(), grid);

        Set<Integer> componentVertices = new HashSet<>();
        for (int faceId : component) {
            IndexedTriangle face = mesh.face(faceId);
            componentVertices.add(face.a());
            componentVertices.add(face.b());
            componentVertices.add(face.c());
        }

        double amplitude = AMPLITUDE_FRACTION;
        for (int attempt = 1; attempt <= MAX_AREA_CORRECTION_ATTEMPTS; attempt++) {
            double area = displacedArea(mesh, component, displaced);
            if (area >= minArea && area <= maxArea) {
                return;
            }
            amplitude *= AMPLITUDE_DECAY;
            for (int vertexId : componentVertices) {
                ThroughVertex through = throughVertices.get(vertexId);
                if (through != null) {
                    displaced.put(vertexId, displacedPosition(mesh, vertexId, through, amplitude, worldSeed, attempt));
                }
            }
        }
        // Cheapest-fix ceiling reached - accept whatever the last roll produced, same
        // "drop, don't force" philosophy as everywhere else in this pipeline: this is
        // purely cosmetic, not a hard invariant.
    }

    private static List<Set<Integer>> patchComponents(FineMesh mesh, Map<Integer, BiomeId> faceIdentity, Set<Integer> fixedFaceIds) {
        Set<Integer> visited = new HashSet<>(fixedFaceIds);
        List<Set<Integer>> components = new ArrayList<>();
        for (int faceId : faceIdentity.keySet()) {
            if (visited.contains(faceId)) {
                continue;
            }
            Set<Integer> component = floodFillSameIdentity(mesh, faceId, faceIdentity, fixedFaceIds);
            visited.addAll(component);
            components.add(component);
        }
        return components;
    }

    private static Set<Integer> floodFillSameIdentity(FineMesh mesh, int start, Map<Integer, BiomeId> faceIdentity, Set<Integer> fixedFaceIds) {
        BiomeId biome = faceIdentity.get(start);
        Set<Integer> visited = new LinkedHashSet<>();
        java.util.Deque<Integer> stack = new java.util.ArrayDeque<>(List.of(start));
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

    private static Point2D displacedPosition(FineMesh mesh, int vertexId, ThroughVertex through, double amplitudeFraction, long worldSeed, int attemptSalt) {
        Point2D self = mesh.position(vertexId);
        Point2D a = mesh.position(through.otherA());
        Point2D b = mesh.position(through.otherB());

        double tangentX = b.x() - a.x();
        double tangentZ = b.z() - a.z();
        double tangentLength = Math.sqrt(tangentX * tangentX + tangentZ * tangentZ);
        if (tangentLength < MIN_TANGENT_LENGTH) {
            return self;
        }

        double perpendicularX = -tangentZ / tangentLength;
        double perpendicularZ = tangentX / tangentLength;
        double localEdgeLength = (distance(self, a) + distance(self, b)) / 2.0;
        double maxDisplacement = amplitudeFraction * localEdgeLength;
        double offset = perpendicularOffset(worldSeed, through.otherA(), through.otherB(), vertexId, attemptSalt, maxDisplacement);

        return new Point2D(self.x() + perpendicularX * offset, self.z() + perpendicularZ * offset);
    }

    private static double distance(Point2D a, Point2D b) {
        double dx = a.x() - b.x();
        double dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Deterministic per (worldSeed, edge endpoints, vertex, area-correction attempt) - a re-roll for area correction changes attemptSalt, so it's a genuinely different draw, not the same one clamped. */
    private static double perpendicularOffset(long worldSeed, int otherA, int otherB, int vertexId, int attemptSalt, double maxDisplacement) {
        long hash = worldSeed ^ DISPLACEMENT_SALT;
        hash = hash * 31 + Math.min(otherA, otherB);
        hash = hash * 31 + Math.max(otherA, otherB);
        hash = hash * 31 + vertexId;
        hash = hash * 31 + attemptSalt;
        return (new Random(hash).nextDouble() - 0.5) * 2.0 * maxDisplacement;
    }
}
