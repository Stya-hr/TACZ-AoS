package tacz.longrangesniping.mixin;

import com.tacz.guns.entity.EntityKineticBullet;
import com.tacz.guns.util.TacHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Initialized parameters and the native damage entry point for detached kinetic bullets. */
@Mixin(value = EntityKineticBullet.class, remap = false)
public interface KineticBulletAccessor {
    @Accessor("speed") void taczlrs$setSpeed(float speed);
    @Accessor("life") int taczlrs$getLife();
    @Accessor("life") void taczlrs$setLife(int ticks);
    @Accessor("gravity") float taczlrs$getGravity();
    @Accessor("gravity") void taczlrs$setGravity(float gravity);
    @Accessor("friction") float taczlrs$getFriction();
    @Accessor("friction") void taczlrs$setFriction(float friction);
    @Accessor("pierce") int taczlrs$getPierce();
    @Accessor("pierce") void taczlrs$setPierce(int remaining);
    @Invoker("onHitEntity") void taczlrs$hitEntity(TacHitResult hit, Vec3 start, Vec3 end);
    @Invoker("onHitBlock") void taczlrs$hitBlock(net.minecraft.world.phys.BlockHitResult hit, Vec3 start, Vec3 end);
    @Accessor("distanceAmount") float taczlrs$getEffectiveRange();
    @Accessor("headShot") float taczlrs$getHeadshot();
    @Accessor("armorIgnore") float taczlrs$getArmorIgnore();
    @Accessor("explosion") boolean taczlrs$isExplosive();
    @Accessor("igniteEntity") boolean taczlrs$ignitesEntities();
    @Accessor("igniteBlock") boolean taczlrs$ignitesBlocks();
}
