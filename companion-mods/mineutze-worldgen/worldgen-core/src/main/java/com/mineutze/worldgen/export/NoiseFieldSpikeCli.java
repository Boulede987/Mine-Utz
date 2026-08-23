package com.mineutze.worldgen.export;

import com.mineutze.worldgen.regionsolve.field.AdaptiveDomainWarp;
import com.mineutze.worldgen.regionsolve.field.AdaptiveWarpTuning;
import com.mineutze.worldgen.regionsolve.field.CoherentNoise2D;
import com.mineutze.worldgen.regionsolve.field.DomainWarp;
import com.mineutze.worldgen.regionsolve.field.SafetyField;
import com.mineutze.worldgen.regionsolve.field.Warp2D;
import com.mineutze.worldgen.regionsolve.field.WarpTuning;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

/**
 * Step-0 spike answering exactly one question: does a domain-warped
 * additively-weighted-Voronoi field produce organic (non-straight) biome
 * patch boundaries, sampled straight at raster resolution — instead of the
 * straight-edged failure BOTH deleted prior attempts hit (mesh
 * triangle-merge's {@code EdgeDisplacement}, and the forgotten {@code
 * shape/} package's Voronoi-cell + {@code PatchNegotiator}, see
 * doc/DESIGN.md history)? Deliberately no mesh, no CSP, no real
 * {@code BiomeGraph} — a handful of hardcoded seed points/radii/colors,
 * rendered straight to PNG for visual judgement, comparing the flat-safe
 * {@link WarpTuning#RECOMMENDED_STRONG} against {@link AdaptiveDomainWarp}'s
 * {@link AdaptiveWarpTuning#RECOMMENDED_ADAPTIVE} (a modest but real ~20%
 * amplitude gain over the flat floor, plus better texture at this frequency
 * than the flat tuning alone — see that constant's own doc for why bigger
 * gains weren't achievable without a repair pass).
 *
 * <p>Winner-per-pixel is the additively-weighted-Voronoi rule (nearest
 * center MINUS its own radius) — this ties the field's "size" lever
 * (bigger radius claims more area) to the same shape mechanism producing
 * the boundary, previewing how a real triangle's target area would plug in
 * once this graduates past spike stage.
 *
 * <p><b>NOT wired into any real pipeline.</b> Delete this once the organic-
 * edge question is answered, or promote its warp logic into the real field
 * assignment once the mesh/CSP layers are built around it.
 *
 * <p>Usage: outputDir (optional, defaults to {@code field-spike})
 */
public final class NoiseFieldSpikeCli {

    private record Seed(double x, double z, double radius, int rgb, String label) {
    }

    // A 4-way junction with deliberately unequal radii (roughly matching doc/DESIGN.md's
    // own target widths - desert 700-1000 blocks, others narrower), on a real region-scale
    // canvas (MacroGridConfig.DEFAULT's region is 2048 blocks/side) - a small few-hundred-
    // block canvas measurably under-represents what a given tuning actually looks like at
    // the scale it'll really run at (confirmed directly earlier this session with a lower-
    // frequency candidate whose wobble was barely visible at 500x500 but obviously strong
    // at 2048x2048).
    private static final List<Seed> SEEDS = List.of(
        new Seed(600, 600, 450, 0xE0A030, "desert"),
        new Seed(1500, 500, 300, 0x4C9A2A, "forest"),
        new Seed(950, 1500, 320, 0xB5B5B5, "badlands"),
        new Seed(1650, 1600, 280, 0x3070B0, "ocean")
    );

    private record RenderCase(String label, Function<Long, Warp2D> buildWarp) {
    }

    private static final List<RenderCase> CASES = List.of(
        new RenderCase("00_none", worldSeed -> (x, z) -> new double[]{x, z}),
        new RenderCase("recommended_flat", NoiseFieldSpikeCli::flatWarp),
        new RenderCase("recommended_adaptive", NoiseFieldSpikeCli::adaptiveWarp)
    );

    private static final int CANVAS_SIZE = 2048;
    private static final long WORLD_SEED = 42L;

    private NoiseFieldSpikeCli() {
    }

