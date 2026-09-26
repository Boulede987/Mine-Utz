package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphJson;
import com.mineutze.worldgen.biomegraph.BiomeId;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * The biome of every point of a world, decided once at world creation (2026-09-20). The world is a
 * periodic tile: a solved pattern of cells, its borders bent by an area-preserving warp, rendered
 * to a raster that the game then only reads. {@link #biomeAt} is a single array read, safe from any
 * number of threads, and the map repeats exactly after each tile vector, so tiles meet with no seam.
 *
 * <p>This is the only class the Minecraft side needs. It has no Minecraft dependency and no logger
 * (worldgen-core has none on purpose): progress goes to a {@link ProgressListener}, failures are
 * exceptions carrying the world seed and what the closest attempt was stuck on.
 *
 * <p>Creation takes tens of seconds on a many-core machine and minutes on a few cores, once per
 * world; a {@link PlanSnapshot} of the finished plan is what gets saved and reloaded.
 */
public final class WorldBiomePlan {

    static final int SCHEMA_VERSION = 1;

    private static final int LARGEST_PALETTE = 256;

    /** Receives one line per stage of creation. */
    @FunctionalInterface
    public interface ProgressListener {
        void report(String message);
    }

    private final long worldSeed;
    private final String catalogFingerprint;
    private final int gridWidth;
    private final int gridHeight;
    private final double atomWidthBlocks;
    private final double warpStrength;
    private final int patternAttempts;
    private final int strengthsTried;
    private final List<String> paletteNames;
    private final List<BiomeId> palette;
    private final int rasterSize;
    private final byte[] raster;
    private final HexTorusGeometry geometry;

    private WorldBiomePlan(
        long worldSeed, String catalogFingerprint, int gridWidth, int gridHeight, double atomWidthBlocks, double warpStrength, int patternAttempts, int strengthsTried,
        List<String> paletteNames, int rasterSize, byte[] raster
    ) {
        this.worldSeed = worldSeed;
        this.catalogFingerprint = catalogFingerprint;
        this.gridWidth = gridWidth;
        this.gridHeight = gridHeight;
        this.atomWidthBlocks = atomWidthBlocks;
        this.warpStrength = warpStrength;
        this.patternAttempts = patternAttempts;
        this.strengthsTried = strengthsTried;
        this.paletteNames = List.copyOf(paletteNames);
        this.palette = paletteNames.stream().map(WorldBiomePlan::biomeIdOf).toList();
        this.rasterSize = rasterSize;
        this.raster = raster;
        this.geometry = new HexTorusGeometry(gridWidth, gridHeight, atomWidthBlocks);
    }

    /** Solves and renders the world for {@code worldSeed} with the proven default settings. */
    public static WorldBiomePlan create(BiomeGraphData catalog, long worldSeed, int threads, ProgressListener progress) {
        return create(catalog, worldSeed, threads, progress, PlanSettings.DEFAULT);
    }

    static WorldBiomePlan create(BiomeGraphData catalog, long worldSeed, int threads, ProgressListener progress, PlanSettings settings) {
        RawRuleTable table = RawRuleTable.from(catalog);
        List<BiomeId> biomes = table.allBiomeIds();
        if (biomes.size() > LARGEST_PALETTE) {
            throw new IllegalStateException("WorldBiomePlan: the catalog has " + biomes.size() + " biomes but a map stores at most " + LARGEST_PALETTE);
        }
        ToroidalGrid grid = new ToroidalGrid(settings.gridSize(), settings.gridSize());
        HexTorusGeometry geometry = new HexTorusGeometry(settings.gridSize(), settings.gridSize(), settings.atomWidthBlocks());
        ClusterSizeModel model = modelOf(table, settings);

        progress.report(
            "solving the biome pattern for world seed " + worldSeed + " on " + threads + " threads (" + biomes.size() + " biomes, " + settings.gridSize() + "x" + settings.gridSize()
                + " cells, up to " + settings.maxPatternAttempts() + " attempts)"
        );
        ToroidalSizedPatternGenerator.Generated generated = ToroidalSizedPatternGenerator.generate(
            table, grid, model, worldSeed, settings.patternStepsPerAttempt(), settings.maxPatternAttempts(), settings.patternSchedule(), threads
        );
        progress.report("pattern solved at attempt " + (generated.seedIndex() + 1));

        progress.report("rendering and verifying the map, trying warp strengths " + Arrays.toString(settings.warpStrengthsStrongestFirst()) + " strongest first");
        RealizationSelector.Selection selection = RealizationSelector.select(
            table, grid, generated.pattern(), geometry, worldSeed, settings.warpStrengthsStrongestFirst(), settings.rasterSamplesPerSide(), settings.sizeToleranceOfMinimum(), biomes
        );
        progress.report("map ready: warp strength " + selection.strength() + " (" + selection.candidatesTried() + " tried)");

        byte[] raster = toByteRaster(selection.map().biomeIndices());
        return new WorldBiomePlan(
            worldSeed, fingerprintOf(catalog), settings.gridSize(), settings.gridSize(), settings.atomWidthBlocks(), selection.strength(), generated.seedIndex() + 1,
            selection.candidatesTried(), biomes.stream().map(BiomeId::toString).toList(), settings.rasterSamplesPerSide(), raster
        );
    }

    /** The raster stores one byte per sample, a palette index - {@code indices} is the same values as wider ints, no palette lookup here. */
    private static byte[] toByteRaster(int[] indices) {
        byte[] raster = new byte[indices.length];
        for (int sample = 0; sample < indices.length; sample++) {
            raster[sample] = (byte) indices[sample];
        }
        return raster;
    }

    /**
     * Throws {@link IllegalArgumentException}, saying why, if {@link #create} would reject {@code catalog} outright: a size range holding no whole cell, or a border requirement that
     * no cluster could ever meet. Cheap - it solves nothing - so the graph editor can run it on every edit. It cannot say whether the catalog is solvable; only solving does.
     */
    public static void requireCatalogAcceptable(BiomeGraphData catalog) {
        modelOf(RawRuleTable.from(catalog), PlanSettings.DEFAULT);
    }

    private static ClusterSizeModel modelOf(RawRuleTable table, PlanSettings settings) {
        return new ClusterSizeModel(table, settings.atomWidthBlocks(), new ShapeRule(settings.perimeterRatio(), Integer.MAX_VALUE, Integer.MAX_VALUE, 2));
    }

    /** Restores a saved plan exactly. Anything that does not add up (unknown format, wrong raster length, an index outside the palette) is rejected, never guessed at. */
    public static WorldBiomePlan fromSnapshot(PlanSnapshot snapshot) {
        if (snapshot.schemaVersion() != SCHEMA_VERSION) {
            throw new IllegalArgumentException("WorldBiomePlan: snapshot has format " + snapshot.schemaVersion() + " but this version reads format " + SCHEMA_VERSION);
        }
        if (snapshot.palette().isEmpty() || snapshot.palette().size() > LARGEST_PALETTE) {
            throw new IllegalArgumentException("WorldBiomePlan: snapshot palette has " + snapshot.palette().size() + " biomes, expected 1 to " + LARGEST_PALETTE);
        }
        if (snapshot.gridWidth() < 1 || snapshot.gridHeight() < 1 || snapshot.atomWidthBlocks() <= 0 || snapshot.rasterSize() < 1) {
            throw new IllegalArgumentException("WorldBiomePlan: snapshot has a non-positive grid, atom width or raster size");
        }
        if ((long) snapshot.rasterSize() * snapshot.rasterSize() != snapshot.raster().length) {
            throw new IllegalArgumentException(
                "WorldBiomePlan: snapshot raster holds " + snapshot.raster().length + " samples but " + snapshot.rasterSize() + "x" + snapshot.rasterSize() + " were expected"
            );
        }
        for (byte index : snapshot.raster()) {
            if ((index & 0xFF) >= snapshot.palette().size()) {
                throw new IllegalArgumentException("WorldBiomePlan: snapshot raster refers to palette entry " + (index & 0xFF) + " but the palette has " + snapshot.palette().size());
            }
        }
        return new WorldBiomePlan(
            snapshot.worldSeed(), snapshot.catalogFingerprint(), snapshot.gridWidth(), snapshot.gridHeight(), snapshot.atomWidthBlocks(), snapshot.warpStrength(),
            snapshot.patternAttempts(), snapshot.strengthsTried(), snapshot.palette(), snapshot.rasterSize(), snapshot.raster().clone()
        );
    }

    public PlanSnapshot toSnapshot() {
        return new PlanSnapshot(
            SCHEMA_VERSION, worldSeed, catalogFingerprint, gridWidth, gridHeight, atomWidthBlocks, warpStrength, patternAttempts, strengthsTried, paletteNames, rasterSize, raster.clone()
        );
    }

    /** The biome at world position (x, z), in blocks. */
    public BiomeId biomeAt(double x, double z) {
        double[] tile = tileCoordinates(x, z);
        int column = Math.min(rasterSize - 1, (int) (tile[0] * rasterSize));
        int row = Math.min(rasterSize - 1, (int) (tile[1] * rasterSize));
        return palette.get(raster[row * rasterSize + column] & 0xFF);
    }

    /**
     * The nearest position, in whole blocks, whose biome is {@code biome}, or empty if the map
     * has no such biome. Exact: every biome occurs in every tile, so an answer always exists within
     * about one tile.
     */
    public Optional<BlockPosition> nearest(BiomeId biome, double x, double z) {
        int paletteIndex = paletteNames.indexOf(biome.toString());
        if (paletteIndex < 0) {
            return Optional.empty();
        }
        double ax = geometry.tileVectorAX();
        double ay = geometry.tileVectorAY();
        double bx = geometry.tileVectorBX();
        double by = geometry.tileVectorBY();
        double[] origin = tileCoordinates(x, z);
        double bestSquared = Double.MAX_VALUE;
        double bestX = 0;
        double bestZ = 0;
        for (int row = 0; row < rasterSize; row++) {
            double v = (row + 0.5) / rasterSize - origin[1];
            v -= Math.rint(v);
            for (int column = 0; column < rasterSize; column++) {
                if ((raster[row * rasterSize + column] & 0xFF) != paletteIndex) {
                    continue;
                }
                double u = (column + 0.5) / rasterSize - origin[0];
                u -= Math.rint(u);
                WrappedOffset offset = closestWrappedOffset(u, v, ax, ay, bx, by);
                if (offset.distanceSquared() < bestSquared) {
                    bestSquared = offset.distanceSquared();
                    bestX = offset.x();
                    bestZ = offset.z();
                }
            }
        }
        return Optional.of(new BlockPosition((int) Math.round(x + bestX), (int) Math.round(z + bestZ)));
    }

    /** The closest a sample at tile-fraction {@code (u, v)} (already centred on the query point) can get across the 9 ways the tile wraps, and its squared distance in blocks. */
    private record WrappedOffset(double x, double z, double distanceSquared) {
    }

    private static WrappedOffset closestWrappedOffset(double u, double v, double ax, double ay, double bx, double by) {
        double bestX = 0;
        double bestZ = 0;
        double bestSquared = Double.MAX_VALUE;
        for (int shiftU = -1; shiftU <= 1; shiftU++) {
            for (int shiftV = -1; shiftV <= 1; shiftV++) {
                double offsetX = (u + shiftU) * ax + (v + shiftV) * bx;
                double offsetZ = (u + shiftU) * ay + (v + shiftV) * by;
                double squared = offsetX * offsetX + offsetZ * offsetZ;
                if (squared < bestSquared) {
                    bestSquared = squared;
                    bestX = offsetX;
                    bestZ = offsetZ;
                }
            }
        }
        return new WrappedOffset(bestX, bestZ, bestSquared);
    }

    /** The biomes of the catalog this plan was made from, in the order the palette uses. */
    public List<BiomeId> biomes() {
        return palette;
    }

    public long worldSeed() {
        return worldSeed;
    }

    /** Hash of the catalog the plan was made from; differs if the catalog has been edited since. */
    public String catalogFingerprint() {
        return catalogFingerprint;
    }

    /** How hard the borders were bent (0 = not at all). */
    public double warpStrength() {
        return warpStrength;
    }

    /** Which attempt of the world seed's sequence solved the pattern (1 = the first). */
    public int patternAttempts() {
        return patternAttempts;
    }

    public int strengthsTried() {
        return strengthsTried;
    }

    /** Length in blocks of one side of the repeating tile. */
    public double tileSideBlocks() {
        return Math.sqrt(geometry.tileVectorAX() * geometry.tileVectorAX() + geometry.tileVectorAY() * geometry.tileVectorAY());
    }

    double tileVectorAX() {
        return geometry.tileVectorAX();
    }

    double tileVectorAY() {
        return geometry.tileVectorAY();
    }

    double tileVectorBX() {
        return geometry.tileVectorBX();
    }

    double tileVectorBY() {
        return geometry.tileVectorBY();
    }

    /** Position (x, z) as fractions of the two tile vectors, wrapped into [0, 1). */
    private double[] tileCoordinates(double x, double z) {
        double ax = geometry.tileVectorAX();
        double bx = geometry.tileVectorBX();
        double by = geometry.tileVectorBY();
        double area = ax * by;
        double u = (by * x - bx * z) / area;
        double v = z / by;
        return new double[] {u - Math.floor(u), v - Math.floor(v)};
    }

    private static BiomeId biomeIdOf(String name) {
        int colon = name.indexOf(':');
        return new BiomeId(name.substring(0, colon), name.substring(colon + 1));
    }

    /** Hash of a catalog, the same value a plan made from it records: how a saved plan is checked against the catalog the game is running now. */
    public static String fingerprintOf(BiomeGraphData catalog) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(BiomeGraphJson.toJson(catalog).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("WorldBiomePlan: SHA-256 is required by every Java runtime but is missing", impossible);
        }
    }
}
