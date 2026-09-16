package com.mineutze.worldgen.coherence;

import com.mineutze.worldgen.coherence.PeriodicRowLayout.Cell;
import com.mineutze.worldgen.coherence.PeriodicRowLayout.Row;

import java.util.ArrayList;
import java.util.List;

/**
 * Derives every physical-adjacency edge from a fully-positioned {@link PeriodicRowLayout} -
 * purely mechanical, zero freedom left once widths/positions are fixed (see {@link
 * RowFeasibility}). Horizontal: each row's own cells, consecutive, wrapping last-to-first.
 * Vertical: row r's cells against row {@code (r+1) mod rowCount}'s cells (this single mod
 * also handles the whole grid's own top-to-bottom wraparound, not a special case), via
 * standard interval overlap on their SHARED [0, circumferenceBlocks) coordinate system - no
 * cell individually wraps that boundary (widths always sum to exactly circumferenceBlocks
 * by construction), so only row-to-row wraparound needs the modular index, not the overlap
 * math itself.
 */
final class AdjacencyDerivation {

    // Real cell widths/positions here are on the order of tens to hundreds of blocks -
    // this is many orders of magnitude below the smallest meaningful geometric distinction,
    // only guards against floating-point noise from RowFeasibility's proportional split.
    private static final double EPSILON_BLOCKS = 1e-6;

    record Edge(Cell a, Cell b) {
    }

    private AdjacencyDerivation() {
    }

    static List<Edge> deriveAll(PeriodicRowLayout layout) {
        List<Edge> edges = new ArrayList<>();
        int rowCount = layout.rows().size();
        for (int r = 0; r < rowCount; r++) {
            Row row = layout.rows().get(r);
            edges.addAll(horizontalEdges(row));
            Row nextRow = layout.rows().get((r + 1) % rowCount);
            edges.addAll(verticalEdges(row, nextRow));
        }
        return edges;
    }

    private static List<Edge> horizontalEdges(Row row) {
        List<Edge> edges = new ArrayList<>();
        List<Cell> cells = row.cells();
        if (cells.size() <= 1) {
            return edges; // a single cell can't be horizontally adjacent to itself
        }
        for (int i = 0; i < cells.size(); i++) {
            edges.add(new Edge(cells.get(i), cells.get((i + 1) % cells.size())));
        }
        return edges;
    }

    private static List<Edge> verticalEdges(Row lower, Row upper) {
        List<Edge> edges = new ArrayList<>();
        for (Cell lowerCell : lower.cells()) {
            for (Cell upperCell : upper.cells()) {
                if (overlaps(lowerCell, upperCell)) {
                    edges.add(new Edge(lowerCell, upperCell));
                }
            }
        }
        return edges;
    }

    /** Package-visible: {@link PeriodicCoherenceProof} reuses this same overlap test during construction, to check vertical legality against an already-finalized row before committing to a new one - not just post-hoc via {@link #deriveAll}. */
    static boolean overlaps(Cell a, Cell b) {
        return intervalsOverlap(a.startBlocks(), a.widthBlocks(), b.startBlocks(), b.widthBlocks());
    }

    /**
     * Raw-double version of the same overlap test, package-visible: {@link
     * PeriodicCoherenceProof} also uses this against PROVISIONAL (not-yet-finalized)
     * positions while a row is still being built, to keep the random walk itself
     * vertically-aware instead of only checking after the fact.
     */
    static boolean intervalsOverlap(double aStart, double aWidth, double bStart, double bWidth) {
        return aStart < bStart + bWidth - EPSILON_BLOCKS && bStart < aStart + aWidth - EPSILON_BLOCKS;
    }
}
