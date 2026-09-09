package tacz.longrangesniping.compat.tacz;

import com.tacz.guns.entity.EntityKineticBullet;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import tacz.longrangesniping.mixin.KineticBulletAccessor;

/** Values actually stored in the projectile after TACZ's attachment, script and global modifiers. */
public record BulletParameters(ResourceLocation gunId, ResourceLocation ammoId, Vec3 position, Vec3 velocity,
                               int lifetimeTicks, float gravity, float friction, int pierce,
                               float effectiveRange, float headshotMultiplier, float armorIgnore,
                               boolean explosive, boolean ignitesEntities, boolean ignitesBlocks) {
    public static BulletParameters capture(EntityKineticBullet bullet) {
        KineticBulletAccessor fields = (KineticBulletAccessor) bullet;
        return new BulletParameters(bullet.getGunId(), bullet.getAmmoId(), bullet.position(), bullet.getDeltaMovement(),
                fields.taczlrs$getLife(), fields.taczlrs$getGravity(), fields.taczlrs$getFriction(),
                fields.taczlrs$getPierce(), fields.taczlrs$getEffectiveRange(), fields.taczlrs$getHeadshot(),
                fields.taczlrs$getArmorIgnore(), fields.taczlrs$isExplosive(),
                fields.taczlrs$ignitesEntities(), fields.taczlrs$ignitesBlocks());
    }

    /** Future ballistic integration must explicitly handle world-changing ammunition. */
    public boolean isPlainKinetic() { return !explosive && !ignitesEntities && !ignitesBlocks; }
}
