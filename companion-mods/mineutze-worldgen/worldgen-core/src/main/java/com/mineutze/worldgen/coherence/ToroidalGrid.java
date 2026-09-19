package com.mineutze.worldgen.coherence;

import java.util.ArrayList;
import java.util.List;

/**
 * A FINITE {@code width x height} hex-like grid, PERIODIC in both axial directions -
 * {@code Math.floorMod} on each coordinate means every position always has exactly 6 real,
 * in-grid neighbors, never an "off-grid"/undecided-by-construction case the way the
 * infinite {@code reachability} grid has (a real simplification this substrate gets for
 * free by being finite and wrapped from the start).
 *
 * <p><b>Real shape, not a hidden claim of a perfect square:</b> wrapping raw axial (q,r)
 * coordinates modulo width/height independently is self-consistent (moving {@code width}
 * steps in q, or {@code height} steps in r, always returns to the same cell) but the
 * resulting torus is shaped like a PARALLELOGRAM in real 2D space (hex axial coordinates
 * are naturally skewed), not a rectangle - a real "diamond," matching the user's own
 * stated tolerance for that shape (2026-09-17).
 */
final class ToroidalGrid {

    private static final int[][] NEIGHBOR_DELTAS = {
        {1, 0}, {1, -1}, {0, -1}, {-1, 0}, {-1, 1}, {0, 1}
    };

    private final int width;
    private final int height;

    ToroidalGrid(int width, int height) {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("ToroidalGrid: width and height must be >= 1, got " + width + "x" + height);
        }
        this.width = width;
        this.height = height;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    int cellCount() {
        return width * height;
    }

    ToroidalPos normalize(int q, int r) {
        return new ToroidalPos(Math.floorMod(q, width), Math.floorMod(r, height));
    }

    List<ToroidalPos> neighborsOf(ToroidalPos pos) {
        List<ToroidalPos> neighbors = new ArrayList<>(6);
        for (int[] delta : NEIGHBOR_DELTAS) {
            neighbors.add(normalize(pos.q() + delta[0], pos.r() + delta[1]));
        }
        return neighbors;
    }

    /**
     * Row-major (r outer, q inner) - the specific order that makes the two wraparound
     * seams (q=width-1 back to q=0 within a row; r=height-1 back to r=0 across the whole
     * grid) always check against an ALREADY-DECIDED neighbor, never a not-yet-reached one -
     * real, necessary property (same lesson {@code PeriodicCoherenceProof}'s own row-stack
     * substrate already learned the hard way: a wraparound conflict discovered only AFTER
     * full construction wastes enormous search effort - see this class's own doc history).
     */
    List<ToroidalPos> allPositionsInFillOrder() {
        List<ToroidalPos> all = new ArrayList<>(cellCount());
        for (int r = 0; r < height; r++) {
            for (int q = 0; q < width; q++) {
                all.add(new ToroidalPos(q, r));
            }
        }
        return all;
    }
}
