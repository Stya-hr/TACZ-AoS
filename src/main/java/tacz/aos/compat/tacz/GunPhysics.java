package tacz.aos.compat.tacz;

import com.tacz.guns.entity.EntityKineticBullet;
import net.minecraft.world.entity.projectile.Projectile;
import tacz.aos.config.ServerConfig;
import tacz.aos.mixin.KineticBulletAccessor;

/** Apply once at launch, before spread and inherited shooter motion. Independent of sniping eligibility. */
public final class GunPhysics {
    public static final float GRAVITY_PER_TICK = 9.80665f / 400f;
    private GunPhysics() {}

    public static float launchSpeed(Projectile projectile, float nativeSpeed) {
        if (!(projectile instanceof EntityKineticBullet bullet) || bullet.tickCount != 0 || bullet.isRemoved()) return nativeSpeed;
        var profile = ServerConfig.gunOverrides().get(String.valueOf(bullet.getGunId()));
        var fields = (KineticBulletAccessor) bullet;
        float friction = profile == null ? fields.tacz_aos$getFriction() : profile.frictionOr(fields.tacz_aos$getFriction());
        if (!fields.tacz_aos$isExplosive() && !fields.tacz_aos$ignitesEntities() && !fields.tacz_aos$ignitesBlocks()) {
            fields.tacz_aos$setGravity(GRAVITY_PER_TICK);
            if (bullet.level() instanceof net.minecraft.server.level.ServerLevel level) {
                friction = ((tacz.aos.humidity.HumidityBulletAccess) bullet).tacz_aos$humidFriction(friction,
                        tacz.aos.humidity.HumidityField.current(level).densityRatio());
            }
        }
        fields.tacz_aos$setFriction(friction);
        float speed = profile == null ? nativeSpeed : profile.speedOr(nativeSpeed);
        fields.tacz_aos$setSpeed(speed); // Native spawn data sends speed, gravity, friction and the actual velocity.
        return speed;
    }
}
