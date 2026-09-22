package tacz.aos.mixin;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import tacz.aos.client.ScopedRendering;
import tacz.aos.config.ServerConfig;

@Mixin(GameRenderer.class)
public abstract class ScopeFarPlaneMixin {
    @Inject(method = "getDepthFar", at = @At("RETURN"), cancellable = true)
    private void tacz_aos$scopedFarPlane(CallbackInfoReturnable<Float> callback) {
        if (!tacz.aos.client.ShaderCompat.shadowPass() && (ScopedRendering.active() || tacz.aos.client.ShotTrails.hasDistantTrails()))
            callback.setReturnValue(Math.max(callback.getReturnValue(), ServerConfig.current().maxDistance() + 64f));
    }
}
