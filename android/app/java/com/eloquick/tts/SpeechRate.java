package com.eloquick.tts;

/** Android's speech rate turned into one of the engine's speed numbers.
 *
 * The engine's 0-250 speed is nothing like linear in how fast it speaks
 * (measured: speed 100 speaks 2.7x the speed-50 rate, 200 speaks 16.6x),
 * so a percentage must go through the measured curve, not multiply.
 * Table and method from trypsynth/evvdroid's SpeechRate (MIT, Quin
 * Gillespie); one table serves every voice (speed alone decides duration).
 */
public final class SpeechRate {
    private SpeechRate() {}

    private static final int[] SPEED = {
        0, 5, 10, 20, 30, 40, 50, 60, 70, 85, 100, 125, 150, 175, 200, 225, 250};
    private static final double[] TIMES = {
        0.400, 0.416, 0.450, 0.552, 0.675, 0.823, 1.000, 1.226, 1.504,
        1.976, 2.740, 4.383, 6.986, 11.801, 16.633, 20.427, 21.170};

    /** How many times the speed-50 rate {@code speed} speaks at. */
    public static double timesFor(int speed) {
        int want = Math.max(SPEED[0], Math.min(SPEED[SPEED.length - 1], speed));
        for (int i = 1; i < SPEED.length; i++) {
            if (want > SPEED[i]) continue;
            double span = (double) (SPEED[i] - SPEED[i - 1]);
            double along = (want - SPEED[i - 1]) / span;
            return TIMES[i - 1] + along * (TIMES[i] - TIMES[i - 1]);
        }
        return TIMES[TIMES.length - 1];
    }

    /** The speed number speaking {@code times} the speed-50 rate. */
    public static int speedFor(double times) {
        if (times <= TIMES[0]) return SPEED[0];
        for (int i = 1; i < TIMES.length; i++) {
            if (times > TIMES[i]) continue;
            double span = TIMES[i] - TIMES[i - 1];
            double along = span <= 0 ? 0 : (times - TIMES[i - 1]) / span;
            return Math.round((float) (SPEED[i - 1] + along * (SPEED[i] - SPEED[i - 1])));
        }
        return SPEED[SPEED.length - 1];
    }

    /** Speed number for {@code percent} of the rate {@code base} speaks at. */
    public static int speedForPercent(int base, int percent) {
        if (percent == 100) return base;
        double want = timesFor(base) * Math.max(0, percent) / 100.0;
        int out = speedFor(want);
        return Math.max(0, Math.min(Eci.SPEED_MAX, out));
    }
}
