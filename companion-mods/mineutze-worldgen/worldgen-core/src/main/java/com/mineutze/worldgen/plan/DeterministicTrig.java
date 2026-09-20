package com.mineutze.worldgen.plan;

/**
 * Sine and cosine that give the SAME bits on every JVM and CPU (2026-09-20). {@code Math.sin} and
 * {@code Math.cos} are only required to be within one unit in the last place and are allowed to
 * differ between implementations; if a world's biome map were ever re-evaluated on another machine,
 * a single border sample could flip and new chunks would seam against old ones. Since Java 17 the
 * operations {@code + - * /} and {@code sqrt} are strictly reproducible, so building the functions
 * from those alone is enough - no table, no {@code StrictMath} call in the hot loop, and faster than
 * {@code StrictMath.sin}.
 *
 * <p>Method (the classic fdlibm one): reduce the argument to {@code r} within a quarter turn of
 * zero by subtracting a whole number of quarter turns, using pi/2 split into three 33-bit pieces so
 * that each product with the count is exact; evaluate the kernel polynomial for sine or cosine on
 * {@code r}; pick sine or cosine and the sign from the quadrant. Accurate to about two units in the
 * last place near the origin, and to about {@code 3e-17 * |x|} for large arguments.
 */
final class DeterministicTrig {

    /** Beyond this the count of quarter turns no longer fits the exactness of the reduction. */
    private static final double LARGEST_ARGUMENT = 1e6;

    private static final double TWO_OVER_PI = 6.36619772367581382433e-01;
    private static final double QUARTER_TURN_FIRST = 1.57079632673412561417e+00;
    private static final double QUARTER_TURN_SECOND = 6.07710050630396597660e-11;
    private static final double QUARTER_TURN_THIRD = 2.02226624871116645580e-21;

    private static final double SINE_1 = -1.66666666666666324348e-01;
    private static final double SINE_2 = 8.33333333332248946124e-03;
    private static final double SINE_3 = -1.98412698298579493134e-04;
    private static final double SINE_4 = 2.75573137070700676789e-06;
    private static final double SINE_5 = -2.50507602534068634195e-08;
    private static final double SINE_6 = 1.58969099521155010221e-10;

    private static final double COSINE_1 = 4.16666666666666019037e-02;
    private static final double COSINE_2 = -1.38888888888741095749e-03;
    private static final double COSINE_3 = 2.48015872894767294178e-05;
    private static final double COSINE_4 = -2.75573143513906633035e-07;
    private static final double COSINE_5 = 2.08757232129817482790e-09;
    private static final double COSINE_6 = -1.13596475577881948265e-11;

    private DeterministicTrig() {
    }

    static double sin(double x) {
        double reduced = reduce(x);
        return switch ((int) (quarterTurns(x) & 3)) {
            case 0 -> sineKernel(reduced);
            case 1 -> cosineKernel(reduced);
            case 2 -> -sineKernel(reduced);
            default -> -cosineKernel(reduced);
        };
    }

    static double cos(double x) {
        double reduced = reduce(x);
        return switch ((int) (quarterTurns(x) & 3)) {
            case 0 -> cosineKernel(reduced);
            case 1 -> -sineKernel(reduced);
            case 2 -> -cosineKernel(reduced);
            default -> sineKernel(reduced);
        };
    }

    /** The nearest whole number of quarter turns to {@code x}. */
    private static long quarterTurns(double x) {
        if (!(Math.abs(x) <= LARGEST_ARGUMENT)) {
            throw new IllegalArgumentException("DeterministicTrig: argument must be finite and within +-" + LARGEST_ARGUMENT + ", got " + x);
        }
        return (long) Math.floor(x * TWO_OVER_PI + 0.5);
    }

    /** {@code x} minus its nearest whole number of quarter turns: a value in about [-pi/4, pi/4]. */
    private static double reduce(double x) {
        double count = quarterTurns(x);
        return ((x - count * QUARTER_TURN_FIRST) - count * QUARTER_TURN_SECOND) - count * QUARTER_TURN_THIRD;
    }

    private static double sineKernel(double r) {
        double squared = r * r;
        double polynomial = SINE_2 + squared * (SINE_3 + squared * (SINE_4 + squared * (SINE_5 + squared * SINE_6)));
        return r + r * squared * (SINE_1 + squared * polynomial);
    }

    private static double cosineKernel(double r) {
        double squared = r * r;
        double polynomial = COSINE_1 + squared * (COSINE_2 + squared * (COSINE_3 + squared * (COSINE_4 + squared * (COSINE_5 + squared * COSINE_6))));
        return 1.0 - 0.5 * squared + squared * squared * polynomial;
    }
}
