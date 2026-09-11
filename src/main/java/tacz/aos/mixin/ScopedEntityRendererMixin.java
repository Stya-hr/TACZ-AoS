package tacz.aos.mixin;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import tacz.aos.client.ScopedRendering;

@Mixin(EntityRenderer.class)
public abstract class ScopedEntityRendererMixin {
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void tacz_aos$hideExpiredExtra(Entity entity, Frustum frustum, double x, double y, double z, CallbackInfoReturnable<Boolean> callback) {
        if (ScopedRendering.suppressExtra(entity)) callback.setReturnValue(false);
    }
}
