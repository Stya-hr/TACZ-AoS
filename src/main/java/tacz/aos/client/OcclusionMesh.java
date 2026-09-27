package tacz.aos.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.model.data.ModelData;
import org.joml.*;
import tacz.aos.config.ServerConfig;
import tacz.aos.network.ScopeOcclusionPayload;
import tacz.aos.occlusion.SectionMask;

/** Shared greedy solid faces and texture-alpha plants, drawn only into temporary entity depth. */
public final class OcclusionMesh {
    private static java.nio.FloatBuffer vertices;
    private static final RandomSource RANDOM=RandomSource.create();
    public static final class Section {
        private final ScopeOcclusionPayload.Update update;
        private List<float[]> faces;
        public Section(ScopeOcclusionPayload.Update update){this.update=update;}
        public long revision(){return update.revision();}
        public SectionMask mask(){return update.mask();}
        List<float[]> faces(){if(faces==null)faces=mask().faces();return faces;}
    }
    public static final class Mask implements AutoCloseable {
        private final OcclusionDepthPass pass;private final boolean ready;
        private Mask(OcclusionDepthPass pass,boolean ready){this.pass=pass;this.ready=ready;}
        public boolean ready(){return ready;}
        public void close(){if(pass!=null)pass.close();}
    }
    private static void begin(){int capacity=ServerConfig.occlusion().maxFaces()*30;if(vertices==null||vertices.capacity()!=capacity)vertices=org.lwjgl.BufferUtils.createFloatBuffer(capacity);vertices.clear();}
    public static Mask render(Collection<Section> sections,PoseStack pose,Vec3 eye) {
        begin();var plants=new LinkedHashMap<BlockPos,BlockState>();
        for(var section:sections) {
            long key=section.update.key();var origin=new BlockPos(SectionPos.x(key)*16,SectionPos.y(key)*16,SectionPos.z(key)*16);
            pose.pushPose();
            try {
                pose.translate(origin.getX()-eye.x,origin.getY()-eye.y,origin.getZ()-eye.z);
                for(var face:section.faces()) {if(vertices.remaining()<30)return new Mask(null,false);emit(face,pose.last().pose());}
            } finally {pose.popPose();}
            for(var entry:section.mask().plants().entrySet()) {int i=entry.getKey();plants.put(origin.offset(i&15,i>>8,(i>>4)&15),Block.stateById(entry.getValue()));}
        }
        if(!plants(plants,pose,eye))return new Mask(null,false);
        return draw();
    }
    public static Mask renderBlocks(Map<BlockPos,BlockState> blocks,PoseStack pose,Vec3 eye){begin();return plants(blocks,pose,eye)?draw():new Mask(null,false);}
    private static boolean plants(Map<BlockPos,BlockState> blocks,PoseStack pose,Vec3 eye) {
        var mc=Minecraft.getInstance();if(mc.level==null)return blocks.isEmpty();
        for(var entry:blocks.entrySet()) {
            var pos=entry.getKey();var state=entry.getValue();var model=mc.getBlockRenderer().getBlockModel(state);
            long seed=state.getSeed(pos);var offset=state.getOffset(mc.level,pos);pose.pushPose();
            try {
                pose.translate(pos.getX()-eye.x+offset.x,pos.getY()-eye.y+offset.y,pos.getZ()-eye.z+offset.z);
                RANDOM.setSeed(seed);
                for(var layer:model.getRenderTypes(state,RANDOM,ModelData.EMPTY))for(int face=0;face<=6;face++) {
                    RANDOM.setSeed(seed);
                    for(var quad:model.getQuads(state,face==6?null:Direction.values()[face],RANDOM,ModelData.EMPTY,layer)) {
                        if(vertices.remaining()<30)return false;
                        var data=quad.getVertices();int stride=data.length/4;var position=new Vector3f();
                        for(int v:new int[]{0,1,2,0,2,3}) {
                            int i=v*stride;pose.last().pose().transformPosition(Float.intBitsToFloat(data[i]),Float.intBitsToFloat(data[i+1]),Float.intBitsToFloat(data[i+2]),position);
                            vertices.put(position.x).put(position.y).put(position.z).put(Float.intBitsToFloat(data[i+4])).put(Float.intBitsToFloat(data[i+5]));
                        }
                    }
                }
            } finally {pose.popPose();}
        }
        return true;
    }
    private static void emit(float[] face,Matrix4f pose) {
        var position=new Vector3f();for(int v:new int[]{0,1,2,0,2,3}) {
            pose.transformPosition(face[v*3],face[v*3+1],face[v*3+2],position);
            vertices.put(position.x).put(position.y).put(position.z).put(-1).put(-1);
        }
    }
    private static Mask draw() {
        vertices.flip();if(!vertices.hasRemaining()||ShaderCompat.shadowPass())return new Mask(null,true);
        var matrix=new Matrix4f(RenderSystem.getProjectionMatrix()).mul(RenderSystem.getModelViewMatrix());
        var pass=new OcclusionDepthPass(vertices,matrix);
        try {pass.draw(vertices,matrix,Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getId());return new Mask(pass,true);}
        catch(RuntimeException error){pass.close();throw error;}
    }
    public static void clear(){OcclusionDepthPass.destroy();vertices=null;}
}
