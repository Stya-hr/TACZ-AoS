package tacz.longrangesniping.wind;

import com.tacz.guns.entity.EntityKineticBullet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import tacz.longrangesniping.mixin.KineticBulletAccessor;

public final class WindPhysics {
    private WindPhysics() {}
    public static void captureLaunch(Projectile projectile) {
        if (!(projectile instanceof EntityKineticBullet bullet) || !(bullet.level() instanceof ServerLevel level)
                || bullet.tickCount != 0 || bullet.isRemoved()) return;
        var fields = (KineticBulletAccessor) bullet;
        var wind = fields.taczlrs$isExplosive() || fields.taczlrs$ignitesEntities() || fields.taczlrs$ignitesBlocks()
                ? WindVector.ZERO : WindField.current(level);
        var direction = bullet.getDeltaMovement();
        ((WindBulletAccess) bullet).taczlrs$wind(wind.perpendicular(direction.x, direction.z));
    }
    /** v'=(v-w)*(1-f)+w-g; TACZ already supplies v*(1-f)-g. No wind under water. */
    public static Vec3 afterDrag(Vec3 velocity, WindVector wind, float friction, boolean water) {
        if (water || wind.x() == 0 && wind.z() == 0 || !Float.isFinite(friction) || friction <= 0) return velocity;
        double factor = Math.min(1, friction) / 20.0;
        return velocity.add(wind.x() * factor, 0, wind.z() * factor);
    }
}
