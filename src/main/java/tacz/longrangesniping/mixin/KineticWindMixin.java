package tacz.longrangesniping.mixin;

import com.tacz.guns.entity.EntityKineticBullet;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tacz.longrangesniping.wind.WindBulletAccess;
import tacz.longrangesniping.wind.WindPhysics;
import tacz.longrangesniping.wind.WindVector;

/** Per-shot crosswind travels with native spawn data; detached bullets use the same fields without native ticking. */
@Mixin(value = EntityKineticBullet.class, remap = false)
public abstract class KineticWindMixin implements WindBulletAccess {
    @Unique private WindVector taczlrs$crosswind = WindVector.ZERO;
    @Override public WindVector taczlrs$wind() { return taczlrs$crosswind; }
    @Override public void taczlrs$wind(WindVector wind) { taczlrs$crosswind = wind; }

    @Inject(method = "tick", at = @At("RETURN"))
    private void taczlrs$windStep(CallbackInfo ci) {
        var bullet = (EntityKineticBullet) (Object) this;
        bullet.setDeltaMovement(WindPhysics.afterDrag(bullet.getDeltaMovement(),
                taczlrs$crosswind, ((KineticBulletAccessor) this).taczlrs$getFriction(), bullet.isInWater()));
    }
    @Inject(method = "writeSpawnData", at = @At("RETURN"))
    private void taczlrs$writeWind(RegistryFriendlyByteBuf buffer, CallbackInfo ci) {
        buffer.writeDouble(taczlrs$crosswind.x()); buffer.writeDouble(taczlrs$crosswind.z());
    }
    @Inject(method = "readSpawnData", at = @At("RETURN"))
    private void taczlrs$readWind(RegistryFriendlyByteBuf buffer, CallbackInfo ci) {
        var wind = new WindVector(buffer.readDouble(), buffer.readDouble());
        if (wind.speed() > 30.0001) throw new IllegalArgumentException("Wind spawn data out of bounds");
        taczlrs$crosswind = wind;
    }
}
