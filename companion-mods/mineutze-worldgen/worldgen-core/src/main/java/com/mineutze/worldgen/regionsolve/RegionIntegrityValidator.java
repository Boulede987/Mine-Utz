package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ported directly from this repo's own {@code validate_biome_export.py} (previously run
 * by hand against an exported {@code regions.json} after the fact) — same four checks,
 * same defect definitions, now run in-process against the live {@code
 * Map<MacroCellPos,BiomeId>} a region solve just produced, so a problem shows up
 * immediately instead of needing a separate manual export-then-script round trip. Kept
 * in sync by hand with that script, same as that script was already kept in sync by
 * hand with {@code PixelFragmentRepair.SMALL_FRAGMENT_MAX_SIZE}.
 *
 * <p>{@link SeamGrowthRasterizer} already GUARANTEES zero illegal orthogonal OR
 * diagonal adjacencies in its own output (see that class's own doc — every claim,
 * both during growth and in its {@code fillGaps} cleanup, is legality-checked before
 * being committed). So {@link #findIllegalAdjacencies} and
 * {@link #findDiagonalIllegalAdjacencies} exist here as a regression guard / second
 * opinion, not because they're expected to ever actually fire — a nonempty result
 * there means that guarantee has broken, not that this validator found a "normal"
 * defect. Small fragments and one-cell pinch points are NOT guaranteed by anything in
 * the pipeline — real, still-open shape/size concerns this validator is the only thing
 * currently measuring.
 */
public final class RegionIntegrityValidator {

    /**
     * Matches {@code PixelFragmentRepair.SMALL_FRAGMENT_MAX_SIZE} — a same-biome
     * component this size or smaller is discretization debris (speck/sliver), not a
     * legitimate small patch. Sync by hand if that constant ever changes.
     */
    public static final int SMALL_FRAGMENT_MAX_SIZE = 4;

    // Not a crash risk (SeamGrowthRasterizer already guarantees legality
    // separately) - but per direct user instruction, a messy/speckled world matters
    // just as much as a legal one, so this must never read as a quiet, easy-to-miss
    // line in a wall of output. Shown ONLY when the section it precedes is nonempty.
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
            text.append("=== Illegal adjacencies: ").append(illegalAdjacencies.size()).append(" ===\n");
            text.append("Why: no AdjacencyRule exists between these two biomes (default-deny).\n");
            for (IllegalAdjacency violation : illegalAdjacencies) {
                text.append("  ").append(violation.cellA()).append(' ').append(violation.biomeA())
                    .append("  <->  ").append(violation.cellB()).append(' ').append(violation.biomeB()).append('\n');
            }

            text.append("=== Diagonal illegal adjacencies: ").append(diagonalIllegalAdjacencies.size()).append(" ===\n");
            text.append("Why: no AdjacencyRule exists between these two biomes, and they touch only at a corner.\n");
            for (IllegalAdjacency violation : diagonalIllegalAdjacencies) {
                text.append("  ").append(violation.cellA()).append(' ').append(violation.biomeA())
                    .append("  <->  ").append(violation.cellB()).append(' ').append(violation.biomeB()).append(" [corner touch]\n");
            }

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

            text.append(isClean() ? "PASS" : "FAIL");
            return text.toString();
        }
    }

    private RegionIntegrityValidator() {
    }

    public static Report validate(Map<MacroCellPos, BiomeId> assignment, BiomeGraph graph) {
        List<IllegalAdjacency> illegal = findIllegalAdjacencies(assignment, graph);
        List<IllegalAdjacency> diagonalIllegal = findDiagonalIllegalAdjacencies(assignment, graph);
        // Moore (8-way), not orthogonal-only: the growth algorithm itself treats diagonal
        // adjacency as real connectivity everywhere (legality checks, cohesion, the
        // head-start reservation pass), so measuring "isolated" via orthogonal-only
        // adjacency was splitting a real diagonally-touching pair into two false
        // single-cell fragments - confirmed directly against a real production export
        // (see this class's own git history for that measurement).
        List<Set<MacroCellPos>> components = ConnectedComponents.find(assignment, assignment.keySet(), MacroCellPos::mooreNeighbors);
        List<SmallFragment> smallFragments = findSmallFragments(assignment, components);

        Set<MacroCellPos> fragmentCells = new LinkedHashSet<>();
        for (SmallFragment fragment : smallFragments) {
            fragmentCells.addAll(fragment.cells());
        }
        List<PinchPoint> pinchPoints = findPinchPoints(assignment, fragmentCells);

        return new Report(illegal, diagonalIllegal, smallFragments, pinchPoints);
    }

    /** Two orthogonally-adjacent cells with no legal path between their biomes — each undirected pair reported once (only the +x and +z direction is checked from each cell). */
    private static List<IllegalAdjacency> findIllegalAdjacencies(Map<MacroCellPos, BiomeId> assignment, BiomeGraph graph) {
        List<IllegalAdjacency> violations = new ArrayList<>();
        for (Map.Entry<MacroCellPos, BiomeId> entry : assignment.entrySet()) {
            MacroCellPos cell = entry.getKey();
            BiomeId biome = entry.getValue();
            for (MacroCellPos neighborPos : List.of(new MacroCellPos(cell.x() + 1, cell.z()), new MacroCellPos(cell.x(), cell.z() + 1))) {
                BiomeId neighborBiome = assignment.get(neighborPos);
                if (neighborBiome != null && !graph.isAllowed(biome, neighborBiome)) {
                    violations.add(new IllegalAdjacency(cell, biome, neighborPos, neighborBiome));
                }
            }
        }
        return violations;
    }

    /** Two corner-touching (not orthogonal) cells with no legal path between them, and no orthogonal contact either (e.g. d./.f) — not caught by {@link #findIllegalAdjacencies}, and nothing in the generator pipeline checks diagonals directly. */
    private static List<IllegalAdjacency> findDiagonalIllegalAdjacencies(Map<MacroCellPos, BiomeId> assignment, BiomeGraph graph) {
        List<IllegalAdjacency> violations = new ArrayList<>();
        int[][] diagonalOffsets = {{1, 1}, {1, -1}}; // each undirected diagonal pair checked once
        for (Map.Entry<MacroCellPos, BiomeId> entry : assignment.entrySet()) {
            MacroCellPos cell = entry.getKey();
            BiomeId biome = entry.getValue();
            for (int[] offset : diagonalOffsets) {
                MacroCellPos diagonalPos = new MacroCellPos(cell.x() + offset[0], cell.z() + offset[1]);
                BiomeId diagonalBiome = assignment.get(diagonalPos);
                if (diagonalBiome == null || graph.isAllowed(biome, diagonalBiome)) {
                    continue;
                }
                MacroCellPos elbow1 = new MacroCellPos(cell.x() + offset[0], cell.z());
                MacroCellPos elbow2 = new MacroCellPos(cell.x(), cell.z() + offset[1]);
                BiomeId elbow1Biome = assignment.get(elbow1);
                BiomeId elbow2Biome = assignment.get(elbow2);
                boolean alsoTouchesOrthogonally = biome.equals(elbow1Biome) || diagonalBiome.equals(elbow1Biome)
                    || biome.equals(elbow2Biome) || diagonalBiome.equals(elbow2Biome);
                if (alsoTouchesOrthogonally) {
                    continue; // already reported by findIllegalAdjacencies
                }
                violations.add(new IllegalAdjacency(cell, biome, diagonalPos, diagonalBiome));
            }
        }
        return violations;
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

    /** A cell whose neighbors on one WHOLE axis (both north+south, or both east+west) are all a different biome (or missing), while it's still connected to same-biome neighbors on the other axis — the patch is only 1 cell thick at this point. A cell with a same-biome DIAGONAL neighbor is skipped — a diagonal boundary line trips the orthogonal axis check on every cell along it regardless of true thickness, so diagonal support means the shape is genuinely thicker than this axis-only check alone can see, not a real pinch. Cells already part of a small fragment are excluded (reported once, not twice). */
    private static List<PinchPoint> findPinchPoints(Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> fragmentCells) {
        List<PinchPoint> pinches = new ArrayList<>();
        for (Map.Entry<MacroCellPos, BiomeId> entry : assignment.entrySet()) {
            MacroCellPos cell = entry.getKey();
            if (fragmentCells.contains(cell)) {
                continue;
            }
            BiomeId biome = entry.getValue();
            if (hasDiagonalSameBiomeNeighbor(assignment, cell, biome)) {
                continue;
            }
            MacroCellPos northPos = new MacroCellPos(cell.x(), cell.z() - 1);
            MacroCellPos southPos = new MacroCellPos(cell.x(), cell.z() + 1);
            MacroCellPos eastPos = new MacroCellPos(cell.x() + 1, cell.z());
            MacroCellPos westPos = new MacroCellPos(cell.x() - 1, cell.z());
            BiomeId north = assignment.get(northPos);
            BiomeId south = assignment.get(southPos);
            BiomeId east = assignment.get(eastPos);
            BiomeId west = assignment.get(westPos);

            if (!biome.equals(north) && !biome.equals(south)) {
                pinches.add(new PinchPoint(cell, biome, "north-south", northPos, north, southPos, south));
            }
            if (!biome.equals(east) && !biome.equals(west)) {
                pinches.add(new PinchPoint(cell, biome, "east-west", eastPos, east, westPos, west));
            }
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
