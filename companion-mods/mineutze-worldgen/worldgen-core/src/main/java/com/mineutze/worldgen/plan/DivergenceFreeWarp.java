package com.mineutze.worldgen.plan;

import java.util.Random;

/**
 * A smooth, tileable displacement of the plane that neither stretches nor squeezes (2026-09-20).
 * The pattern's cells are hexagons; sampling the pattern at {@code P + displacement(P)} instead of
 * {@code P} bends every border without touching which biomes touch and, because the field has zero
 * divergence, without changing the area any region covers (to first order - the residual is
 * second order in the field's slope, measured by {@link #jacobianDeterminant}).
 *
 * <p>The field is a sum of plane waves {@code a * n * sin(k.P + phase)} with {@code n} perpendicular
 * to {@code k}: divergence is {@code (n.k) * a * cos(...) = 0} for every wave, exactly. Wave
 * vectors are snapped to the tile's reciprocal lattice so every wave repeats after each tile
 * vector - the map has no seam. Wavelengths are spread evenly on a log scale between the shortest
 * and longest requested, so both large bends and fine roughness are present; the amplitude of a
 * wave of wavelength {@code L} is {@code displacementAtLongest * (L / longest)^roughness}
 * (roughness 1 gives every wave the same slope, lower values put relatively more into fine detail).
 *
 * <p><b>Two ways to apply the field</b>: with {@code flowSteps == 0} a point is moved ONCE by the
 * field's value - cheap, but where the field's slope is steep the map folds onto itself and
 * regions fragment (measured: at 35 blocks per wave, 14 of 254 clusters split). With {@code
 * flowSteps >= 1} the point is instead carried along the field for one unit of time (fourth-order
 * Runge-Kutta, that many steps): the flow of a divergence-free field is EXACTLY area-preserving and
 * can never fold, whatever the strength, so borders stay borders and regions keep their area. The
 * price is that a strong flow can stretch a region into a thin filament.
 */
final class DivergenceFreeWarp {

    /** {@code displacementAtLongestWavelengthBlocks} is the amplitude of a single wave at the longest wavelength. */
    record Settings(
        int waveCount, double shortestWavelengthBlocks, double longestWavelengthBlocks, double displacementAtLongestWavelengthBlocks, double roughness, int flowSteps
    ) {
        /** Settings for the simple one-step displacement. */
        Settings(int waveCount, double shortestWavelengthBlocks, double longestWavelengthBlocks, double displacementAtLongestWavelengthBlocks, double roughness) {
            this(waveCount, shortestWavelengthBlocks, longestWavelengthBlocks, displacementAtLongestWavelengthBlocks, roughness, 0);
        }

        Settings {
            if (flowSteps < 0) {
                throw new IllegalArgumentException("DivergenceFreeWarp.Settings: flowSteps must not be negative (0 = one-step displacement), got " + flowSteps);
            }
            if (waveCount < 1) {
                throw new IllegalArgumentException("DivergenceFreeWarp.Settings: waveCount must be at least 1, got " + waveCount);
            }
            if (shortestWavelengthBlocks <= 0 || longestWavelengthBlocks < shortestWavelengthBlocks) {
                throw new IllegalArgumentException(
                    "DivergenceFreeWarp.Settings: wavelengths must satisfy 0 < shortest <= longest, got " + shortestWavelengthBlocks + " and " + longestWavelengthBlocks
                );
            }
            if (displacementAtLongestWavelengthBlocks < 0) {
                throw new IllegalArgumentException("DivergenceFreeWarp.Settings: displacement must not be negative, got " + displacementAtLongestWavelengthBlocks);
            }
            if (roughness < 0) {
                throw new IllegalArgumentException("DivergenceFreeWarp.Settings: roughness must not be negative, got " + roughness);
            }
        }
    }

    private final double[] waveNumberX;
    private final double[] waveNumberY;
    private final double[] directionX;
    private final double[] directionY;
    private final double[] amplitude;
    private final double[] phase;
    private final double sumOfAmplitudes;
    private final int flowSteps;

