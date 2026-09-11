package tacz.longrangesniping.ballistics;

import com.tacz.guns.entity.EntityKineticBullet;
import com.tacz.guns.particles.BulletHoleOption;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import tacz.longrangesniping.mixin.ChunkMapAccessor;
import tacz.longrangesniping.terrain.CollisionCell;
import tacz.longrangesniping.terrain.StaticCollisionShapes;

/** Target-core feedback. TACZ target blocks additionally receive their native projectile callback. */
public final class BlockImpactEffects {
    private BlockImpactEffects() {}

    public static BlockHitResult liveHit(ServerLevel level, Vec3 start, Vec3 movement, double fraction) {
        Vec3 impact = start.add(movement.scale(fraction));
        var center = BlockPos.containing(impact);
        BlockHitResult best = null;
        // Shapes such as fences can extend beyond their owner cell. Only the confirmed live surface gets a decal.
        for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) {
            var pos = center.offset(x, y, z);
            if (level.isOutsideBuildHeight(pos)) continue;
            var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk == null) continue;
            var state = chunk.getBlockState(pos);
            boolean target = state.getBlock() instanceof com.tacz.guns.block.TargetBlock;
            if (!target && StaticCollisionShapes.classify(state).kind() == CollisionCell.Kind.CONSERVATIVE) continue;
            var hit = (target ? state.getCollisionShape(chunk, pos) : state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)).clip(start,
                    start.add(movement).add(movement.normalize().scale(0.001)), pos);
            if (hit != null && hit.getLocation().distanceToSqr(impact) < 0.000001
                    && (best == null || hit.getLocation().distanceToSqr(start) < best.getLocation().distanceToSqr(start))) best = hit;
        }
        return best;
    }

    public static void targetHit(ServerLevel level, EntityKineticBullet bullet, Vec3 start, Vec3 movement, double fraction) {
        var hit = liveHit(level, start, movement, fraction);
        if (hit == null) return; // A cached/conservative stop is not proof of a present-day surface.
        var chunk = level.getChunkSource().getChunkNow(hit.getBlockPos().getX() >> 4, hit.getBlockPos().getZ() >> 4);
        if (chunk != null && chunk.getBlockState(hit.getBlockPos()).getBlock() instanceof com.tacz.guns.block.TargetBlock) {
            ((tacz.longrangesniping.mixin.KineticBulletAccessor) bullet).taczlrs$hitBlock(hit, start, start.add(movement));
            return;
        }
        var p = hit.getLocation(); var block = hit.getBlockPos();
        var option = new BulletHoleOption(hit.getDirection(), block, bullet.getAmmoId().toString(),
                bullet.getGunId().toString(), bullet.getGunDisplayId().toString());
        var map = (ChunkMapAccessor) level.getChunkSource().chunkMap;
        for (var player : level.players()) {
            if (player.distanceToSqr(p) <= 32 * 32 && map.taczlrs$isChunkTracked(player, block.getX() >> 4, block.getZ() >> 4))
                level.sendParticles(player, option, false, p.x, p.y, p.z, 1, 0, 0, 0, 0);
        }
    }
}
