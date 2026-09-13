package com.mineutze.worldgen.regionsolve;

import java.util.List;
import java.util.stream.Stream;

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

    /** The full 8-cell Moore neighborhood — {@link #orthogonalNeighbors()} plus {@link #diagonalNeighbors()}. */
    public List<MacroCellPos> mooreNeighbors() {
        return Stream.concat(orthogonalNeighbors().stream(), diagonalNeighbors().stream()).toList();
    }

    public long distanceSquaredTo(MacroCellPos other) {
        long dx = x - other.x;
        long dz = z - other.z;
        return dx * dx + dz * dz;
    }

    // Record's default hashCode (31*x+z) spans only tens of thousands of values - fine at
    // old ~1k-cell regions, but at real 512-cell-per-side scale a 260k+-entry HashMap
    // collides almost every bucket, treeifies, turns get() into red-black-tree lookup not
    // O(1) (found via jstack: HashMap$TreeNode.find hottest frame during hung
    // SeparatorWidener flood-fill). Golden-ratio multiply-then-xor spreads x/z across full
    // 32-bit range, collisions stay rare at this scale.
    @Override
    public int hashCode() {
        return x * 0x9E3779B1 ^ z;
    }
}