    DivergenceFreeWarp(HexTorusGeometry geometry, long seed, Settings settings) {
        int count = settings.waveCount();
        this.flowSteps = settings.flowSteps();
        this.waveNumberX = new double[count];
        this.waveNumberY = new double[count];
        this.directionX = new double[count];
        this.directionY = new double[count];
        this.amplitude = new double[count];
        this.phase = new double[count];

        Random random = new Random(seed);
        double logShortest = StrictMath.log(settings.shortestWavelengthBlocks());
        double logLongest = StrictMath.log(settings.longestWavelengthBlocks());
        double sum = 0;
        for (int wave = 0; wave < count; wave++) {
            sum += initializeWave(geometry, random, logShortest, logLongest, settings, wave);
        }
        this.sumOfAmplitudes = sum;
    }

    /** Draws and stores wave {@code wave}'s number, direction, amplitude and phase; returns the amplitude, for the caller's running sum. */
    private double initializeWave(HexTorusGeometry geometry, Random random, double logShortest, double logLongest, Settings settings, int wave) {
        double[] snapped = drawTileWaveNumber(geometry, random, logShortest, logLongest);
        double waveNumber = StrictMath.hypot(snapped[0], snapped[1]);
        waveNumberX[wave] = snapped[0];
        waveNumberY[wave] = snapped[1];
        directionX[wave] = -snapped[1] / waveNumber;
        directionY[wave] = snapped[0] / waveNumber;
        double wavelength = 2 * Math.PI / waveNumber;
        amplitude[wave] = settings.displacementAtLongestWavelengthBlocks() * StrictMath.pow(wavelength / settings.longestWavelengthBlocks(), settings.roughness());
        phase[wave] = random.nextDouble() * 2 * Math.PI;
        return amplitude[wave];
    }

    private DivergenceFreeWarp() {
        this.waveNumberX = new double[0];
        this.waveNumberY = new double[0];
        this.directionX = new double[0];
        this.directionY = new double[0];
        this.amplitude = new double[0];
        this.phase = new double[0];
        this.sumOfAmplitudes = 0;
        this.flowSteps = 0;
    }

    /** A warp that displaces nothing: the raw hexagon pattern. */
    static DivergenceFreeWarp none(HexTorusGeometry geometry) {
        return new DivergenceFreeWarp();
    }

    /**
     * A random direction and log-uniform wavelength, snapped to the nearest wave the tile can hold:
     * a wave repeats after each tile vector only if {@code k.A} and {@code k.B} are whole multiples
     * of 2 pi, so the integers {@code (k.A, k.B) / 2 pi} are rounded and the wave number rebuilt.
     */
    private static double[] drawTileWaveNumber(HexTorusGeometry geometry, Random random, double logShortest, double logLongest) {
        double ax = geometry.tileVectorAX();
        double ay = geometry.tileVectorAY();
        double bx = geometry.tileVectorBX();
        double by = geometry.tileVectorBY();
        double determinant = ax * by - ay * bx;
        double[] snapped = null;
        while (snapped == null) {
            snapped = tryDrawValidWaveNumber(random, logShortest, logLongest, ax, ay, bx, by, determinant);
        }
        return snapped;
    }

    /** One attempt: a random direction and wavelength, snapped to the tile lattice; null if that snaps to the zero wave (no wave at all), for the caller to retry. */
    private static double[] tryDrawValidWaveNumber(Random random, double logShortest, double logLongest, double ax, double ay, double bx, double by, double determinant) {
        double wavelength = StrictMath.exp(logShortest + random.nextDouble() * (logLongest - logShortest));
        double angle = random.nextDouble() * 2 * Math.PI;
        double kx = 2 * Math.PI / wavelength * StrictMath.cos(angle);
        double ky = 2 * Math.PI / wavelength * StrictMath.sin(angle);
        long alongA = Math.round((kx * ax + ky * ay) / (2 * Math.PI));
        long alongB = Math.round((kx * bx + ky * by) / (2 * Math.PI));
        if (alongA == 0 && alongB == 0) {
            return null;
        }
        double snappedX = 2 * Math.PI * (alongA * by - alongB * ay) / determinant;
        double snappedY = 2 * Math.PI * (alongB * ax - alongA * bx) / determinant;
        return new double[] {snappedX, snappedY};
    }

