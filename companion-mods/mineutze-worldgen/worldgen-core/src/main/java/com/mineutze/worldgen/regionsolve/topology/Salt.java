package com.mineutze.worldgen.regionsolve.topology;

import java.nio.charset.StandardCharsets;

/**
 * Derives a deterministic salt constant from a short, self-documenting
 * purpose name instead of a hand-picked hex literal (this codebase already
 * had several — {@code SeedGrid.JITTER_SALT}, {@code MacroGridConfig
 * .WARP_SALT_X}/{@code WARP_SALT_Z} — each a separately invented magic
 * number with no defense against two features accidentally colliding).
 * {@code Salt.of("EdgeDisplacement.wiggle")} both names itself for a reader
 * and gets a value effectively as collision-free as a hand-picked one, via
 * a standard string hash (FNV-1a, 64-bit) — nothing here is used for
 * anything security-sensitive, only for scattering deterministic-per-input
 * noise, so a non-cryptographic hash is the right tool.
 */
public final class Salt {

    private static final long FNV_OFFSET_BASIS = 0xCBF2_9CE4_8422_2325L;
    private static final long FNV_PRIME = 0x0000_0100_0000_01B3L;

    private Salt() {
    }

    public static long of(String purpose) {
        long hash = FNV_OFFSET_BASIS;
        for (byte b : purpose.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xFFL);
            hash *= FNV_PRIME;
        }
        return hash;
    }
}
