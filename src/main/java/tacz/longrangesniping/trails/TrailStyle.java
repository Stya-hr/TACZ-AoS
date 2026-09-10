package tacz.longrangesniping.trails;

/** Visibility-first tuning. Times in simulation ticks; no speed-based shortening during this verification stage. */
public final class TrailStyle {
    private TrailStyle() {}
    public static final int LIFETIME_TICKS = 50;
    public static double lifetime(double speed) { return LIFETIME_TICKS; }
    public static double halfWidth(double distance, double worldPerPixelPerBlock) {
        double pixel = distance * worldPerPixelPerBlock;
        return 1.82 * Math.clamp(0.006, pixel * 1.25, pixel * 1.5);
    }
    public static double smokeSpread(double age) {
        return 1 + 0.8 * Math.clamp(age / LIFETIME_TICKS, 0, 1);
    }
    public static double smokeOffset(double phase, double age) {
        double maturity = Math.clamp(age / LIFETIME_TICKS, 0, 1);
        return Math.sin(phase * 2.3 + age * 0.12) * maturity * 0.6;
    }
    public static double smokeWidth(double phase, double age) {
        return smokeSpread(age) * (0.90 + Math.sin(phase * 3.5) * 0.16);
    }
    public static float alpha(double age, double speed) {
        // Hold full density for 0.2 seconds, then smoothly fade over the remaining 2.3 seconds.
        double fade = Math.clamp((age - 4) / (lifetime(speed) - 4), 0, 1);
        return (float) (1 - fade * fade * (3 - 2 * fade));
    }
}
