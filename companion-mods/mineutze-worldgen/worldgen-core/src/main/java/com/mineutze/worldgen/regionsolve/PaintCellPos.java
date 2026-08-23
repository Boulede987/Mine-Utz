package com.mineutze.worldgen.regionsolve;

import java.util.List;

/**
 * Position on the coarse "paint grid" {@code CoarseToFineRasterizer} batches
 * pixel-level rasterization work by — each paint cell nominally covers a
 * {@link MacroGridConfig#paintCellSizeBlocks()} / {@link
 * MacroGridConfig#macroCellSizeBlocks()} square of fine {@link MacroCellPos}
 * cells, though a paint cell straddling the region's true (warped) boundary
 * can own fewer than that.
 */
public record PaintCellPos(int x, int z) {

    public static PaintCellPos of(MacroCellPos fine, int ratio) {
        return new PaintCellPos(Math.floorDiv(fine.x(), ratio), Math.floorDiv(fine.z(), ratio));
    }

    public List<PaintCellPos> orthogonalNeighbors() {
        return List.of(
            new PaintCellPos(x + 1, z),
            new PaintCellPos(x - 1, z),
            new PaintCellPos(x, z + 1),
            new PaintCellPos(x, z - 1)
        );
    }

    /** The 4 corner-touching neighbors — orthogonalNeighbors() plus these is the full 8-cell Moore neighborhood. */
    public List<PaintCellPos> diagonalNeighbors() {
        return List.of(
            new PaintCellPos(x + 1, z + 1),
            new PaintCellPos(x + 1, z - 1),
            new PaintCellPos(x - 1, z + 1),
            new PaintCellPos(x - 1, z - 1)
        );
    }

    // Same rationale as MacroCellPos's own hashCode override: a region's worth of paint
    // cells still lands in the tens of thousands, and the default record hash spreads
    // poorly enough at that scale to matter (see MacroCellPos's own comment for the
    // measured HashMap-treeification story this avoids repeating).
    @Override
    public int hashCode() {
        return x * 0x9E3779B1 ^ z;
    }
}