    public static void main(String[] args) {
        Path outputDir = Path.of(args.length > 0 ? args[0] : "field-spike");
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create output directory " + outputDir, e);
        }

        for (RenderCase renderCase : CASES) {
            Warp2D warp = renderCase.buildWarp().apply(WORLD_SEED);
            BufferedImage image = render(warp);
            Path file = outputDir.resolve("warp_" + renderCase.label() + ".png");
            try {
                ImageIO.write(image, "png", file.toFile());
            } catch (IOException e) {
                throw new UncheckedIOException("Failed writing " + file, e);
            }
            System.out.println("wrote " + file.toAbsolutePath());
        }
    }

    private static Warp2D flatWarp(long worldSeed) {
        CoherentNoise2D noiseX = new CoherentNoise2D(worldSeed ^ 0x1234_5678_9ABC_DEF0L);
        CoherentNoise2D noiseZ = new CoherentNoise2D(worldSeed ^ 0x0FED_CBA9_8765_4321L);
        WarpTuning tuning = WarpTuning.RECOMMENDED_STRONG;
        return new DomainWarp(noiseX, noiseZ, tuning.amplitude(), tuning.frequency(), tuning.octaves());
    }

    private static Warp2D adaptiveWarp(long worldSeed) {
        CoherentNoise2D noiseX = new CoherentNoise2D(worldSeed ^ 0x1234_5678_9ABC_DEF0L);
        CoherentNoise2D noiseZ = new CoherentNoise2D(worldSeed ^ 0x0FED_CBA9_8765_4321L);
        AdaptiveWarpTuning tuning = AdaptiveWarpTuning.RECOMMENDED_ADAPTIVE;

        DomainWarp riskReference = new DomainWarp(noiseX, noiseZ, tuning.strongAmplitude(), tuning.frequency(), tuning.octaves());
        double margin = tuning.dilateRadiusCells() * tuning.latticeSpacing() + tuning.latticeSpacing();
        SafetyField safetyField = new SafetyField(
            riskReference, -margin, CANVAS_SIZE + margin, tuning.latticeSpacing(),
            tuning.dangerDeterminant(), tuning.comfortableDeterminant(), tuning.dilateRadiusCells()
        );
        return new AdaptiveDomainWarp(noiseX, noiseZ, tuning.floorAmplitude(), tuning.strongAmplitude(), tuning.frequency(), tuning.octaves(), safetyField);
    }

    private static BufferedImage render(Warp2D warp) {
        BufferedImage image = new BufferedImage(CANVAS_SIZE, CANVAS_SIZE, BufferedImage.TYPE_INT_RGB);
        for (int pixelX = 0; pixelX < CANVAS_SIZE; pixelX++) {
            for (int pixelZ = 0; pixelZ < CANVAS_SIZE; pixelZ++) {
                double[] warped = warp.warp(pixelX, pixelZ);
                image.setRGB(pixelX, pixelZ, winner(warped[0], warped[1]).rgb());
            }
        }
        drawSeedMarkers(image);
        return image;
    }

    private static Seed winner(double x, double z) {
        Seed best = null;
        double bestScore = Double.MAX_VALUE;
        for (Seed seed : SEEDS) {
            double dx = x - seed.x();
            double dz = z - seed.z();
            double score = Math.sqrt(dx * dx + dz * dz) - seed.radius();
            if (score < bestScore) {
                bestScore = score;
                best = seed;
            }
        }
        return best;
    }

    /** Black marker at each seed's true (unwarped) center, so the sweep makes clear how far the boundary strays from the plain-distance answer. */
    private static void drawSeedMarkers(BufferedImage image) {
        int markerRadius = CANVAS_SIZE / 200;
        for (Seed seed : SEEDS) {
            for (int dx = -markerRadius; dx <= markerRadius; dx++) {
                for (int dz = -markerRadius; dz <= markerRadius; dz++) {
                    int pixelX = (int) seed.x() + dx;
                    int pixelZ = (int) seed.z() + dz;
                    if (pixelX >= 0 && pixelX < CANVAS_SIZE && pixelZ >= 0 && pixelZ < CANVAS_SIZE) {
                        image.setRGB(pixelX, pixelZ, 0x000000);
                    }
                }
            }
        }
    }
}
