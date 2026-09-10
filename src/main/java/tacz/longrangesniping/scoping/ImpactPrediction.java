package tacz.longrangesniping.scoping;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import tacz.longrangesniping.ballistics.SegmentTrace;
import tacz.longrangesniping.compat.tacz.BallisticPreview;
import tacz.longrangesniping.terrain.CollisionCell;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Bounded, read-only integration. A target is accepted only before any unknown terrain on the curved path. */
public final class ImpactPrediction {
    public enum End { BLOCK, ENTITY, UNKNOWN, RANGE, LIFETIME, BUDGET, INVALID }
    public record Result(End end, Vec3 position, int steps, int cells) {
        public Vec3 target() { return end == End.BLOCK || end == End.ENTITY ? position : null; }
    }
    private ImpactPrediction() {}

    public static Result predict(BallisticPreview shot, SegmentTrace.Source terrain, List<AABB> entities,
                                 double maxDistance, int maxTicks, int maxCells, int maxEntityChecks,
                                 long deadlineNanos) {
        var source = new BoundedSource(terrain, maxCells, deadlineNanos);
        Vec3 point = shot.start(), velocity = shot.velocity();
        double traveled = 0;
        int checks = 0;
        for (int tick = 0; tick < maxTicks; tick++) {
            if (source.expired()) return new Result(End.BUDGET, point, tick, source.cells.size());
            double length = velocity.length();
            if (!Double.isFinite(length) || length > 256 || !Double.isFinite(maxDistance) || maxDistance <= 0)
                return new Result(End.INVALID, point, tick, source.cells.size());
            boolean rangeEnd = length >= maxDistance - traveled;
            Vec3 movement = rangeEnd && length > 0 ? velocity.scale((maxDistance - traveled) / length) : velocity;
            var trace = SegmentTrace.trace(source, point.x, point.y, point.z, movement.x, movement.y, movement.z);
            if (source.exhausted) return new Result(End.BUDGET, point, tick, source.cells.size());
            double fraction = trace.fraction();
            // Match the live-flight guard: a terrain/unknown tie cannot expose an entity behind the boundary.
            if (trace.end() != SegmentTrace.End.CLEAR && movement.length() > 0)
                fraction = Math.max(0, fraction - 1.0e-6 / movement.length());
            Vec3 safeEnd = point.add(movement.scale(fraction)), entityHit = null;
            double nearest = Double.POSITIVE_INFINITY;
            for (var box : entities) {
                if (++checks > maxEntityChecks || source.expired())
                    return new Result(End.BUDGET, point, tick, source.cells.size());
                var hit = box.contains(point) ? java.util.Optional.of(point) : box.clip(point, safeEnd);
                if (fraction > 0 && hit.isPresent() && hit.get().distanceToSqr(point) < nearest) {
                    nearest = hit.get().distanceToSqr(point); entityHit = hit.get();
                }
            }
            if (entityHit != null) return new Result(End.ENTITY, entityHit, tick + 1, source.cells.size());
            if (trace.end() == SegmentTrace.End.BLOCK)
                return new Result(End.BLOCK, point.add(movement.scale(trace.fraction()))
                        .add(movement.normalize().scale(0.0001)), tick + 1, source.cells.size());
            if (trace.end() != SegmentTrace.End.CLEAR)
                return new Result(trace.end() == SegmentTrace.End.UNKNOWN ? End.UNKNOWN : End.INVALID,
                        safeEnd, tick + 1, source.cells.size());
            var medium = source.cell(Mth.floor(point.x), Mth.floor(point.y), Mth.floor(point.z));
            if (source.exhausted) return new Result(End.BUDGET, point, tick, source.cells.size());
            point = point.add(movement);
            traveled += movement.length();
            if (rangeEnd) return new Result(End.RANGE, point, tick + 1, source.cells.size());
            // Native TACZ order and float rounding: move, drag, gravity. Water follows snapshot flight semantics.
            float friction = shot.friction(), gravity = shot.gravity();
            if (medium != null && medium.water()) { friction = 0.4f; gravity *= 0.6f; }
            velocity = velocity.scale((double) (1f - friction)).add(0, (double) -gravity, 0);
            velocity = tacz.longrangesniping.wind.WindPhysics.afterDrag(velocity, shot.crosswind(), friction,
                    medium != null && medium.water());
        }
        return new Result(End.LIFETIME, point, maxTicks, source.cells.size());
    }

    private static final class BoundedSource implements SegmentTrace.Source {
        private final SegmentTrace.Source source;
        private final int maxCells;
        private final long deadline;
        private final Map<Long, CollisionCell> cells = new HashMap<>();
        private boolean exhausted;
        private int visits;
        private BoundedSource(SegmentTrace.Source source, int maxCells, long deadline) {
            this.source = source; this.maxCells = maxCells; this.deadline = deadline;
        }
        private boolean expired() { return exhausted |= System.nanoTime() - deadline >= 0; }
        public CollisionCell cell(int x, int y, int z) {
            if (exhausted || (++visits & 63) == 0 && expired()) return null;
            long key = BlockPos.asLong(x, y, z);
            if (cells.containsKey(key)) return cells.get(key);
            if (cells.size() >= maxCells) { exhausted = true; return null; }
            var cell = source.cell(x, y, z);
            cells.put(key, cell);
            return cell;
        }
    }
}
