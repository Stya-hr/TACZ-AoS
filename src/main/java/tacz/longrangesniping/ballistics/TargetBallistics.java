package tacz.longrangesniping.ballistics;

import com.tacz.guns.entity.EntityKineticBullet;
import com.tacz.guns.util.EntityUtil;
import com.tacz.guns.util.TacHitResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import tacz.longrangesniping.mixin.KineticBulletAccessor;

import java.util.Comparator;

/** No Projectile/Entity base tick: those methods can access or load terrain around a detached bullet. */
public final class TargetBallistics {
    public record Step(String termination, double distance) {}
    private TargetBallistics() {}

    public static Step tick(ServerLevel level, EntityKineticBullet bullet, double remaining, TargetCollisionSource source) {
        Vec3 start = bullet.position(), velocity = bullet.getDeltaMovement();
        double length = velocity.length();
        if (!Double.isFinite(start.lengthSqr()) || Math.abs(start.x)>30_000_000 || Math.abs(start.y)>30_000_000 || Math.abs(start.z)>30_000_000
                || !Double.isFinite(length) || length > 256 || !Double.isFinite(remaining) || remaining <= 0) {
            return stop(bullet, remaining <= 0 ? "RANGE_LIMIT" : "INVALID", 0);
        }
        boolean rangeEnd = length >= remaining;
        Vec3 movement = rangeEnd && length > 0 ? velocity.scale(remaining / length) : velocity;
        var trace = source.trace(start, movement);
        double fraction = trace.fraction();
        // Keep the endpoint strictly inside the covered prefix. A block/unknown tie cannot hit an entity behind it.
        if (trace.end() != SegmentTrace.End.CLEAR && movement.length() > 0) {
            fraction = Math.max(0, fraction - 1.0e-6 / movement.length());
        }
        Vec3 end = start.add(movement.scale(fraction));
        var fields = (KineticBulletAccessor) bullet;
        if (fraction > 0 && length > 0 && source.intersects(start,end)) {
            // TACZ searches real, currently accessible server entities and supplies its native headshot result.
            // Narrow its broad-phase bounds to the safe prefix, then restore the physical velocity before callbacks.
            bullet.setDeltaMovement(end.subtract(start));
            java.util.List<EntityKineticBullet.EntityResult> hits;
            try { hits = EntityUtil.findEntitiesOnPath(bullet, start, end); }
            finally { bullet.setDeltaMovement(velocity); }
            hits.sort(Comparator.comparingDouble(hit -> hit.getHitPos().distanceToSqr(start)));
            for (var hit : hits) {
                var entity = hit.getEntity();
                if (!source.contains(hit.getHitPos()) || entity.isRemoved() || entity.level() != level
                        || level.getChunkSource().getChunkNow(entity.chunkPosition().x, entity.chunkPosition().z) == null) continue;
                fields.taczlrs$hitEntity(new TacHitResult(hit), start, end);
                fields.taczlrs$setPierce(fields.taczlrs$getPierce() - 1);
                if (fields.taczlrs$getPierce() < 1 || bullet.isRemoved()) {
                    bullet.setPos(hit.getHitPos());
                    return stop(bullet, "entity_hit", hit.getHitPos().distanceTo(start));
                }
                if (fields.taczlrs$isExplosive() || fields.taczlrs$ignitesEntities() || fields.taczlrs$ignitesBlocks()) {
                    return stop(bullet, "unsupported_ammunition_changed", hit.getHitPos().distanceTo(start));
                }
            }
        }
        bullet.setPos(end);
        double distance = end.distanceTo(start);
        if (trace.end() == SegmentTrace.End.BLOCK) BlockImpactEffects.targetHit(level, bullet, start, movement, trace.fraction());
        if (trace.end() != SegmentTrace.End.CLEAR) return stop(bullet, trace.end().name(), distance);
        if (rangeEnd) return stop(bullet, "RANGE_LIMIT", distance);

        // Same order and float rounding as TACZ 1.1.8-hotfix-r6: move, drag, gravity, then lifetime.
        // Only target-core water affects flight; transit regions deliberately use air physics.
        var medium = source.cell(Mth.floor(start.x), Mth.floor(start.y), Mth.floor(start.z));
        float friction = fields.taczlrs$getFriction(), gravity = fields.taczlrs$getGravity();
        if (medium != null && medium.water()) { friction = 0.4f; gravity *= 0.6f; }
        bullet.setDeltaMovement(tacz.longrangesniping.wind.WindPhysics.afterDrag(
                velocity.scale((double) (1.0f - friction)).add(0, (double) -gravity, 0),
                ((tacz.longrangesniping.wind.WindBulletAccess) bullet).taczlrs$wind(), friction, medium != null && medium.water()));
        float yaw = (float) (Mth.atan2(velocity.x, velocity.z) * (180 / Math.PI));
        float pitch = (float) (Mth.atan2(velocity.y, velocity.horizontalDistance()) * (180 / Math.PI));
        if (bullet.xRotO == 0 && bullet.yRotO == 0) { bullet.xRotO = pitch; bullet.yRotO = yaw; }
        bullet.setYRot(lerpRotation(bullet.yRotO, yaw));
        bullet.setXRot(lerpRotation(bullet.xRotO, pitch));
        if (bullet.tickCount >= fields.taczlrs$getLife() - 1) return stop(bullet, "lifetime", distance);
        return new Step(null, distance);
    }

    private static float lerpRotation(float previous, float current) {
        while (current - previous < -180) previous -= 360;
        while (current - previous >= 180) previous += 360;
        return Mth.lerp(0.2f, previous, current);
    }

    private static Step stop(EntityKineticBullet bullet, String reason, double distance) {
        bullet.discard();
        return new Step(reason.equals("UNKNOWN") ? "UNKNOWN_AREA" : reason, distance);
    }
}
