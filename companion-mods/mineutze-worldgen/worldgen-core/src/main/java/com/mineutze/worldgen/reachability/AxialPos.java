package com.mineutze.worldgen.reachability;

import java.util.ArrayList;
import java.util.List;

/**
 * Pointy-top hex axial coordinate - own copy, not {@code regionsolve.topology.mesh.HexGrid}'s
 * (see package doc: independent of the live mesh CODE, faithful only to its MECHANICS).
 * Neighbor deltas match {@code HexGrid}/{@code DeadEndFrequencySimulation}'s own real
 * convention exactly, by inspection - not an independently-derived shape.
 */
public record AxialPos(int q, int r) {

    private static final int[][] NEIGHBOR_DELTAS = {
        {1, 0}, {1, -1}, {0, -1}, {-1, 0}, {-1, 1}, {0, 1}
    };

    public AxialPos neighbor(int direction) {
        int[] delta = NEIGHBOR_DELTAS[direction];
        return new AxialPos(q + delta[0], r + delta[1]);
    }

    public List<AxialPos> neighbors() {
        List<AxialPos> result = new ArrayList<>(6);
        for (int direction = 0; direction < NEIGHBOR_DELTAS.length; direction++) {
            result.add(neighbor(direction));
        }
        return result;
    }

    /** Standard axial hex distance - number of single-step moves needed to reach {@code other}. */
    public int distanceTo(AxialPos other) {
        int dq = q - other.q;
        int dr = r - other.r;
        return (Math.abs(dq) + Math.abs(dr) + Math.abs(dq + dr)) / 2;
    }

    /**
     * Every cell at exactly {@code radius} hex-steps from {@code center}, in real ring order
     * (standard hex-ring-walk algorithm) - {@code radius=0} is just {@code center} itself.
     * {@code 6*radius} cells for {@code radius>0}, matching the real hex lattice's own growth.
     */
    public static List<AxialPos> ring(AxialPos center, int radius) {
        if (radius == 0) {
            return List.of(center);
        }
        List<AxialPos> result = new ArrayList<>(6 * radius);
        AxialPos hex = center;
        for (int step = 0; step < radius; step++) {
            hex = hex.neighbor(4);
        }
        for (int side = 0; side < 6; side++) {
            for (int step = 0; step < radius; step++) {
                result.add(hex);
                hex = hex.neighbor(side);
            }
        }
        return result;
    }
}
