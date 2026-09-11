package tacz.aos.mixin;

import com.tacz.guns.client.renderer.block.TargetRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import tacz.aos.client.ScopedRendering;
import tacz.aos.client.ShaderCompat;
import tacz.aos.config.ServerConfig;

/** Native received chunks retain their native block entity renderer, including its skin and animation. */
@Mixin(value=TargetRenderer.class,remap=false)
public abstract class ScopedTargetRendererMixin {
    @Inject(method="getViewDistance",at=@At("RETURN"),cancellable=true)
    private void tacz_aos$distance(CallbackInfoReturnable<Integer> result){
        if(ScopedRendering.active() && !ShaderCompat.shadowPass())result.setReturnValue(Math.max(result.getReturnValue(),ServerConfig.current().maxDistance()));
    }
}
