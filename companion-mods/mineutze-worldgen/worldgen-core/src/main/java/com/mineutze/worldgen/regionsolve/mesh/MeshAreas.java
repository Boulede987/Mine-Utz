package com.mineutze.worldgen.regionsolve.mesh;

import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mineutze.worldgen.regionsolve.topology.IndexedTriangle;
import com.mineutze.worldgen.regionsolve.topology.Point2D;

/**
 * Shoelace-formula area, shared by {@code PatchMerger} (sizing target) and
 * {@code EdgeDisplacement} (post-displacement area-drift recheck) so both
 * measure area the exact same way. {@link Point2D} coordinates already live
 * in macro-cell units (see {@code SeedGraphBuilder.toPoint}), so an area
 * computed here is already in macro-cell² units — the same unit
 * {@code MacroGridConfig.cellCountForWidthBlocks} returns, with no
 * conversion factor needed between the two.
 */
public final class MeshAreas {

    private MeshAreas() {
    }

    public static double area(FineMesh mesh, IndexedTriangle face) {
        return area(mesh.position(face.a()), mesh.position(face.b()), mesh.position(face.c()));
    }

    public static double area(Point2D a, Point2D b, Point2D c) {
        return Math.abs((b.x() - a.x()) * (c.z() - a.z()) - (c.x() - a.x()) * (b.z() - a.z())) / 2.0;
    }

    public static Point2D centroid(FineMesh mesh, IndexedTriangle face) {
        Point2D a = mesh.position(face.a());
        Point2D b = mesh.position(face.b());
        Point2D c = mesh.position(face.c());
        return new Point2D((a.x() + b.x() + c.x()) / 3.0, (a.z() + b.z() + c.z()) / 3.0);
    }

    /** A width-in-blocks (e.g. a {@code BiomeSizeRange}'s min or max) expressed as a square-patch area in macro-cell² units — shared by every caller that needs to compare a mesh area against a biome's declared size range. */
    public static double areaForWidthBlocks(int widthBlocks, MacroGridConfig grid) {
        double sideInCells = (double) widthBlocks / grid.macroCellSizeBlocks();
        return sideInCells * sideInCells;
    }
}
