package tacz.longrangesniping.mixin;

import com.tacz.guns.entity.EntityKineticBullet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only access to initialized TACZ fields. No tick injections or setters. */
@Mixin(value = EntityKineticBullet.class, remap = false)
public interface KineticBulletAccessor {
    @Accessor("life") int taczlrs$getLife();
    @Accessor("gravity") float taczlrs$getGravity();
    @Accessor("friction") float taczlrs$getFriction();
    @Accessor("pierce") int taczlrs$getPierce();
    @Accessor("distanceAmount") float taczlrs$getEffectiveRange();
    @Accessor("headShot") float taczlrs$getHeadshot();
    @Accessor("armorIgnore") float taczlrs$getArmorIgnore();
    @Accessor("explosion") boolean taczlrs$isExplosive();
    @Accessor("igniteEntity") boolean taczlrs$ignitesEntities();
    @Accessor("igniteBlock") boolean taczlrs$ignitesBlocks();
}
