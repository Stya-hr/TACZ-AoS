package tacz.longrangesniping.wind;

/** Horizontal air velocity in blocks/second. Arrows indicate where wind pushes, not where it comes from. */
public record WindVector(double x, double z) {
    public static final WindVector ZERO = new WindVector(0, 0);
    public WindVector {
        if (x == 0) x = 0;
        if (z == 0) z = 0;
        if (!Double.isFinite(x) || !Double.isFinite(z)) throw new IllegalArgumentException("Non-finite wind");
    }
    public double speed() { return Math.hypot(x, z); }
    public double signedCrosswind(double directionX, double directionZ) {
        double length = Math.hypot(directionX, directionZ);
        return length < 1e-8 ? 0 : (-directionZ * x + directionX * z) / length;
    }
    public WindVector perpendicular(double directionX, double directionZ) {
        double length = Math.hypot(directionX, directionZ);
        if (length < 1e-8) return ZERO;
        double cross = signedCrosswind(directionX, directionZ);
        return new WindVector(-directionZ / length * cross, directionX / length * cross);
    }
}
