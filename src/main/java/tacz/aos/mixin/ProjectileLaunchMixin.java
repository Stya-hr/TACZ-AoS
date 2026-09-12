package tacz.aos.mixin;

import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import tacz.aos.compat.tacz.GunPhysics;
import tacz.aos.compat.tacz.GunSpread;

/** Default TACZ firing inherits this vanilla overload. */
@Mixin(Projectile.class)
public abstract class ProjectileLaunchMixin {
    @ModifyVariable(method = "shoot(DDDFF)V", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private float tacz_aos$proneSpread(float nativeSpread) {
        return GunSpread.zeroWhileProne((Projectile) (Object) this) ? 0f : nativeSpread;
    }

    @ModifyVariable(method = "shoot(DDDFF)V", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private float tacz_aos$gunSpeed(float nativeSpeed) {
        return GunPhysics.launchSpeed((Projectile) (Object) this, nativeSpeed);
    }
    @org.spongepowered.asm.mixin.injection.Inject(method = "shoot(DDDFF)V", at = @At("RETURN"))
    private void tacz_aos$captureWind(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        tacz.aos.wind.WindPhysics.captureLaunch((Projectile) (Object) this);
    }
}
