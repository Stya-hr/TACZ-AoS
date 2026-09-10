package tacz.longrangesniping.mixin;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import tacz.longrangesniping.client.ScopedRendering;
import tacz.longrangesniping.config.ServerConfig;

@Mixin(GameRenderer.class)
public abstract class ScopeFarPlaneMixin {
    @Inject(method = "getDepthFar", at = @At("RETURN"), cancellable = true)
    private void taczlrs$scopedFarPlane(CallbackInfoReturnable<Float> callback) {
        if (!tacz.longrangesniping.client.ShaderCompat.shadowPass()
                && (ScopedRendering.active() || tacz.longrangesniping.client.ShotTrails.hasDistantTrails()))
            callback.setReturnValue(Math.max(callback.getReturnValue(), ServerConfig.current().maxDistance() + 64f));
    }
}
