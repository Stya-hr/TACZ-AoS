package tacz.aos.mixin;

import com.tacz.guns.entity.EntityKineticBullet;
import com.tacz.guns.util.TacHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Initialized parameters and the native damage entry point for detached kinetic bullets. */
@Mixin(value = EntityKineticBullet.class, remap = false)
public interface KineticBulletAccessor {
    @Accessor("speed") void tacz_aos$setSpeed(float speed);
    @Accessor("life") int tacz_aos$getLife();
    @Accessor("life") void tacz_aos$setLife(int ticks);
    @Accessor("gravity") float tacz_aos$getGravity();
    @Accessor("gravity") void tacz_aos$setGravity(float gravity);
    @Accessor("friction") float tacz_aos$getFriction();
    @Accessor("friction") void tacz_aos$setFriction(float friction);
    @Accessor("pierce") int tacz_aos$getPierce();
    @Accessor("pierce") void tacz_aos$setPierce(int remaining);
    @Invoker("onHitEntity") void tacz_aos$hitEntity(TacHitResult hit, Vec3 start, Vec3 end);
    @Invoker("onHitBlock") void tacz_aos$hitBlock(net.minecraft.world.phys.BlockHitResult hit, Vec3 start, Vec3 end);
    @Accessor("distanceAmount") float tacz_aos$getEffectiveRange();
    @Accessor("headShot") float tacz_aos$getHeadshot();
    @Accessor("armorIgnore") float tacz_aos$getArmorIgnore();
    @Accessor("explosion") boolean tacz_aos$isExplosive();
    @Accessor("igniteEntity") boolean tacz_aos$ignitesEntities();
    @Accessor("igniteBlock") boolean tacz_aos$ignitesBlocks();
}
