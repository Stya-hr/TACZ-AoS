package tacz.aos.trails;

import java.util.ArrayList;
import java.util.List;

/** Geometry only: no world requests, and never joins two visible runs across an untracked chunk. */
public final class TrailAudience {
    public static final double HEARING_RADIUS = 5;
    @FunctionalInterface public interface Chunks { boolean contains(int x, int z); }
    public record Pass(double x, double y, double z, double distance) {
        public float volume() { return flybyVolume(distance); }
    }
    /** Unity gain at the path, fading to silence at five blocks. Never amplify the source audio. */
    public static float flybyVolume(double distance) {
        return (float) Math.clamp(1 - distance / HEARING_RADIUS, 0, 1);
    }
    private TrailAudience() {}

    public static List<TrailSegment> clip(TrailSegment s, Chunks chunks) {
        double dx = s.x1() - s.x0(), dz = s.z1() - s.z0();
        int x = (int) Math.floor(s.x0() / 16), z = (int) Math.floor(s.z0() / 16);
        int ix = dx > 0 ? 1 : dx < 0 ? -1 : 0, iz = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        double tx = boundary(s.x0(), dx, x), tz = boundary(s.z0(), dz, z), enter = 0, run = -1;
        var result = new ArrayList<TrailSegment>();
        for (int visited = 0; visited < 64; visited++) {
            double leave = Math.min(1, Math.min(tx, tz));
            if (leave > enter) {
                if (chunks.contains(x, z)) { if (run < 0) run = enter; }
                else if (run >= 0) { result.add(slice(s, run, enter)); run = -1; }
            }
            if (leave >= 1) { if (run >= 0) result.add(slice(s, run, 1)); break; }
            if (tx <= leave) { x += ix; tx += 16 / Math.abs(dx); }
            if (tz <= leave) { z += iz; tz += 16 / Math.abs(dz); }
            enter = leave;
        }
        return result;
    }

    private static double boundary(double start, double delta, int cell) {
        return delta == 0 ? Double.POSITIVE_INFINITY : ((cell + (delta > 0 ? 1.0 : 0)) * 16 - start) / delta;
    }
    private static TrailSegment slice(TrailSegment s, double a, double b) {
        return new TrailSegment(s.shot(), s.step(), lerp(s.x0(), s.x1(), a), lerp(s.y0(), s.y1(), a), lerp(s.z0(), s.z1(), a),
                lerp(s.x0(), s.x1(), b), lerp(s.y0(), s.y1(), b), lerp(s.z0(), s.z1(), b), s.speed(), s.terminal() && b == 1);
    }
    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }

    /** Wait until closest approach is reached, rather than playing at the first, quieter radius crossing. */
    public static Pass nearMiss(TrailSegment s, double x, double y, double z) {
        double dx = s.x1()-s.x0(), dy = s.y1()-s.y0(), dz = s.z1()-s.z0();
        double length = dx*dx + dy*dy + dz*dz;
        if (length < 1.0e-12 || s.speed() < 1) return null;
        double t = ((x-s.x0())*dx + (y-s.y0())*dy + (z-s.z0())*dz) / length;
        if (t > 1 && !s.terminal()) return null;
        t = Math.clamp(t, 0, 1);
        double px = lerp(s.x0(), s.x1(), t), py = lerp(s.y0(), s.y1(), t), pz = lerp(s.z0(), s.z1(), t);
        double distance = Math.sqrt((px-x)*(px-x)+(py-y)*(py-y)+(pz-z)*(pz-z));
        return distance < HEARING_RADIUS ? new Pass(px, py, pz, distance) : null;
    }
}
