package tacz.aos.mixin;

import com.tacz.guns.entity.EntityKineticBullet;
import net.minecraft.network.FriendlyByteBuf;
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
public abstract class KineticWindMixin implements WindBulletAccess, tacz.aos.humidity.HumidityBulletAccess {
    @Unique private double tacz_aos$humidityDensity = Double.NaN;
    @Unique private float tacz_aos$humidityBase, tacz_aos$humidityApplied;
    @Override public float tacz_aos$humidFriction(float base, double density) {
        // Re-launching the same tick-zero projectile cannot compound its own correction or reroll the weather.
        if (Double.isNaN(tacz_aos$humidityDensity)) {
            tacz_aos$humidityDensity = density;
            tacz_aos$humidityBase = base;
        } else if (base != tacz_aos$humidityApplied) tacz_aos$humidityBase = base;
        return tacz_aos$humidityApplied = tacz.aos.humidity.HumidityModel.friction(tacz_aos$humidityBase, tacz_aos$humidityDensity);
    }
    @Unique private WindVector tacz_aos$crosswind = WindVector.ZERO;
    @Override public WindVector tacz_aos$wind() { return tacz_aos$crosswind; }
    @Override public void tacz_aos$wind(WindVector wind) { tacz_aos$crosswind = wind; }

    @Inject(method = "tick", at = @At("RETURN"), remap = true)
    private void tacz_aos$windStep(CallbackInfo ci) {
        var bullet = (EntityKineticBullet) (Object) this;
        bullet.setDeltaMovement(WindPhysics.afterDrag(bullet.getDeltaMovement(),
                tacz_aos$crosswind, ((KineticBulletAccessor) this).tacz_aos$getFriction(), bullet.isInWater()));
    }
    @Inject(method = "writeSpawnData", at = @At("RETURN"))
    private void tacz_aos$writeWind(FriendlyByteBuf buffer, CallbackInfo ci) {
        buffer.writeDouble(tacz_aos$crosswind.x()); buffer.writeDouble(tacz_aos$crosswind.z());
    }
    @Inject(method = "readSpawnData", at = @At("RETURN"))
    private void tacz_aos$readWind(FriendlyByteBuf buffer, CallbackInfo ci) {
        var wind = new WindVector(buffer.readDouble(), buffer.readDouble());
        if (wind.speed() > 30.0001) throw new IllegalArgumentException("Wind spawn data out of bounds");
        tacz_aos$crosswind = wind;
    }
}
