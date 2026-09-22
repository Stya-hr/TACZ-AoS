package tacz.aos.client;

import net.minecraftforge.fml.ModList;

/** Keep all optional Oculus types behind a class-load boundary. */
public final class ShaderCompat {
    private static final boolean OCULUS = ModList.get().isLoaded("oculus");
    private ShaderCompat() {}
    public static boolean active() { return OCULUS && OculusBridge.active(); }
    public static boolean shadowPass() { return OCULUS && OculusBridge.shadowPass(); }
    public static net.minecraft.client.renderer.ShaderInstance translucentParticleShader() {
        var shader = active() ? OculusBridge.translucentParticleShader() : null;
        return shader != null ? shader : net.minecraft.client.renderer.GameRenderer.getParticleShader();
    }

    private static final class OculusBridge {
        static boolean active() { return net.irisshaders.iris.api.v0.IrisApi.getInstance().isShaderPackInUse(); }
        static boolean shadowPass() { return net.irisshaders.iris.api.v0.IrisApi.getInstance().isRenderingShadowPass(); }
        static net.minecraft.client.renderer.ShaderInstance translucentParticleShader() {
            var pipeline = net.irisshaders.iris.Iris.getPipelineManager().getPipelineNullable();
            // GameRenderer.getParticleShader selects the opaque key. Our smoke is drawn after
            // translucents, so packs with separate particle passes need the translucent key.
            if (pipeline instanceof net.irisshaders.iris.pipeline.ShaderRenderingPipeline shaders
                    && shaders.shouldOverrideShaders())
                return shaders.getShaderMap().getShader(net.irisshaders.iris.pipeline.programs.ShaderKey.PARTICLES_TRANS);
            return null;
        }
    }
}
