package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.Map;

/**
 * Measures a cell's contiguous same-biome run along one grid axis, capped by
 * the first differing (or unassigned) cell on either side — the "how wide is
 * this patch right here" primitive both {@link SeparatorWidthEnforcer} (is
 * it under the hard minimum) and {@link SeparatorOrganicizer} (is it a local
 * corridor worth roughening, regardless of what its whole connected
 * component's shape looks like elsewhere) need, extracted so neither
 * reimplements it.
 */
final class AxisRunFinder {

    private AxisRunFinder() {
    }

    /** @param alongX true = measure the X axis (east/west boundary), false = the Z axis (north/south boundary). */
    static MacroCellPos boundaryJustPast(MacroCellPos from, boolean alongX, int direction, BiomeId biome, Map<MacroCellPos, BiomeId> assignment) {
        MacroCellPos cursor = from;
        while (biome.equals(assignment.get(cursor))) {
            cursor = stepOnce(cursor, alongX, direction);
        }
        return cursor;
    }

    static MacroCellPos stepOnce(MacroCellPos from, boolean alongX, int direction) {
        return alongX ? new MacroCellPos(from.x() + direction, from.z()) : new MacroCellPos(from.x(), from.z() + direction);
    }

    /** Cell count strictly between two boundary cells (exclusive on both ends) found via {@link #boundaryJustPast}. */
    static int runLengthBetween(MacroCellPos low, MacroCellPos high, boolean alongX) {
        return alongX ? (high.x() - low.x() - 1) : (high.z() - low.z() - 1);
    }
}
