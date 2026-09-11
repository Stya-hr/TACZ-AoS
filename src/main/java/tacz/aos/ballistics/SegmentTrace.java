package tacz.aos.ballistics;

import tacz.aos.terrain.CollisionCell;

/** Bounded voxel traversal of one actual movement segment; independent of Minecraft/world access. */
public final class SegmentTrace {
    public enum End { CLEAR, BLOCK, UNKNOWN, INVALID }
    public record Result(End end, double fraction) {}
    /** Null means unavailable geometry; selected-core callers provide air outside their regions. */
    @FunctionalInterface public interface Source { CollisionCell cell(int x, int y, int z); }
    private SegmentTrace() {}

    public static Result trace(Source source, double sx, double sy, double sz, double dx, double dy, double dz) {
        if (!Double.isFinite(sx + sy + sz + dx + dy + dz)
                || Math.abs(sx) > 30_000_000 || Math.abs(sz) > 30_000_000 || Math.abs(sy) > 30_000_000
                || dx * dx + dy * dy + dz * dz > 256 * 256) return new Result(End.INVALID, 0);
        int x = (int) Math.floor(sx), y = (int) Math.floor(sy), z = (int) Math.floor(sz);
        int ix = Double.compare(dx, 0), iy = Double.compare(dy, 0), iz = Double.compare(dz, 0);
        double tx = boundary(sx, dx, x), ty = boundary(sy, dy, y), tz = boundary(sz, dz, z);
        double enter = 0;
        for (int visited = 0; visited < 1024; visited++) {
            double leave = Math.min(1, Math.min(tx, Math.min(ty, tz)));
            double hit = Double.POSITIVE_INFINITY;
            // Stored boxes can extend one cell beyond their owning block (e.g. fences).
            // Require a known one-cell halo too; missing neighbors never imply empty space.
            for (int ox = -1; ox <= 1; ox++) for (int oy = -1; oy <= 1; oy++) for (int oz = -1; oz <= 1; oz++) {
                int bx = x + ox, by = y + oy, bz = z + oz;
                CollisionCell cell = source.cell(bx, by, bz);
                if (cell == null) return new Result(End.UNKNOWN, enter);
                // Unknown custom geometry must not be treated as its placeholder unit cube.
                if (cell.kind() == CollisionCell.Kind.CONSERVATIVE) hit = Math.min(hit, enter);
                else for (var box : cell.boxes()) {
                    double t = clip(sx - bx, sy - by, sz - bz, dx, dy, dz, box, enter, leave);
                    hit = Math.min(hit, t);
                }
            }
            if (hit <= leave) return new Result(End.BLOCK, hit);
            if (leave >= 1) return new Result(End.CLEAR, 1);
            // Advance all tied axes. Their touching neighboring cells were checked in the halo.
            if (tx <= leave) { x += ix; tx += 1 / Math.abs(dx); }
            if (ty <= leave) { y += iy; ty += 1 / Math.abs(dy); }
            if (tz <= leave) { z += iz; tz += 1 / Math.abs(dz); }
            enter = leave;
        }
        return new Result(End.INVALID, enter);
    }

    private static double boundary(double start, double delta, int cell) {
        return delta == 0 ? Double.POSITIVE_INFINITY : ((delta > 0 ? cell + 1.0 : cell) - start) / delta;
    }

    private static double clip(double sx, double sy, double sz, double dx, double dy, double dz,
                               CollisionCell.Box box, double enter, double leave) {
        double lo = enter, hi = leave;
        if (dx == 0) { if (sx < box.minX() || sx > box.maxX()) return Double.POSITIVE_INFINITY; }
        else {
            double a = (box.minX() - sx) / dx, b = (box.maxX() - sx) / dx;
            lo = Math.max(lo, Math.min(a, b)); hi = Math.min(hi, Math.max(a, b));
        }
        if (dy == 0) { if (sy < box.minY() || sy > box.maxY()) return Double.POSITIVE_INFINITY; }
        else {
            double a = (box.minY() - sy) / dy, b = (box.maxY() - sy) / dy;
            lo = Math.max(lo, Math.min(a, b)); hi = Math.min(hi, Math.max(a, b));
        }
        if (dz == 0) { if (sz < box.minZ() || sz > box.maxZ()) return Double.POSITIVE_INFINITY; }
        else {
            double a = (box.minZ() - sz) / dz, b = (box.maxZ() - sz) / dz;
            lo = Math.max(lo, Math.min(a, b)); hi = Math.min(hi, Math.max(a, b));
        }
        return lo <= hi ? lo : Double.POSITIVE_INFINITY;
    }
}
