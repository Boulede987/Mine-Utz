package com.mineutze.worldgen.coherence;

import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.coherence.PeriodicRowLayout.Cell;
import com.mineutze.worldgen.coherence.RawRuleTable.SizeRange;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a candidate sequence of biome types for one row into EXACT cell widths - no
 * solver, no approximation, no proxy. Each type's own [min,max] size range becomes a
 * width interval [min/h, max/h] (h = row height, shared by every row); the row's own
 * achievable TOTAL-width interval is the sum of its cells' own intervals. If the shared
 * circumference lies inside that interval, the needed slack is distributed
 * proportionally across cells - each keeps its own width inside its own interval - so
 * the row's cells sum to exactly the circumference, not approximately.
 */
final class RowFeasibility {

    // Real row/cell widths here are tens to hundreds of blocks - this only guards against
    // floating-point rounding (e.g. a computed circumference landing 1 ULP past an
    // interval's own bound after several chained arithmetic ops), never a real geometric
    // distinction. Real, measured need: distributeWidths threw on a circumference that was
    // 93.33333333333334 against an interval bound computed (independently, same inputs) as
    // 93.33333333333333 - a genuine rounding artifact, not a logic error, caught by this
    // class's own fail-loud check exactly as intended.
    private static final double EPSILON_BLOCKS = 1e-6;

    private RowFeasibility() {
    }

    record WidthInterval(double lowBlocks, double highBlocks) {
    }

    static WidthInterval achievableInterval(List<BiomeId> typeSequence, RawRuleTable table, double rowHeightBlocks) {
        double low = 0;
        double high = 0;
        for (BiomeId id : typeSequence) {
            SizeRange range = table.sizeRangeOf(id);
            low += range.minWidthBlocks() / rowHeightBlocks;
            high += range.maxWidthBlocks() / rowHeightBlocks;
        }
        return new WidthInterval(low, high);
    }

    static boolean fits(double circumferenceBlocks, WidthInterval interval) {
        return circumferenceBlocks >= interval.lowBlocks() - EPSILON_BLOCKS && circumferenceBlocks <= interval.highBlocks() + EPSILON_BLOCKS;
    }

    /**
     * @throws IllegalArgumentException if {@code circumferenceBlocks} doesn't fit this
     *                                   sequence's own {@link #achievableInterval} - callers must check {@link #fits} first.
     */
    static List<Cell> distributeWidths(List<BiomeId> typeSequence, RawRuleTable table, double rowHeightBlocks, double circumferenceBlocks) {
        int count = typeSequence.size();
        double[] lowByIndex = new double[count];
        double[] highByIndex = new double[count];
        double totalLow = 0;
        double totalSlack = 0;
        for (int i = 0; i < count; i++) {
            SizeRange range = table.sizeRangeOf(typeSequence.get(i));
            lowByIndex[i] = range.minWidthBlocks() / rowHeightBlocks;
            highByIndex[i] = range.maxWidthBlocks() / rowHeightBlocks;
            totalLow += lowByIndex[i];
            totalSlack += highByIndex[i] - lowByIndex[i];
        }
        double needed = circumferenceBlocks - totalLow;
        if (!fits(circumferenceBlocks, new WidthInterval(totalLow, totalLow + totalSlack))) {
            throw new IllegalArgumentException(
                "RowFeasibility: circumference " + circumferenceBlocks + " doesn't fit this sequence's achievable interval "
                    + "[" + totalLow + "," + (totalLow + totalSlack) + "] - call fits() first"
            );
        }
        // Clamp: the epsilon tolerance in fits() above can let `needed` land a hair outside
        // [0, totalSlack] (rounding, not a real infeasibility) - clamping keeps every
        // individual width inside its own [low,high], never propagating that epsilon into
        // an out-of-range cell.
        double fraction = totalSlack == 0 ? 0 : Math.min(1, Math.max(0, needed / totalSlack));

        List<Cell> cells = new ArrayList<>();
        double cursor = 0;
        for (int i = 0; i < count; i++) {
            boolean isLastCell = i == count - 1;
            // Last cell closes the row EXACTLY against circumferenceBlocks, absorbing any
            // floating-point drift from the proportional formula above - never approximate,
            // the row's cells must sum to exactly the shared circumference.
            double width = isLastCell ? circumferenceBlocks - cursor : lowByIndex[i] + fraction * (highByIndex[i] - lowByIndex[i]);
            cells.add(new Cell(typeSequence.get(i), width, cursor));
            cursor += width;
        }
        return cells;
    }
}
