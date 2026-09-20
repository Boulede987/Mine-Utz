package com.mineutze.worldgen.plan;

/**
 * Real block coordinates for the toroidal hex pattern (2026-09-20). Cells are pointy-top
 * hexagons; a cell of "atom width" {@code w} is a regular hexagon of corner-to-corner width
 * {@code w} (the same convention as the biome size ranges), so its centers are
 * {@code sqrt(3)/2 * w} apart. Axial (q, r) maps to the plane as
 * {@code x = cellWidth * (q + r/2), y = rowHeight * r}, the layout the picture exporter already
 * draws. The pattern repeats every tile: moving by {@code A = (width * cellWidth, 0)} or
 * {@code B = (height * cellWidth / 2, height * rowHeight)} lands on the same cell, so the tile is
 * a 60-degree rhombus - the "diamond" the grid always was.
 */
final class HexTorusGeometry {

    private final int width;
    private final int height;
    private final double cornerRadius;
    private final double cellWidth;
    private final double rowHeight;

    HexTorusGeometry(int width, int height, double atomWidthBlocks) {
        if (atomWidthBlocks <= 0) {
            throw new IllegalArgumentException("HexTorusGeometry: atomWidthBlocks must be positive, got " + atomWidthBlocks);
        }
        this.width = width;
        this.height = height;
        this.cornerRadius = atomWidthBlocks / 2;
        this.cellWidth = Math.sqrt(3) * cornerRadius;
        this.rowHeight = 1.5 * cornerRadius;
    }

    /** Area of a regular hexagon whose corner-to-corner width is {@code widthBlocks} - how the biome size ranges turn a width into an area. */
    static double areaOfHexagonWidth(double widthBlocks) {
        return 3 * Math.sqrt(3) / 8 * widthBlocks * widthBlocks;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    /** Flat-to-flat width of a cell, which is also the distance between neighboring cell centers. */
    double cellWidthBlocks() {
        return cellWidth;
    }

    /** Length of one side of a cell: the hexagon's corner radius, the length of a border between two neighboring cells. */
    double cellEdgeBlocks() {
        return cornerRadius;
    }

    double cellAreaBlocks2() {
        return cellWidth * rowHeight;
    }

    double tileAreaBlocks2() {
        return width * height * cellAreaBlocks2();
    }

    double centerX(ToroidalPos pos) {
        return cellWidth * (pos.q() + pos.r() / 2.0);
    }

    double centerY(ToroidalPos pos) {
        return rowHeight * pos.r();
    }

    double tileVectorAX() {
        return width * cellWidth;
    }

    double tileVectorAY() {
        return 0;
    }

    double tileVectorBX() {
        return height * cellWidth / 2;
    }

    double tileVectorBY() {
        return height * rowHeight;
    }

    /** The cell containing the plane point, wrapped into the tile. */
    ToroidalPos cellAt(double x, double y) {
        double fractionalR = y / rowHeight;
        double fractionalQ = x / cellWidth - fractionalR / 2;
        double fractionalS = -fractionalQ - fractionalR;
        long roundedQ = Math.round(fractionalQ);
        long roundedR = Math.round(fractionalR);
        long roundedS = Math.round(fractionalS);
        double offByQ = Math.abs(roundedQ - fractionalQ);
        double offByR = Math.abs(roundedR - fractionalR);
        double offByS = Math.abs(roundedS - fractionalS);
        if (offByQ > offByR && offByQ > offByS) {
            roundedQ = -roundedR - roundedS;
        } else if (offByR > offByS) {
            roundedR = -roundedQ - roundedS;
        }
        return new ToroidalPos(Math.floorMod((int) roundedQ, width), Math.floorMod((int) roundedR, height));
    }
}
