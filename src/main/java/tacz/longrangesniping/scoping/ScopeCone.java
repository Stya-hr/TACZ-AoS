package tacz.longrangesniping.scoping;

/** Shared server/client aim geometry. The server uses the player's own rotation, never client-supplied targets. */
public final class ScopeCone {
    private ScopeCone() {}
    public static double halfAngle(double zoom) { return Math.toRadians(Math.clamp(35.0 / zoom, 0.5, 6.0)); }
    public static double radius(double width, double height, double depth) {
        return Math.sqrt(width * width + height * height + depth * depth) / 2;
    }

    public static boolean contains(double dx, double dy, double dz, double vx, double vy, double vz,
                                   double radius, double distance, double zoom) {
        double squared = dx * dx + dy * dy + dz * dz;
        double along = dx * vx + dy * vy + dz * vz;
        if (!Double.isFinite(squared + along + radius + distance + zoom) || zoom <= 0 || distance <= 0 || radius < 0
                || along <= 0 || squared > distance * distance) return false;
        double width = along * Math.tan(halfAngle(zoom)) + radius;
        return Math.max(0, squared - along * along) <= width * width;
    }
}
