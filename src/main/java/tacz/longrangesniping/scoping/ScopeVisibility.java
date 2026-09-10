package tacz.longrangesniping.scoping;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import tacz.longrangesniping.ballistics.NoLoadCollisionSource;
import tacz.longrangesniping.ballistics.SegmentTrace;
import tacz.longrangesniping.terrain.CollisionCell;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** One bounded, shared read cache per player refresh; neither successful nor failed queries load terrain. */
public final class ScopeVisibility {
    private final NoLoadCollisionSource world;
    private final ServerLevel level;
    private final Map<Long, CollisionCell> cells = new HashMap<>();
    private int remaining;
    public ScopeVisibility(ServerLevel level, int maxCells) { this.level = level; world = new NoLoadCollisionSource(level); remaining = maxCells; }
    public int remaining() { return remaining; }

    /** The first real/cached collision before any unknown cell. Air and exhausted budgets provide no loading target. */
    public Optional<Vec3> firstBlock(Vec3 start, Vec3 end) {
        double distance = end.distanceTo(start);
        if (!Double.isFinite(distance) || distance > 16384 || remaining <= 0) return Optional.empty();
        tacz.longrangesniping.terrain.TerrainSnapshots.INSTANCE.prepare(level, start, end, null);
        int steps = Math.max(1, (int) Math.ceil(distance / 128));
        Vec3 delta = end.subtract(start).scale(1.0 / steps);
        for (int step = 0; step < steps; step++) {
            Vec3 point = start.add(delta.scale(step));
            var hit = SegmentTrace.trace(this::cell, point.x, point.y, point.z, delta.x, delta.y, delta.z);
            if (hit.end() == SegmentTrace.End.BLOCK) return Optional.of(point.add(delta.scale(hit.fraction()))
                    .add(delta.normalize().scale(0.0001)));
            if (hit.end() != SegmentTrace.End.CLEAR) return Optional.empty();
        }
        return Optional.empty();
    }

    public boolean clear(Vec3 start, Vec3 end) {
        double distance = end.distanceTo(start);
        if (!Double.isFinite(distance) || distance > 16384 || remaining <= 0) return false;
        // The ballistic kernel deliberately bounds a single tick. Sight lines span several such segments.
        int steps = Math.max(1, (int) Math.ceil(distance / 128));
        Vec3 delta = end.subtract(start).scale(1.0 / steps);
        for (int step = 0; step < steps; step++) {
            Vec3 point = start.add(delta.scale(step));
            if (SegmentTrace.trace(this::cell, point.x, point.y, point.z, delta.x, delta.y, delta.z).end()
                    != SegmentTrace.End.CLEAR) return false;
        }
        return true;
    }

    private CollisionCell cell(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        if (cells.containsKey(key)) return cells.get(key);
        if (remaining-- <= 0) return null;
        var value = world.cell(x, y, z);
        cells.put(key, value);
        return value;
    }
}
