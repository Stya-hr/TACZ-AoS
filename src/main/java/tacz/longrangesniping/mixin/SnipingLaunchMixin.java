package tacz.longrangesniping.mixin;

import com.tacz.guns.entity.EntityKineticBullet;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import tacz.longrangesniping.compat.tacz.SnipingPhysics;

/** Native TACZ launch: replace magnitude before spread direction and shooter movement are combined. */
@Mixin(value = EntityKineticBullet.class, remap = false)
public abstract class SnipingLaunchMixin {
    @ModifyVariable(method = "shoot(DDFLorg/joml/Vector2d;)V", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private float taczlrs$snipingSpeed(float nativeSpeed) {
        var bullet = (EntityKineticBullet) (Object) this;
        var fields = (KineticBulletAccessor) this;
        if (bullet.getClass() != EntityKineticBullet.class || bullet.tickCount != 0 || bullet.isRemoved()
                || !(bullet.getOwner() instanceof Player player)
                || fields.taczlrs$isExplosive() || fields.taczlrs$ignitesEntities() || fields.taczlrs$ignitesBlocks()) return nativeSpeed;
        var profile = SnipingPhysics.forPlayer(player, String.valueOf(bullet.getGunId()));
        if (profile == null) return nativeSpeed;
        fields.taczlrs$setGravity(SnipingPhysics.GRAVITY);
        fields.taczlrs$setFriction(profile.friction());
        return profile.speedPerTick();
    }
}
