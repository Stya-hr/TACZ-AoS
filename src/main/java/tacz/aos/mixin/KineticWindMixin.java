package tacz.aos.mixin;

import com.tacz.guns.entity.EntityKineticBullet;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tacz.aos.wind.WindBulletAccess;
import tacz.aos.wind.WindPhysics;
import tacz.aos.wind.WindVector;

/** Per-shot crosswind travels with native spawn data; detached bullets use the same fields without native ticking. */
@Mixin(value = EntityKineticBullet.class, remap = false)
public abstract class KineticWindMixin implements WindBulletAccess {
    @Unique private WindVector tacz_aos$crosswind = WindVector.ZERO;
    @Override public WindVector tacz_aos$wind() { return tacz_aos$crosswind; }
    @Override public void tacz_aos$wind(WindVector wind) { tacz_aos$crosswind = wind; }

    @Inject(method = "tick", at = @At("RETURN"))
    private void tacz_aos$windStep(CallbackInfo ci) {
        var bullet = (EntityKineticBullet) (Object) this;
        bullet.setDeltaMovement(WindPhysics.afterDrag(bullet.getDeltaMovement(),
                tacz_aos$crosswind, ((KineticBulletAccessor) this).tacz_aos$getFriction(), bullet.isInWater()));
    }
    @Inject(method = "writeSpawnData", at = @At("RETURN"))
    private void tacz_aos$writeWind(RegistryFriendlyByteBuf buffer, CallbackInfo ci) {
        buffer.writeDouble(tacz_aos$crosswind.x()); buffer.writeDouble(tacz_aos$crosswind.z());
    }
    @Inject(method = "readSpawnData", at = @At("RETURN"))
    private void tacz_aos$readWind(RegistryFriendlyByteBuf buffer, CallbackInfo ci) {
        var wind = new WindVector(buffer.readDouble(), buffer.readDouble());
        if (wind.speed() > 30.0001) throw new IllegalArgumentException("Wind spawn data out of bounds");
        tacz_aos$crosswind = wind;
    }
}
