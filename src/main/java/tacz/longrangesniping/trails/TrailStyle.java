package tacz.longrangesniping.trails;

/** Visibility-first tuning. Times in simulation ticks; no speed-based shortening during this verification stage. */
public final class TrailStyle {
    private TrailStyle() {}
    public static final int LIFETIME_TICKS = 16;
    public static double lifetime(double speed) { return LIFETIME_TICKS; }
    public static double halfWidth(double distance, double worldPerPixelPerBlock) {
        double pixel = distance * worldPerPixelPerBlock;
        return Math.clamp(0.006, pixel * 1.25, pixel * 1.5);
    }
    public static float alpha(double age, double speed) {
        // Hold full density for 0.2 seconds, then smoothly fade over the remaining 0.6 seconds.
        double fade = Math.clamp((age - 4) / (lifetime(speed) - 4), 0, 1);
        return (float) (1 - fade * fade * (3 - 2 * fade));
    }
}
