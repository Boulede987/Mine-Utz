package com.mineutze.worldgen.regionsolve;

import java.util.List;

/** Position on the macro-cell grid (not block coords, not chunk coords). */
public record MacroCellPos(int x, int z) {

    public List<MacroCellPos> orthogonalNeighbors() {
        return List.of(
            new MacroCellPos(x + 1, z),
            new MacroCellPos(x - 1, z),
            new MacroCellPos(x, z + 1),
            new MacroCellPos(x, z - 1)
        );
    }

    /** The 4 corner-touching neighbors — orthogonalNeighbors() plus these is the full 8-cell Moore neighborhood. */
    public List<MacroCellPos> diagonalNeighbors() {
        return List.of(
            new MacroCellPos(x + 1, z + 1),
            new MacroCellPos(x + 1, z - 1),
            new MacroCellPos(x - 1, z + 1),
            new MacroCellPos(x - 1, z - 1)
        );
    }

    public long distanceSquaredTo(MacroCellPos other) {
        long dx = x - other.x;
        long dz = z - other.z;
        return dx * dx + dz * dz;
    }

    // Record's default hashCode (essentially 31*x+z) only spans a few tens of thousands
    // of distinct values - fine for the old ~1k-cell regions, but at the real (512-cell-
    // per-side) region scale a 260k+-entry HashMap/LinkedHashMap keyed on this collides
    // on nearly every bucket and treeifies almost everywhere, turning every single get()
    // into a red-black-tree lookup instead of O(1) (found via jstack: HashMap$TreeNode.find
    // as the hottest frame during a hung SeparatorWidener flood-fill). Golden-ratio
    // multiply-then-xor spreads x/z across the full 32-bit range so collisions stay rare
    // at this scale.
    @Override
    public int hashCode() {
        return x * 0x9E3779B1 ^ z;
    }
}
