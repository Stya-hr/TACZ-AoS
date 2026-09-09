package tacz.longrangesniping.ballistics;

import com.tacz.guns.entity.EntityKineticBullet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Conservative prototype guard, not the future terrain coverage/snapshot implementation. */
public final class LoadedAreaGuard {
    private LoadedAreaGuard() {}

    public enum Result { READY, INVALID, RANGE_LIMIT, UNKNOWN_AREA }

    public static Result check(ServerLevel level, EntityKineticBullet bullet, double remainingDistance) {
        Vec3 start = bullet.position();
        Vec3 velocity = bullet.getDeltaMovement();
        Vec3 end = start.add(velocity);
        if (!finite(start) || !finite(velocity) || !finite(end)) return Result.INVALID;
        double distance = velocity.length();
        if (!Double.isFinite(distance) || distance > remainingDistance + 1.0e-7) return Result.RANGE_LIMIT;
        // TACZ's entity search inflates the swept bounding box by one block. Two blocks
        // also cover fluid/base entity updates and ordinary adjacent collision-shape reads.
        var bounds = bullet.getBoundingBox().expandTowards(velocity).inflate(2);
        if (bounds.minY < level.getMinBuildHeight() || bounds.maxY >= level.getMaxBuildHeight()
                || !level.getWorldBorder().isWithinBounds(bounds)) return Result.UNKNOWN_AREA;
        int minX = Mth.floor(bounds.minX) >> 4;
        int maxX = Mth.floor(bounds.maxX) >> 4;
        int minZ = Mth.floor(bounds.minZ) >> 4;
        int maxZ = Mth.floor(bounds.maxZ) >> 4;
        // Bound work even if another mod supplies pathological entity dimensions.
        if ((long) maxX - minX > 32 || (long) maxZ - minZ > 32) return Result.INVALID;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                // This lookup never creates a ticket, waits for a future, or loads a chunk.
                if (level.getChunkSource().getChunkNow(x, z) == null) return Result.UNKNOWN_AREA;
            }
        }
        return Result.READY;
    }

    private static boolean finite(Vec3 value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z)
                && Math.abs(value.x) <= 30_000_000 && Math.abs(value.z) <= 30_000_000;
    }
}
