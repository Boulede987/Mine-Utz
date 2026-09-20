package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static com.mineutze.worldgen.plan.ToroidalTestTables.A;
import static com.mineutze.worldgen.plan.ToroidalTestTables.B;
import static com.mineutze.worldgen.plan.ToroidalTestTables.C;
import static com.mineutze.worldgen.plan.ToroidalTestTables.threeColoring;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealizedBiomeFieldTest {

    private static final ToroidalGrid GRID = new ToroidalGrid(6, 6);
    private static final HexTorusGeometry GEOMETRY = new HexTorusGeometry(6, 6, 300);
    private static final List<BiomeId> ORDER = List.of(A, B, C);

    private static RealizedBiomeField field(DivergenceFreeWarp warp) {
        return new RealizedBiomeField(GRID, threeColoring(GRID, A, B, C), GEOMETRY, warp, ORDER);
    }

    private static DivergenceFreeWarp strongFlow(long seed) {
        return new DivergenceFreeWarp(GEOMETRY, seed, new DivergenceFreeWarp.Settings(40, 40, 800, 35, 0.8, 8));
    }

    @Test
    void withoutAWarpAPointHasTheBiomeOfTheHexagonItLiesIn() {
        Map<ToroidalPos, BiomeId> pattern = threeColoring(GRID, A, B, C);
        RealizedBiomeField plain = field(DivergenceFreeWarp.none(GEOMETRY));
        Random random = new Random(1);

        for (int point = 0; point < 200; point++) {
            double x = random.nextDouble() * 4_000 - 1_000;
            double y = random.nextDouble() * 4_000 - 1_000;
            assertEquals(pattern.get(GEOMETRY.cellAt(x, y)), ORDER.get(plain.biomeIndexAt(x, y)), "at " + x + "," + y);
        }
    }

    /**
     * The whole point of a tileable map: whatever the warp does, moving by a tile vector must land on the
     * SAME biome, at any point - including points on and next to the tile edges - or the tiles would meet in
     * a visible cut. Checked with a strong warp so a broken repeat could not hide.
     */
    @Test
    void theMapRepeatsExactlyAfterEveryTileVectorSoTilesMeetWithoutASeam() {
        RealizedBiomeField warped = field(strongFlow(5));
        Random random = new Random(2);

        for (int point = 0; point < 400; point++) {
            double x = random.nextDouble() * 3_000;
            double y = random.nextDouble() * 3_000;
            int here = warped.biomeIndexAt(x, y);
            for (int stepsAlongA = -2; stepsAlongA <= 2; stepsAlongA++) {
                for (int stepsAlongB = -2; stepsAlongB <= 2; stepsAlongB++) {
                    double movedX = x + stepsAlongA * GEOMETRY.tileVectorAX() + stepsAlongB * GEOMETRY.tileVectorBX();
                    double movedY = y + stepsAlongA * GEOMETRY.tileVectorAY() + stepsAlongB * GEOMETRY.tileVectorBY();
                    assertEquals(here, warped.biomeIndexAt(movedX, movedY), "point " + x + "," + y + " moved by " + stepsAlongA + "A + " + stepsAlongB + "B");
                }
            }
        }
    }

    /** Walking across the edge of the tile changes biome exactly where walking across an equivalent line inside another tile does: the edge is not special. */
    @Test
    void aWalkAcrossTheTileEdgeSeesTheSameBiomeChangesAsTheSameWalkOneTileAway() {
        RealizedBiomeField warped = field(strongFlow(6));
        double startX = -300;
        double startY = 700;

        for (int step = 0; step < 600; step++) {
            double x = startX + step;
            assertEquals(
                warped.biomeIndexAt(x, startY), warped.biomeIndexAt(x + GEOMETRY.tileVectorAX(), startY + GEOMETRY.tileVectorAY()), "step " + step
            );
        }
    }

    @Test
    void aStrongWarpDoesChangeTheMapComparedWithNone() {
        RealizedBiomeField plain = field(DivergenceFreeWarp.none(GEOMETRY));
        RealizedBiomeField warped = field(strongFlow(7));
        Random random = new Random(3);

        int different = 0;
        for (int point = 0; point < 400; point++) {
            double x = random.nextDouble() * 1_500;
            double y = random.nextDouble() * 1_500;
            if (plain.biomeIndexAt(x, y) != warped.biomeIndexAt(x, y)) {
                different++;
            }
        }
        assertTrue(different > 10, "the warp should visibly move borders; only " + different + " of 400 points changed");
    }

    @Test
    void cellIndexAndBiomeIndexAgree() {
        RealizedBiomeField warped = field(strongFlow(8));
        Map<ToroidalPos, BiomeId> pattern = threeColoring(GRID, A, B, C);

        int cell = warped.cellIndexAt(1_234.5, 987.6);

        assertEquals(pattern.get(new ToroidalPos(cell % GRID.width(), cell / GRID.width())), ORDER.get(warped.biomeIndexAt(1_234.5, 987.6)));
    }
}
