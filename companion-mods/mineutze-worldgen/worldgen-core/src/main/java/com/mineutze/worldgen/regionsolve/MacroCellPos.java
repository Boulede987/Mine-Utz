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

    public long distanceSquaredTo(MacroCellPos other) {
        long dx = x - other.x;
        long dz = z - other.z;
        return dx * dx + dz * dz;
    }
}
