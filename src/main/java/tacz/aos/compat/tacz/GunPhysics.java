package tacz.aos.compat.tacz;

import com.tacz.guns.entity.EntityKineticBullet;
import net.minecraft.world.entity.projectile.Projectile;
import tacz.aos.config.ServerConfig;
import tacz.aos.mixin.KineticBulletAccessor;

/** Apply once at launch, before spread and inherited shooter motion. Independent of sniping eligibility. */
public final class GunPhysics {
    private GunPhysics() {}

    public static float launchSpeed(Projectile projectile, float nativeSpeed) {
        if (!(projectile instanceof EntityKineticBullet bullet) || bullet.tickCount != 0 || bullet.isRemoved()) return nativeSpeed;
        var profile = ServerConfig.gunOverrides().get(String.valueOf(bullet.getGunId()));
        if (profile == null) return nativeSpeed;
        var fields = (KineticBulletAccessor) bullet;
        fields.tacz_aos$setGravity(profile.gravityOr(fields.tacz_aos$getGravity()));
        fields.tacz_aos$setFriction(profile.frictionOr(fields.tacz_aos$getFriction()));
        float speed = profile.speedOr(nativeSpeed);
        fields.tacz_aos$setSpeed(speed); // Native spawn data sends speed, gravity, friction and the actual velocity.
        return speed;
    }
}
