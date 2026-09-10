package tacz.longrangesniping.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import tacz.longrangesniping.TaczLongRangeSniping;

import static net.minecraft.client.renderer.RenderStateShard.*;

/** Standard particle / emissive-entity materials let Iris supply the pack's MRT outputs and bloom.
 * The small periodic opacity texture replaces the custom GLSL noise only while shaders are active.
 */
final class ShaderTrailMaterials {
    private static final ResourceLocation SMOKE = ResourceLocation.fromNamespaceAndPath(TaczLongRangeSniping.MOD_ID, "dynamic/trail_smoke");
    private static final ResourceLocation WHITE = ResourceLocation.fromNamespaceAndPath(TaczLongRangeSniping.MOD_ID, "dynamic/trail_head");
    static final RenderType SMOKE_TYPE = RenderType.create("taczlrs_shader_smoke", DefaultVertexFormat.PARTICLE,
            VertexFormat.Mode.QUADS, 65536, false, false, RenderType.CompositeState.builder()
                    .setShaderState(new ShaderStateShard(ShaderCompat::translucentParticleShader)).setTextureState(new TextureStateShard(SMOKE, true, false))
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY).setCullState(NO_CULL)
                    .setLightmapState(LIGHTMAP).setWriteMaskState(COLOR_WRITE).createCompositeState(false));
    static final RenderType HEAD_TYPE = RenderType.entityTranslucentEmissive(WHITE, false);
    private static boolean ready;

    static void prepare() {
        if (ready) return;
        var pixels = new NativeImage(64, 64, false);
        for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) {
            double v = (y + .5) / 32 - 1, u = (x + .5) / 64 * Math.PI * 2;
            double cloud = .6 + .22 * Math.sin(u + v * 2.4) + .12 * Math.sin(u * 3 - v * 5.1);
            double edge = Math.pow(Math.max(0, 1 - v * v), 1.8);
            int a = (int) (255 * Math.clamp(edge * cloud, 0, 1));
            pixels.setPixelRGBA(x, y, (a << 24) | 0xFFFFFF);
        }
        var white = new NativeImage(1, 1, false); white.setPixelRGBA(0, 0, -1);
        var manager = Minecraft.getInstance().getTextureManager();
        manager.register(SMOKE, new DynamicTexture(pixels)); manager.register(WHITE, new DynamicTexture(white));
        ready = true;
    }
}
