package com.mineutze.worldgen.reachability;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AxialPosTest {

    @Test
    void distanceToSelfIsZero() {
        assertEquals(0, new AxialPos(3, -2).distanceTo(new AxialPos(3, -2)));
    }

    @Test
    void everyDirectNeighborIsDistanceOne() {
        AxialPos center = new AxialPos(0, 0);
        for (AxialPos neighbor : center.neighbors()) {
            assertEquals(1, center.distanceTo(neighbor), neighbor + " should be distance 1 from " + center);
        }
    }

    @Test
    void consecutiveNeighborsAreMutuallyAdjacent() {
        // Real hex mesh property (vertex-degree-3): two CONSECUTIVE neighbors of a shared
        // face are also mutually adjacent to each other - directly relied on by
        // HexPromotionSimulator's own reasoning (see its test's two-attempt scenario).
        AxialPos center = new AxialPos(0, 0);
        List<AxialPos> neighbors = center.neighbors();
        for (int i = 0; i < neighbors.size(); i++) {
            AxialPos a = neighbors.get(i);
            AxialPos b = neighbors.get((i + 1) % neighbors.size());
            assertEquals(1, a.distanceTo(b), a + " and " + b + " (consecutive neighbors of " + center + ") should be mutually adjacent");
        }
    }

    @Test
    void ringOfRadiusZeroIsJustCenter() {
        assertEquals(List.of(new AxialPos(5, 5)), AxialPos.ring(new AxialPos(5, 5), 0));
    }

    @Test
    void ringHasExactlySixTimesRadiusCellsAllAtThatDistance() {
        AxialPos center = new AxialPos(0, 0);
        for (int radius = 1; radius <= 5; radius++) {
            List<AxialPos> ring = AxialPos.ring(center, radius);
            assertEquals(6 * radius, ring.size(), "ring radius " + radius);
            assertEquals(6 * radius, Set.copyOf(ring).size(), "ring radius " + radius + " should have no duplicate cells");
            for (AxialPos cell : ring) {
                assertEquals(radius, center.distanceTo(cell), cell + " should be exactly " + radius + " from center");
            }
        }
    }

    @Test
    void neighborDirectionsAreTheirOwnInverseTwoApart() {
        // direction d and d+3 (mod 6) should be opposite - sanity check on the delta table itself.
        AxialPos center = new AxialPos(0, 0);
        for (int direction = 0; direction < 6; direction++) {
            AxialPos forward = center.neighbor(direction);
            AxialPos backward = center.neighbor((direction + 3) % 6);
            assertTrue(forward.q() == -backward.q() && forward.r() == -backward.r(), "direction " + direction + " and its opposite should cancel");
        }
    }
}
