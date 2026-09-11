package tacz.aos.mixin;

import com.tacz.guns.entity.EntityKineticBullet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import tacz.aos.compat.tacz.GunPhysics;

/** TACZ Lua calcSpread uses this separate launch route. */
@Mixin(value = EntityKineticBullet.class, remap = false)
public abstract class KineticLaunchMixin {
    @ModifyVariable(method = "shoot(DDFLorg/joml/Vector2d;)V", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private float tacz_aos$gunSpeed(float nativeSpeed) {
        return GunPhysics.launchSpeed((EntityKineticBullet) (Object) this, nativeSpeed);
    }
    @org.spongepowered.asm.mixin.injection.Inject(method = "shoot(DDFLorg/joml/Vector2d;)V", at = @At("RETURN"))
    private void tacz_aos$captureWind(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        tacz.aos.wind.WindPhysics.captureLaunch((EntityKineticBullet) (Object) this);
    }
}
