package com.mineutze.worldgen.coherence;

import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.List;

/**
 * A candidate periodic world: {@code rowCount} rows of fixed height {@code
 * rowHeightBlocks}, each row a circular sequence of cells whose widths sum to the SAME
 * shared {@code circumferenceBlocks} - real "brick wall" coursing, not a fixed lattice.
 * Deliberately not the live hex mesh: hex-mesh self-adjacency-as-clustering exists only
 * because a hex cell has one fixed size, so a biome needing more area than one hex offers
 * has to fake it by merging cells (2026-09 user correction). Here, one biome instance is
 * exactly one region, sized directly to whatever it needs via its own {@code widthBlocks}
 * (area = {@code widthBlocks * rowHeightBlocks}) - no clustering, no self-adjacency
 * exception. This also naturally gives each cell VARIABLE degree (however many cells in
 * the row above/below its own width happens to overlap), not a fixed neighbor count.
 */
public record PeriodicRowLayout(double rowHeightBlocks, double circumferenceBlocks, List<Row> rows) {

    public record Row(List<Cell> cells) {
        public double totalWidthBlocks() {
            return cells.stream().mapToDouble(Cell::widthBlocks).sum();
        }
    }

    /** {@code startBlocks} is this cell's position along the row's shared circumference, in [0, circumferenceBlocks). */
    public record Cell(BiomeId biomeId, double widthBlocks, double startBlocks) {
    }
}
