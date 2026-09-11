package tacz.aos.trails;

/** A measured server displacement, never a client prediction or a request to access terrain. */
public record TrailSegment(int shot, int step, double x0, double y0, double z0,
                           double x1, double y1, double z1, float speed, boolean terminal) {
    public TrailSegment {
        if (step < 1 || step > 2401 || !Double.isFinite(x0 + y0 + z0 + x1 + y1 + z1)
                || Math.max(Math.max(Math.abs(x0), Math.abs(x1)), Math.max(Math.abs(z0), Math.abs(z1))) > 30_000_000
                || Math.abs(y0) > 30_000_000 || Math.abs(y1) > 30_000_000
                || !Float.isFinite(speed) || speed < 0 || speed > 256
                || Math.pow(x1-x0, 2) + Math.pow(y1-y0, 2) + Math.pow(z1-z0, 2) > 256.01 * 256.01) {
            throw new IllegalArgumentException("Invalid trail segment");
        }
    }
}