    int waveCount() {
        return amplitude.length;
    }

    /** Upper bound on the displacement length anywhere. */
    double sumOfAmplitudes() {
        return sumOfAmplitudes;
    }

    /** Writes the displacement at plane point (x, y) to {@code out[0]}, {@code out[1]}: the field's value, or - in flow mode - how far the flow carries the point. */
    void displace(double x, double y, double[] out) {
        if (flowSteps == 0) {
            fieldAt(x, y, out);
            return;
        }
        double stepLength = 1.0 / flowSteps;
        double[] point = {x, y};
        double[] slope = new double[2];
        for (int step = 0; step < flowSteps; step++) {
            rk4Step(stepLength, point, slope);
        }
        out[0] = point[0] - x;
        out[1] = point[1] - y;
    }

    /** One fourth-order Runge-Kutta step of the field's flow, advancing {@code point} in place; {@code slope} is a scratch buffer reused across stages. */
    private void rk4Step(double stepLength, double[] point, double[] slope) {
        double pointX = point[0];
        double pointY = point[1];
        fieldAt(pointX, pointY, slope);
        double firstX = slope[0];
        double firstY = slope[1];
        fieldAt(pointX + 0.5 * stepLength * firstX, pointY + 0.5 * stepLength * firstY, slope);
        double secondX = slope[0];
        double secondY = slope[1];
        fieldAt(pointX + 0.5 * stepLength * secondX, pointY + 0.5 * stepLength * secondY, slope);
        double thirdX = slope[0];
        double thirdY = slope[1];
        fieldAt(pointX + stepLength * thirdX, pointY + stepLength * thirdY, slope);
        point[0] = pointX + stepLength / 6 * (firstX + 2 * secondX + 2 * thirdX + slope[0]);
        point[1] = pointY + stepLength / 6 * (firstY + 2 * secondY + 2 * thirdY + slope[1]);
    }

    /** The field itself: sum of the waves at (x, y). */
    private void fieldAt(double x, double y, double[] out) {
        out[0] = 0;
        out[1] = 0;
        for (int wave = 0; wave < amplitude.length; wave++) {
            accumulateWaveContribution(x, y, wave, out);
        }
    }

    private void accumulateWaveContribution(double x, double y, int wave, double[] out) {
        double value = amplitude[wave] * DeterministicTrig.sin(waveNumberX[wave] * x + waveNumberY[wave] * y + phase[wave]);
        out[0] += value * directionX[wave];
        out[1] += value * directionY[wave];
    }

    /**
     * Determinant of {@code I + gradient(field)}: the local area scale of the ONE-STEP sampling map
     * (flow mode 0). 1 means no change; near 0 the map is about to fold onto itself; negative means
     * it has folded. In flow mode the map's area scale is exactly 1 and this does not describe it.
     */
    double jacobianDeterminant(double x, double y) {
        double[] gradient = new double[4];
        for (int wave = 0; wave < amplitude.length; wave++) {
            accumulateWaveGradient(x, y, wave, gradient);
        }
        return (1 + gradient[0]) * (1 + gradient[3]) - gradient[1] * gradient[2];
    }

    /** Adds wave {@code wave}'s contribution to the field's Jacobian into {@code gradient} ({@code [dxdx, dxdy, dydx, dydy]}). */
    private void accumulateWaveGradient(double x, double y, int wave, double[] gradient) {
        double slope = amplitude[wave] * DeterministicTrig.cos(waveNumberX[wave] * x + waveNumberY[wave] * y + phase[wave]);
        gradient[0] += slope * directionX[wave] * waveNumberX[wave];
        gradient[1] += slope * directionX[wave] * waveNumberY[wave];
        gradient[2] += slope * directionY[wave] * waveNumberX[wave];
        gradient[3] += slope * directionY[wave] * waveNumberY[wave];
    }
}
