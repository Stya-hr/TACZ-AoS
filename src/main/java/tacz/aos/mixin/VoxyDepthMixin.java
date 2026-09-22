package tacz.aos.mixin;

import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.rendering.Viewport;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tacz.aos.client.VisibleTargetReporter;

/** Optional target: Voxy is not linked on clients without Voxy or on servers. */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.AbstractRenderPipeline", remap = false)
public abstract class VoxyDepthMixin {
    @Inject(method = "runPipeline", at = @At("RETURN"), require = 1)
    private void tacz_aos$terrainDepth(Viewport<?> viewport, int sourceFramebuffer, int width, int height, CallbackInfo ci) {
        var pipeline = (AbstractRenderPipeline) (Object) this;
        VisibleTargetReporter.captureTerrain(pipeline.fb.framebuffer.id, viewport.width, viewport.height,
                viewport.MVP, new Vec3(viewport.cameraX, viewport.cameraY, viewport.cameraZ),
                // Projection coefficients and hardware NDC-to-window mapping are separate. In the
                // supplied Voxy build, a 0..1 projection can still run with GL_NEGATIVE_ONE_TO_ONE.
                org.lwjgl.opengl.GL11C.glGetInteger(org.lwjgl.opengl.GL45C.GL_CLIP_DEPTH_MODE)
                        == org.lwjgl.opengl.GL45C.GL_ZERO_TO_ONE, pipeline.properties.clearDepth());
    }
}
