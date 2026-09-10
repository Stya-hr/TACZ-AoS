package tacz.longrangesniping.scoping;

/** Shared server/client aim geometry. The server uses the player's own rotation, never client-supplied targets. */
public final class ScopeCone {
    private ScopeCone() {}
    public static final double ACQUIRE_PADDING = 1.15, RELEASE_PADDING = 1.35;
    private static final double MAX_ANGLE = Math.toRadians(30);
    /** Conservative startup view until the client supplies its actual world projection. */
    public static double halfAngle(double zoom) {
        return Math.min(MAX_ANGLE, Math.atan(Math.hypot(1, 16.0 / 9) / zoom));
    }
    public static double maximumHalfAngle(double zoom) {
        return Math.min(MAX_ANGLE, Math.atan(Math.tan(Math.toRadians(55)) * Math.hypot(1, 4) / zoom));
    }
    public static double padded(double angle, double padding) {
        return Math.min(MAX_ANGLE, Math.atan(Math.tan(angle) * padding));
    }
    /** The entire viewport encloses the lens, including unusual aspect ratios and scope models. */
    public static double projectionHalfAngle(double horizontalScale, double verticalScale) {
        if (!Double.isFinite(horizontalScale + verticalScale) || horizontalScale <= 0 || verticalScale <= 0) return Double.NaN;
        return Math.atan(Math.hypot(1 / horizontalScale, 1 / verticalScale));
    }
    public static double radius(double width, double height, double depth) {
        return Math.sqrt(width * width + height * height + depth * depth) / 2;
    }

    public static boolean contains(double dx, double dy, double dz, double vx, double vy, double vz,
                                   double radius, double distance, double zoom) {
        if (!Double.isFinite(zoom) || zoom <= 0) return false;
        return containsAtAngle(dx, dy, dz, vx, vy, vz, radius, distance, halfAngle(zoom));
    }
    public static boolean containsAtAngle(double dx, double dy, double dz, double vx, double vy, double vz,
                                          double radius, double distance, double angle) {
        double squared = dx * dx + dy * dy + dz * dz;
        double along = dx * vx + dy * vy + dz * vz;
        if (!Double.isFinite(squared + along + radius + distance + angle) || angle <= 0 || angle >= Math.PI / 2 || distance <= 0 || radius < 0
                || along <= 0 || squared > distance * distance) return false;
        double width = along * Math.tan(angle) + radius;
        return Math.max(0, squared - along * along) <= width * width;
    }
}
