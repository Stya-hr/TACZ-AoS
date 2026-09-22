package tacz.aos.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.phys.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import tacz.aos.network.PacketDelivery;
import org.joml.Matrix4f;
import tacz.aos.TaczArtOfSniping;
import tacz.aos.config.ServerConfig;
import tacz.aos.network.ScopeAimPayload;
import static org.lwjgl.opengl.GL33C.*;

/** Native loaded-block picking plus asynchronous picking of Voxy's own terrain depth.
 * Three tiny PBOs, nonblocking fences, no terrain cache and no Voxy class linkage.
 */
@EventBusSubscriber(modid=TaczArtOfSniping.MOD_ID,value=Dist.CLIENT)
public final class VisibleTargetReporter {
    private static final class Read {
        int buffer; long fence, tick; ClientLevel level; Matrix4f inverse;
        Vec3 camera,eye,direction; float clear; boolean zeroToOne; float nx,ny;
    }
    private static final Read[] ring={new Read(),new Read(),new Read()};
    private static ClientLevel level;
    private static long sent=-100, captured=-100;
    private static Vec3 depthPoint,depthEye,depthDirection;
    private static long depthTick;
    public static double lastDistance=-1;
    private VisibleTargetReporter(){}
    @SubscribeEvent public static void render(RenderLevelStageEvent event) {
        if(event.getStage()!=RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        var mc=Minecraft.getInstance();
        if(level!=mc.level) {reset();level=mc.level;}
        if(level==null || mc.player==null || !mc.options.getCameraType().isFirstPerson()
                || !TaczClientBridge.evaluateForDisplay(mc.player).eligible()) {depthPoint=null;return;}
        long tick=level.getGameTime();
        if(ModList.get().isLoaded("voxy")) poll(tick);
        if(tick-sent<2) return;
        Vec3 eye=mc.player.getEyePosition(),direction=mc.player.getViewVector(1).normalize();
        int range=ServerConfig.current().maxDistance();
        var nativeHit=nativeTarget(eye,direction,range);
        Vec3 target=nativeHit==null?null:nativeHit.getLocation();
        if(depthPoint!=null && tick-depthTick<=6 && depthEye.distanceToSqr(eye)<1
                && depthDirection.dot(direction)>.99999) {
            double distance=eye.distanceTo(depthPoint);
            if(distance<=range && distance>.1 && (target==null || distance<eye.distanceTo(target))) target=depthPoint;
        }
        target=ScopedBlockRendering.pick(eye,direction,range,target);
        double distance=target==null?range:eye.distanceTo(target);
        // Remote entities already paired by this mod are valid candidates too. Unseen entities cannot be invented.
        var end=eye.add(direction.scale(distance));
        for(var entity:level.entitiesForRendering()) {
            if(entity==mc.player || entity.isRemoved() || !entity.isPickable() || entity.distanceToSqr(mc.player)>range*(double)range) continue;
            var hit=entity.getBoundingBox().clip(eye,end);
            if(hit.isPresent() && eye.distanceTo(hit.get())<distance) {target=hit.get();distance=eye.distanceTo(target);}
        }
        lastDistance=target==null?-1:eye.distanceTo(target);
        PacketDelivery.sendToServer(new ScopeAimPayload(level.dimension().location(),eye,direction,lastDistance));
        sent=tick;
    }
    private static BlockHitResult nativeTarget(Vec3 eye,Vec3 direction,int range) {
        var mc=Minecraft.getInstance();
        int cx=mc.player.chunkPosition().x,cz=mc.player.chunkPosition().z,view=mc.options.getEffectiveRenderDistance();
        BlockHitResult result=BlockGetter.traverseBlocks(eye,eye.add(direction.scale(range)),Boolean.TRUE,(ignored,pos)->{
            if(Math.abs((pos.getX()>>4)-cx)>view || Math.abs((pos.getZ()>>4)-cz)>view)
                return BlockHitResult.miss(eye,net.minecraft.core.Direction.UP,pos);
            var chunk=level.getChunkSource().getChunk(pos.getX()>>4,pos.getZ()>>4,ChunkStatus.FULL,false);
            if(chunk==null) return BlockHitResult.miss(eye,net.minecraft.core.Direction.UP,pos);
            var state=chunk.getBlockState(pos);
            return state.getShape(chunk,pos).clip(eye,eye.add(direction.scale(range)),pos);
        },ignored->BlockHitResult.miss(eye,net.minecraft.core.Direction.UP,BlockPos.containing(eye)));
        return result.getType()==HitResult.Type.BLOCK?result:null;
    }
    private static void poll(long tick) {
        int previous=glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
        try {
            for(var r:ring) {
                if(r.fence==0) continue;
                int result=glClientWaitSync(r.fence,0,0);
                if(result==GL_TIMEOUT_EXPIRED) continue;
                glDeleteSync(r.fence);r.fence=0;
                if(result==GL_WAIT_FAILED || r.level!=level || tick-r.tick>6) continue;
                glBindBuffer(GL_PIXEL_PACK_BUFFER,r.buffer);
                float[] data=new float[1];glGetBufferSubData(GL_PIXEL_PACK_BUFFER,0,data);
                float depth=data[0];
                if(r.tick<depthTick) continue;
                depthTick=r.tick;depthPoint=null;
                Vec3 point=tacz.aos.scoping.DepthProjection.unproject(depth,r.clear,r.nx,r.ny,r.zeroToOne,r.inverse,r.camera);
                if(point==null || point.subtract(r.eye).dot(r.direction)<=0) continue;
                depthPoint=point;depthEye=r.eye;depthDirection=r.direction;
            }
        } finally {glBindBuffer(GL_PIXEL_PACK_BUFFER,previous);}
    }
    /** Called at the end of the actual Voxy opaque terrain pass, before hands/post-processing.
     * Depth conventions belong to the producing pipeline, never the GL state left by another pass.
     */
    public static void captureTerrain(int framebuffer, int width, int height, Matrix4f mvp,
                                      Vec3 camera, boolean zeroToOne, float clear) {
        var mc=Minecraft.getInstance();
        if(level!=mc.level) {reset();level=mc.level;}
        if(level==null || mc.player==null || !mc.options.getCameraType().isFirstPerson()
                || !TaczClientBridge.evaluateForDisplay(mc.player).eligible()) return;
        long tick=level.getGameTime();
        if(tick==captured || width<=0 || height<=0)return;
        Read r=null;for(var entry:ring) if(entry.fence==0){r=entry;break;} if(r==null)return;
        int previous=glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING),fb=glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int row=glGetInteger(GL_PACK_ROW_LENGTH),skipRows=glGetInteger(GL_PACK_SKIP_ROWS),skipPixels=glGetInteger(GL_PACK_SKIP_PIXELS);
        try {
            if(r.buffer==0) r.buffer=glGenBuffers();
            glBindBuffer(GL_PIXEL_PACK_BUFFER,r.buffer);glBufferData(GL_PIXEL_PACK_BUFFER,4,GL_STREAM_READ);
            glPixelStorei(GL_PACK_ROW_LENGTH,0);glPixelStorei(GL_PACK_SKIP_ROWS,0);glPixelStorei(GL_PACK_SKIP_PIXELS,0);
            glBindFramebuffer(GL_READ_FRAMEBUFFER,framebuffer);
            int x=width/2,y=height/2;
            glReadPixels(x,y,1,1,GL_DEPTH_COMPONENT,GL_FLOAT,0L);
            r.fence=glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE,0);
            r.level=level;r.tick=tick;r.clear=clear;r.zeroToOne=zeroToOne;
            r.inverse=new Matrix4f(mvp).invert();
            r.camera=camera;r.eye=mc.player.getEyePosition();r.direction=mc.player.getViewVector(1).normalize();
            r.nx=2f*(x+.5f)/width-1;r.ny=2f*(y+.5f)/height-1;
            captured=tick;
        } finally {
            glBindBuffer(GL_PIXEL_PACK_BUFFER,previous);glBindFramebuffer(GL_READ_FRAMEBUFFER,fb);
            glPixelStorei(GL_PACK_ROW_LENGTH,row);glPixelStorei(GL_PACK_SKIP_ROWS,skipRows);glPixelStorei(GL_PACK_SKIP_PIXELS,skipPixels);
        }
    }
    private static void reset() {
        for(var r:ring){if(r.fence!=0)glDeleteSync(r.fence);if(r.buffer!=0)glDeleteBuffers(r.buffer);r.fence=0;r.buffer=0;r.level=null;}
        sent=captured=-100;depthPoint=null;depthTick=0;lastDistance=-1;
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event){reset();level=null;}
}
