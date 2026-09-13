package com.mineutze.worldgen.regionsolve.pixelgrid;

import com.mineutze.worldgen.regionsolve.MacroCellPos;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Export-only today, deliberately kept - its only real caller is {@code
 * TriangleFieldRegionExportCli}; {@code WeightedVertexPlacement}/{@code
 * TriangleFieldGeometryRealization} only mention it in comments, never actually call it.
 *
 * <p>Ported directly from this repo's own {@code validate_biome_export.py} (previously
 * run by hand against an exported {@code regions.json} after the fact) - same four
 * checks, same defect definitions, now run in-process against the live {@code
 * Map<MacroCellPos,BiomeId>} a region solve just produced, so a problem shows up
 * immediately instead of needing a separate manual export-then-script round trip. Kept
 * in sync by hand with that script, same as that script was kept in sync by hand with
 * {@code PixelFragmentRepair.SMALL_FRAGMENT_MAX_SIZE}.
 *
 * <p>{@link EdgeDisplacementRasterizer} already GUARANTEES zero illegal orthogonal OR
 * diagonal adjacencies in its own output today (CSP-time legality via the hex mesh's own
 * vertex-degree-3 completeness, plus the global vertex-displacement clamp - see {@link
 * com.mineutze.worldgen.regionsolve.topology.WeightedVertexPlacement}'s doc; {@code
 * SeamGrowthRasterizer} carried this same guarantee in an earlier, now-deleted
 * pipeline). So {@link #findIllegalAdjacencies} and {@link #findDiagonalIllegalAdjacencies}
 * exist here as a regression guard / second opinion, not because they're expected to
 * ever fire - a nonempty result means that guarantee has broken, not that this
 * validator found a "normal" defect. Small fragments and one-cell pinch points are NOT
 * guaranteed by anything in the pipeline - real, still-open shape/size concerns this
 * validator is the only thing currently measuring.
 */
public final class RegionIntegrityValidator {

    /**
     * Matches {@code PixelFragmentRepair.SMALL_FRAGMENT_MAX_SIZE} - a same-biome
     * component this size or smaller is discretization debris (speck/sliver), not a
     * legitimate small patch. Sync by hand if that constant ever changes.
     */
    public static final int SMALL_FRAGMENT_MAX_SIZE = 4;

    // Not a crash risk (EdgeDisplacementRasterizer already guarantees legality
    // separately, see this class's doc above) - but per direct user instruction, a
    // messy/speckled world matters just as much as a legal one, so this must never read
    // as a quiet, easy-to-miss line in a wall of output. Shown ONLY when the section it
    // precedes is nonempty.
    private static final String SHOULD_NOT_HAPPEN_BANNER =
        "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n"
            + "!!! THIS SHOULD NOT HAPPEN - COSMETIC WORLDGEN DEFECT DETECTED, FIX IT !!!\n"
            + "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n";

    public record IllegalAdjacency(MacroCellPos cellA, BiomeId biomeA, MacroCellPos cellB, BiomeId biomeB) {
    }

    public record SmallFragment(BiomeId biome, Set<MacroCellPos> cells) {
    }

    public record PinchPoint(MacroCellPos cell, BiomeId biome, String axis, MacroCellPos neighborA, BiomeId biomeA, MacroCellPos neighborB, BiomeId biomeB) {
    }

    public record Report(
        List<IllegalAdjacency> illegalAdjacencies,
        List<IllegalAdjacency> diagonalIllegalAdjacencies,
        List<SmallFragment> smallFragments,
        List<PinchPoint> pinchPoints
    ) {
        public boolean isClean() {
            return illegalAdjacencies.isEmpty() && diagonalIllegalAdjacencies.isEmpty()
                && smallFragments.isEmpty() && pinchPoints.isEmpty();
        }

        public String describe() {
            StringBuilder text = new StringBuilder();
            appendIllegalAdjacenciesSection(text);
            appendDiagonalIllegalAdjacenciesSection(text);
            appendSmallFragmentsSection(text);
            appendPinchPointsSection(text);
            text.append(isClean() ? "PASS" : "FAIL");
            return text.toString();
        }

        private void appendIllegalAdjacenciesSection(StringBuilder text) {
            text.append("=== Illegal adjacencies: ").append(illegalAdjacencies.size()).append(" ===\n");
            text.append("Why: no AdjacencyRule exists between these two biomes (default-deny).\n");
            for (IllegalAdjacency violation : illegalAdjacencies) {
                text.append("  ").append(violation.cellA()).append(' ').append(violation.biomeA())
                    .append("  <->  ").append(violation.cellB()).append(' ').append(violation.biomeB()).append('\n');
            }
        }

        private void appendDiagonalIllegalAdjacenciesSection(StringBuilder text) {
            text.append("=== Diagonal illegal adjacencies: ").append(diagonalIllegalAdjacencies.size()).append(" ===\n");
            text.append("Why: no AdjacencyRule exists between these two biomes, and they touch only at a corner.\n");
            for (IllegalAdjacency violation : diagonalIllegalAdjacencies) {
                text.append("  ").append(violation.cellA()).append(' ').append(violation.biomeA())
                    .append("  <->  ").append(violation.cellB()).append(' ').append(violation.biomeB()).append(" [corner touch]\n");
            }
        }

        private void appendSmallFragmentsSection(StringBuilder text) {
            if (!smallFragments.isEmpty()) {
                text.append(SHOULD_NOT_HAPPEN_BANNER);
            }
            text.append("=== Small fragments (<= ").append(SMALL_FRAGMENT_MAX_SIZE).append(" cells): ").append(smallFragments.size()).append(" ===\n");
            text.append("Why: a same-biome component this size is discretization debris (speck/sliver).\n");
            text.append("Cosmetic, not a crash risk - but a clean-looking world matters just as much as a\n");
            text.append("legal one, so this is NOT something to shrug off as fine.\n");
            for (SmallFragment fragment : smallFragments) {
                text.append("  ").append(fragment.biome()).append("  size=").append(fragment.cells().size())
                    .append("  cells: ").append(fragment.cells()).append('\n');
            }
        }

        private void appendPinchPointsSection(StringBuilder text) {
            if (!pinchPoints.isEmpty()) {
                text.append(SHOULD_NOT_HAPPEN_BANNER);
            }
            text.append("=== One-cell-wide pinch points: ").append(pinchPoints.size()).append(" ===\n");
            text.append("Why: both neighbors on one axis are a different biome (or missing) while the\n");
            text.append("cell still connects to same-biome neighbors on the other axis (sliver/tendril shape).\n");
            text.append("Cosmetic, not a crash risk - but a clean-looking world matters just as much as a\n");
            text.append("legal one, so this is NOT something to shrug off as fine.\n");
            for (PinchPoint pinch : pinchPoints) {
                text.append("  ").append(pinch.cell()).append(' ').append(pinch.biome())
                    .append("  pinched along ").append(pinch.axis())
                    .append(": ").append(pinch.neighborA()).append(' ').append(pinch.biomeA())
                    .append("  /  ").append(pinch.neighborB()).append(' ').append(pinch.biomeB()).append('\n');
            }
        }
    }

    private RegionIntegrityValidator() {
    }

    public static Report validate(Map<MacroCellPos, BiomeId> assignment, BiomeGraph graph) {
        List<IllegalAdjacency> illegal = findIllegalAdjacencies(assignment, graph);
        List<IllegalAdjacency> diagonalIllegal = findDiagonalIllegalAdjacencies(assignment, graph);
        // Moore (8-way), not orthogonal-only: the growth algorithm treats diagonal
        // adjacency as real connectivity everywhere (legality checks, cohesion, the
        // head-start reservation pass), so measuring "isolated" via orthogonal-only
        // adjacency was splitting a real diagonally-touching pair into two false
        // single-cell fragments - confirmed against a real production export (see this
        // class's own git history for that measurement).
        List<Set<MacroCellPos>> components = ConnectedComponents.find(assignment, assignment.keySet(), MacroCellPos::mooreNeighbors);
        List<SmallFragment> smallFragments = findSmallFragments(assignment, components);

        Set<MacroCellPos> fragmentCells = new LinkedHashSet<>();
        for (SmallFragment fragment : smallFragments) {
            fragmentCells.addAll(fragment.cells());
        }
        List<PinchPoint> pinchPoints = findPinchPoints(assignment, fragmentCells);

        return new Report(illegal, diagonalIllegal, smallFragments, pinchPoints);
    }

    /** Two orthogonally-adjacent cells with no legal path between their biomes - each undirected pair reported once (only +x and +z direction checked from each cell). */
    private static List<IllegalAdjacency> findIllegalAdjacencies(Map<MacroCellPos, BiomeId> assignment, BiomeGraph graph) {
        List<IllegalAdjacency> violations = new ArrayList<>();
        for (Map.Entry<MacroCellPos, BiomeId> entry : assignment.entrySet()) {
            MacroCellPos cell = entry.getKey();
            BiomeId biome = entry.getValue();
            for (MacroCellPos neighborPos : List.of(new MacroCellPos(cell.x() + 1, cell.z()), new MacroCellPos(cell.x(), cell.z() + 1))) {
                BiomeId neighborBiome = assignment.get(neighborPos);
                // Same biome touching itself is always fine - it's the same patch's own interior, never a
                // violation regardless of graph.isAllowed(biome, biome), which is no longer trivially true
                // now that self-adjacency isn't a free pass (see BiomeGraph.isAllowed's own doc).
                if (neighborBiome != null && !biome.equals(neighborBiome) && !graph.isAllowed(biome, neighborBiome)) {
                    violations.add(new IllegalAdjacency(cell, biome, neighborPos, neighborBiome));
                }
            }
        }
        return violations;
    }

    /** Two corner-touching (not orthogonal) cells with no legal path between them, where the two cells at the shared corner ("elbow") are each a different biome from both - not caught by {@link #findIllegalAdjacencies}, nothing in the generator pipeline checks diagonals directly. */
    private static List<IllegalAdjacency> findDiagonalIllegalAdjacencies(Map<MacroCellPos, BiomeId> assignment, BiomeGraph graph) {
        List<IllegalAdjacency> violations = new ArrayList<>();
        int[][] diagonalOffsets = {{1, 1}, {1, -1}}; // each undirected diagonal pair checked once
        for (Map.Entry<MacroCellPos, BiomeId> entry : assignment.entrySet()) {
            MacroCellPos cell = entry.getKey();
            BiomeId biome = entry.getValue();
            for (int[] offset : diagonalOffsets) {
                diagonalViolationAt(cell, biome, offset, assignment, graph).ifPresent(violations::add);
            }
        }
        return violations;
    }

    /** One diagonal offset's own check - the inner loop body of {@link #findDiagonalIllegalAdjacencies}. */
    private static Optional<IllegalAdjacency> diagonalViolationAt(
        MacroCellPos cell, BiomeId biome, int[] offset, Map<MacroCellPos, BiomeId> assignment, BiomeGraph graph
    ) {
        MacroCellPos diagonalPos = new MacroCellPos(cell.x() + offset[0], cell.z() + offset[1]);
        BiomeId diagonalBiome = assignment.get(diagonalPos);
        // Same biome touching itself diagonally is always fine, same reasoning as findIllegalAdjacencies.
        if (diagonalBiome == null || biome.equals(diagonalBiome) || graph.isAllowed(biome, diagonalBiome)) {
            return Optional.empty();
        }
        MacroCellPos elbow1 = new MacroCellPos(cell.x() + offset[0], cell.z());
        MacroCellPos elbow2 = new MacroCellPos(cell.x(), cell.z() + offset[1]);
        BiomeId elbow1Biome = assignment.get(elbow1);
        BiomeId elbow2Biome = assignment.get(elbow2);
        boolean alsoTouchesOrthogonally = biome.equals(elbow1Biome) || diagonalBiome.equals(elbow1Biome)
            || biome.equals(elbow2Biome) || diagonalBiome.equals(elbow2Biome);
        if (alsoTouchesOrthogonally) {
            return Optional.empty(); // already reported by findIllegalAdjacencies
        }
        return Optional.of(new IllegalAdjacency(cell, biome, diagonalPos, diagonalBiome));
    }

    private static List<SmallFragment> findSmallFragments(Map<MacroCellPos, BiomeId> assignment, List<Set<MacroCellPos>> components) {
        List<SmallFragment> fragments = new ArrayList<>();
        for (Set<MacroCellPos> component : components) {
            if (component.size() > SMALL_FRAGMENT_MAX_SIZE) {
                continue;
            }
            BiomeId biome = assignment.get(component.iterator().next());
            fragments.add(new SmallFragment(biome, component));
        }
        return fragments;
    }

    /** A cell whose neighbors on one WHOLE axis (both north+south, or both east+west) are all a different biome (or missing), while still connected to same-biome neighbors on the other axis - the patch is only 1 cell thick here. A cell with a same-biome DIAGONAL neighbor is skipped - a diagonal boundary line trips the orthogonal axis check on every cell along it regardless of true thickness, so diagonal support means the shape is genuinely thicker than this axis-only check can see, not a real pinch. Cells already part of a small fragment are excluded (reported once, not twice). */
    private static List<PinchPoint> findPinchPoints(Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> fragmentCells) {
        List<PinchPoint> pinches = new ArrayList<>();
        for (Map.Entry<MacroCellPos, BiomeId> entry : assignment.entrySet()) {
            MacroCellPos cell = entry.getKey();
            if (fragmentCells.contains(cell)) {
                continue;
            }
            pinches.addAll(pinchPointsAt(cell, entry.getValue(), assignment));
        }
        return pinches;
    }

    /** {@code cell}'s own pinch check on both axes - the loop body of {@link #findPinchPoints}, returning 0-2 pinch points. */
    private static List<PinchPoint> pinchPointsAt(MacroCellPos cell, BiomeId biome, Map<MacroCellPos, BiomeId> assignment) {
        if (hasDiagonalSameBiomeNeighbor(assignment, cell, biome)) {
            return List.of();
        }
        MacroCellPos northPos = new MacroCellPos(cell.x(), cell.z() - 1);
        MacroCellPos southPos = new MacroCellPos(cell.x(), cell.z() + 1);
        MacroCellPos eastPos = new MacroCellPos(cell.x() + 1, cell.z());
        MacroCellPos westPos = new MacroCellPos(cell.x() - 1, cell.z());
        BiomeId north = assignment.get(northPos);
        BiomeId south = assignment.get(southPos);
        BiomeId east = assignment.get(eastPos);
        BiomeId west = assignment.get(westPos);

        List<PinchPoint> pinches = new ArrayList<>();
        if (!biome.equals(north) && !biome.equals(south)) {
            pinches.add(new PinchPoint(cell, biome, "north-south", northPos, north, southPos, south));
        }
        if (!biome.equals(east) && !biome.equals(west)) {
            pinches.add(new PinchPoint(cell, biome, "east-west", eastPos, east, westPos, west));
        }
        return pinches;
    }

    private static boolean hasDiagonalSameBiomeNeighbor(Map<MacroCellPos, BiomeId> assignment, MacroCellPos cell, BiomeId biome) {
        for (int dx : new int[] {-1, 1}) {
            for (int dz : new int[] {-1, 1}) {
                if (biome.equals(assignment.get(new MacroCellPos(cell.x() + dx, cell.z() + dz)))) {
                    return true;
                }
            }
        }
        return false;
    }
}
