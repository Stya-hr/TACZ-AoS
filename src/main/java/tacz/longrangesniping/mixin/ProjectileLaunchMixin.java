package tacz.longrangesniping.mixin;

import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import tacz.longrangesniping.compat.tacz.GunPhysics;

/** Default TACZ firing inherits this vanilla overload. */
@Mixin(Projectile.class)
public abstract class ProjectileLaunchMixin {
    @ModifyVariable(method = "shoot(DDDFF)V", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private float taczlrs$gunSpeed(float nativeSpeed) {
        return GunPhysics.launchSpeed((Projectile) (Object) this, nativeSpeed);
    }
    @org.spongepowered.asm.mixin.injection.Inject(method = "shoot(DDDFF)V", at = @At("RETURN"))
    private void taczlrs$captureWind(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        tacz.longrangesniping.wind.WindPhysics.captureLaunch((Projectile) (Object) this);
    }
}
