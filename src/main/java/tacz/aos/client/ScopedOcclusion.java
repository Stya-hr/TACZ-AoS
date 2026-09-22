package tacz.aos.client;

import java.util.*;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import tacz.aos.TaczArtOfSniping;
import tacz.aos.config.ServerConfig;
import tacz.aos.network.*;
import tacz.aos.occlusion.ShadowClipper;

/** Clips only extra entity polygons. Existing textures, native materials, and world depth remain intact. */
@EventBusSubscriber(modid=TaczArtOfSniping.MOD_ID,value=Dist.CLIENT)
public final class ScopedOcclusion {
    private static ClientLevel level;
    private static long received;
    private static final Map<Integer,ScopeOcclusionPayload.Target> targets=new HashMap<>();
    private static ShadowClipper.Budget budget=new ShadowClipper.Budget(0);
    private static int inputPolygons,outputPolygons;
    public record Stats(int input,int output,int clipped) {}
    private static final Map<Integer,Stats> stats=new HashMap<>();
    public static Stats stats(int id){return stats.getOrDefault(id,new Stats(0,0,0));}
    public static int boxCount(int id){var t=targets.get(id);return t==null?0:t.boxes().size();}
    public static int inputPolygons(){return inputPolygons;}
    public static int outputPolygons(){return outputPolygons;}
    private ScopedOcclusion(){}
    public static void setup(FMLClientSetupEvent event){event.enqueueWork(()->ScopeNetworking.setOcclusionReceiver(ScopedOcclusion::receive));}
    private static void receive(ScopeOcclusionPayload payload) {
        var mc=Minecraft.getInstance();if(mc.level==null || !mc.level.dimension().location().equals(payload.dimension()))return;
        level=mc.level;received=level.getGameTime();targets.clear();for(var target:payload.targets())targets.put(target.id(),target);
    }
    public static void beginFrame() {
        stats.clear();inputPolygons=outputPolygons=0;budget=new ShadowClipper.Budget(ServerConfig.occlusion().planeTestsPerFrame());
        var mc=Minecraft.getInstance();if(level!=mc.level || level==null || level.getGameTime()-received>40){targets.clear();level=mc.level;}
    }
    public static ClippedSource forEntity(Entity entity,Vec3 camera,Matrix4f pose,MultiBufferSource source) {
        var t=targets.get(entity.getId());
        if(!ServerConfig.occlusion().enabled() || t==null || !t.uuid().equals(entity.getUUID()) || level!=entity.level() || budget.remaining()==0)return null;
        var inverseTranspose=new Matrix4f(pose).invert().transpose();
        var shadows=new ArrayList<List<ShadowClipper.Plane>>();
        for(var box:t.boxes()) {
            var relative=box.move(t.origin().getX()-camera.x,t.origin().getY()-camera.y,t.origin().getZ()-camera.z);
            var planes=new ArrayList<ShadowClipper.Plane>();
            for(var p:ShadowClipper.shadow(relative)) {
                // VertexConsumer positions already include PoseStack transforms. Transform the clipping planes too.
                var v=inverseTranspose.transform(new Vector4f((float)p.x(),(float)p.y(),(float)p.z(),(float)p.w()));
                planes.add(new ShadowClipper.Plane(v.x,v.y,v.z,v.w));
            }
            if(!planes.isEmpty())shadows.add(planes);
        }
        return shadows.isEmpty()?null:new ClippedSource(source,shadows,budget,entity.getId());
    }
    public static final class ClippedSource implements MultiBufferSource,AutoCloseable {
        private final MultiBufferSource source;
        private final int entityId;
        private int inputCount,outputCount,clippedCount;
        private final List<List<ShadowClipper.Plane>> shadows;
        private final ShadowClipper.Budget work;
        private final Map<RenderType,Consumer> consumers=new LinkedHashMap<>();
        public ClippedSource(MultiBufferSource source,List<List<ShadowClipper.Plane>> shadows,ShadowClipper.Budget work,int entityId){this.source=source;this.shadows=shadows;this.work=work;this.entityId=entityId;}
        public VertexConsumer getBuffer(RenderType type) {
            if(type.mode()!=VertexFormat.Mode.QUADS && type.mode()!=VertexFormat.Mode.TRIANGLES){close();return source.getBuffer(type);}
            return consumers.computeIfAbsent(type,t->new Consumer(t));
        }
        public void close(){for(var c:consumers.values())c.flush();stats.put(entityId,new Stats(inputCount,outputCount,clippedCount));}
        private final class Consumer implements VertexConsumer {
            private final RenderType type;
            private final int size;
            private final List<ShadowClipper.Vertex> vertices=new ArrayList<>(4);
            private float[] attr;
            private int red=255,green=255,blue=255,alpha=255;
            Consumer(RenderType type){this.type=type;size=type.mode()==VertexFormat.Mode.QUADS?4:3;}
            public VertexConsumer vertex(double x,double y,double z) {
                if(vertices.size()==size)flush();
                attr=new float[]{red,green,blue,alpha,0,0,0,0,0,0,0,1,0};
                vertices.add(new ShadowClipper.Vertex(x,y,z,attr));return this;
            }
            public VertexConsumer color(int r,int g,int b,int a){attr[0]=r;attr[1]=g;attr[2]=b;attr[3]=a;return this;}
            public VertexConsumer uv(float u,float v){attr[4]=u;attr[5]=v;return this;}
            public VertexConsumer overlayCoords(int u,int v){attr[6]=u;attr[7]=v;return this;}
            public VertexConsumer uv2(int u,int v){attr[8]=u;attr[9]=v;return this;}
            public VertexConsumer normal(float x,float y,float z){attr[10]=x;attr[11]=y;attr[12]=z;return this;}
            public void endVertex() { if (vertices.size() == size) flush(); }
            public void defaultColor(int r,int g,int b,int a) {red=r;green=g;blue=b;alpha=a;}
            public void unsetDefaultColor() {red=green=blue=alpha=255;}
            void flush() {
                if(vertices.size()!=size){vertices.clear();return;}
                inputPolygons++;inputCount++;
                var output=ShadowClipper.clip(List.copyOf(vertices),shadows,work);
                if(!output.equals(List.of(vertices)))clippedCount++;
                if(!output.isEmpty()) {
                    var sink=source.getBuffer(type);
                    for(var polygon:output) {
                        if(size==4 && polygon.size()==4){for(var v:polygon)emit(sink,v);outputPolygons++;outputCount++;}
                        else for(int i=1;i+1<polygon.size();i++) {
                            emit(sink,polygon.get(0));emit(sink,polygon.get(i));emit(sink,polygon.get(i+1));
                            if(size==4)emit(sink,polygon.get(i+1));outputPolygons++;outputCount++;
                        }
                    }
                }
                vertices.clear();
            }
            private void emit(VertexConsumer sink,ShadowClipper.Vertex v) {
                var a=v.attributes();
                sink.vertex((float)v.x(),(float)v.y(),(float)v.z()).color(Math.round(a[0]),Math.round(a[1]),Math.round(a[2]),Math.round(a[3]))
                    .uv(a[4],a[5]).overlayCoords(Math.round(a[6]),Math.round(a[7])).uv2(Math.round(a[8]),Math.round(a[9])).normal(a[10],a[11],a[12]).endVertex();
            }
        }
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event){targets.clear();level=null;received=0;}
}
