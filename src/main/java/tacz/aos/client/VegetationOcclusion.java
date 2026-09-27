package tacz.aos.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.model.data.ModelData;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import tacz.aos.network.ScopeOcclusionPayload;

/** Texture-alpha cover exists only during extra entity drawing; it leaves no invisible scene depth. */
public final class VegetationOcclusion {
    private static final RandomSource RANDOM = RandomSource.create();
    private static final int MAX_QUADS_PER_BLOCK = 128;
    private static java.nio.FloatBuffer vertices;
    private VegetationOcclusion() {}
    public static final class Mask implements AutoCloseable {
        private final PlantDepthPass pass;
        private Mask(PlantDepthPass pass){this.pass=pass;}
        @Override public void close(){if(pass!=null)pass.close();}
    }
    public static Mask render(Collection<ScopeOcclusionPayload.Target> targets, PoseStack pose, Vec3 eye) {
        var blocks = new LinkedHashMap<BlockPos, BlockState>();
        var mc = Minecraft.getInstance();
        if (mc.level == null) return new Mask(null);
        for (var target : targets) {
            var entity = mc.level.getEntity(target.id());
            if (entity == null || !entity.getUUID().equals(target.uuid())) continue;
            for (var plant : target.textured()) blocks.put(target.origin().offset(plant.offset()), plant.state());
        }
        return renderBlocks(blocks, pose, eye);
    }

    public static Mask renderBlocks(Map<BlockPos, BlockState> blocks, PoseStack pose, Vec3 eye) {
        var mc = Minecraft.getInstance();
        if (blocks.isEmpty() || mc.level == null || ShaderCompat.shadowPass()) return new Mask(null);
        if(vertices==null)vertices=org.lwjgl.BufferUtils.createFloatBuffer(ScopeOcclusionPayload.MAX_TEXTURED*MAX_QUADS_PER_BLOCK*30);
        vertices.clear();
        for (var entry : blocks.entrySet()) {
            if(vertices.remaining()<MAX_QUADS_PER_BLOCK*30)break;
            var pos=entry.getKey();var state=entry.getValue();
            var model=mc.getBlockRenderer().getBlockModel(state);long seed=state.getSeed(pos);
            var offset=state.getOffset(mc.level,pos);
            pose.pushPose();
            try {
                pose.translate(pos.getX()-eye.x+offset.x,pos.getY()-eye.y+offset.y,pos.getZ()-eye.z+offset.z);
                int remaining=MAX_QUADS_PER_BLOCK;
                RANDOM.setSeed(seed);
                for(var layer:model.getRenderTypes(state,RANDOM,ModelData.EMPTY)) {
                    for(int face=0;face<=6;face++) {
                        RANDOM.setSeed(seed);
                        for(var quad:model.getQuads(state,face==6?null:Direction.values()[face],RANDOM,ModelData.EMPTY,layer)) {
                            if(remaining--<=0)break;
                            emit(quad,pose.last().pose());
                        }
                        if(remaining<=0)break;
                    }
                    if(remaining<=0)break;
                }
            } finally {pose.popPose();}
        }
        vertices.flip();
        if(!vertices.hasRemaining())return new Mask(null);
        var matrix=new Matrix4f(RenderSystem.getProjectionMatrix()).mul(RenderSystem.getModelViewMatrix());
        var pass=new PlantDepthPass(vertices,matrix);
        try {
            pass.draw(vertices,matrix,
                    mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getId());
            return new Mask(pass);
        } catch(RuntimeException error) {pass.close();throw error;}
    }
    private static void emit(BakedQuad quad,Matrix4f pose) {
        var data=quad.getVertices();int stride=data.length/4;
        var position=new Vector3f();
        for(int vertex:new int[]{0,1,2,0,2,3}) {
            int i=vertex*stride;
            pose.transformPosition(Float.intBitsToFloat(data[i]),Float.intBitsToFloat(data[i+1]),Float.intBitsToFloat(data[i+2]),position);
            vertices.put(position.x).put(position.y).put(position.z).put(Float.intBitsToFloat(data[i+4])).put(Float.intBitsToFloat(data[i+5]));
        }
    }
    public static void clear(){PlantDepthPass.destroy();vertices=null;}
}
