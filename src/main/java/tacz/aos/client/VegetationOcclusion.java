package tacz.aos.client;

import com.mojang.blaze3d.vertex.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.model.data.ModelData;
import tacz.aos.network.ScopeOcclusionPayload;

/** Native cutout alpha writes depth only: no fake client chunks, colors, lighting, or selection-box walls. */
public final class VegetationOcclusion extends RenderStateShard {
    private VegetationOcclusion() { super("tacz_aos_vegetation", () -> {}, () -> {}); }
    private static final RenderType DEPTH = RenderType.create("tacz_aos_vegetation_depth", DefaultVertexFormat.NEW_ENTITY,
            VertexFormat.Mode.QUADS, 65536, false, false, RenderType.CompositeState.builder()
                    .setShaderState(RENDERTYPE_ENTITY_CUTOUT_NO_CULL_SHADER)
                    .setTextureState(new TextureStateShard(TextureAtlas.LOCATION_BLOCKS, false, false))
                    .setCullState(NO_CULL).setDepthTestState(LEQUAL_DEPTH_TEST).setWriteMaskState(DEPTH_WRITE)
                    .setLightmapState(LIGHTMAP).setOverlayState(OVERLAY).createCompositeState(false));
    private static final MultiBufferSource.BufferSource BUFFERS = MultiBufferSource.immediate(new BufferBuilder(65536));
    private static final RandomSource RANDOM = RandomSource.create();
    private static final int MAX_QUADS_PER_BLOCK = 128;

    public static void render(Collection<ScopeOcclusionPayload.Target> targets, PoseStack pose, Vec3 eye) {
        var blocks = new LinkedHashMap<BlockPos, BlockState>();
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        for (var target : targets) {
            var entity = mc.level.getEntity(target.id());
            if (entity == null || !entity.getUUID().equals(target.uuid())) continue;
            for (var plant : target.textured()) blocks.put(target.origin().offset(plant.offset()), plant.state());
        }
        renderBlocks(blocks, pose, eye);
    }

    public static void renderBlocks(Map<BlockPos, BlockState> blocks, PoseStack pose, Vec3 eye) {
        var mc = Minecraft.getInstance();
        if (blocks.isEmpty() || mc.level == null) return;
        try {
            var sink = BUFFERS.getBuffer(DEPTH);
            for (var entry : blocks.entrySet()) {
                var pos = entry.getKey(); var state = entry.getValue();
                var model = mc.getBlockRenderer().getBlockModel(state);
                long seed = state.getSeed(pos);
                var offset = state.getOffset(mc.level, pos);
                pose.pushPose();
                try {
                    pose.translate(pos.getX()-eye.x+offset.x, pos.getY()-eye.y+offset.y, pos.getZ()-eye.z+offset.z);
                    int remaining = MAX_QUADS_PER_BLOCK;
                    RANDOM.setSeed(seed);
                    for (var layer : model.getRenderTypes(state, RANDOM, ModelData.EMPTY)) {
                        // Match vanilla's per-face seed, including unculled cross/crop quads.
                        for (int face=0; face<=6; face++) {
                            RANDOM.setSeed(seed);
                            List<BakedQuad> quads = model.getQuads(state, face==6?null:Direction.values()[face], RANDOM, ModelData.EMPTY, layer);
                            for (var quad : quads) {
                                if (remaining--<=0) break;
                                sink.putBulkData(pose.last(), quad, 1, 1, 1, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
                            }
                            if (remaining<=0) break;
                        }
                        if (remaining<=0) break;
                    }
                } finally { pose.popPose(); }
            }
        } finally { BUFFERS.endBatch(); }
    }
}
