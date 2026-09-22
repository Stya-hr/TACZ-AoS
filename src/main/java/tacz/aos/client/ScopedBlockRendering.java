package tacz.aos.client;

import com.tacz.guns.block.TargetBlock;
import com.tacz.guns.block.entity.TargetBlockEntity;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.phys.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.client.event.*;
import tacz.aos.TaczArtOfSniping;
import tacz.aos.config.ServerConfig;
import tacz.aos.network.*;
import java.util.*;
import net.minecraftforge.event.TickEvent;

/** Isolated render data, not fake client chunks or world block replacements. */
@EventBusSubscriber(modid=TaczArtOfSniping.MOD_ID,value=Dist.CLIENT)
public final class ScopedBlockRendering {
    private record Entry(TargetBlockEntity visual,int light,net.minecraft.nbt.CompoundTag data) {}
    private static final Map<BlockPos,Entry> targets=new LinkedHashMap<>();
    private static ClientLevel level; private static long tick,received; private static int draws;
    public static int draws(){return draws;}
    public static int count(){return targets.size();}
    public static TargetBlockEntity visual(BlockPos pos){var e=targets.get(pos);return e==null?null:e.visual;}
    public static void setup(FMLClientSetupEvent event){event.enqueueWork(()->ScopeNetworking.setBlockTargetReceiver(ScopedBlockRendering::receive));}
    private static void receive(ScopeBlockTargetsPayload payload) {
        var mc=Minecraft.getInstance();if(mc.level==null || !mc.level.dimension().location().equals(payload.dimension()))return;
        if(level!=mc.level){targets.clear();level=mc.level;}
        var next=new LinkedHashMap<BlockPos,Entry>();
        for(var t:payload.targets()) {
            var old=targets.get(t.pos());boolean metadataChanged=old==null || !old.data.equals(t.data());
            var visual=metadataChanged?new TargetBlockEntity(t.pos(),t.state()):old.visual;
            if(old!=null && metadataChanged){visual.rot=old.visual.rot;visual.oRot=old.visual.oRot;}
            visual.setLevel(level);visual.setBlockState(t.state());
            if(metadataChanged)visual.load(t.data());
            next.put(t.pos(),new Entry(visual,t.light(),t.data()));
        }
        targets.clear();targets.putAll(next);received=tick;
    }
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) return;
        tick++;var mc=Minecraft.getInstance();
        if(level!=mc.level || tick-received>40){targets.clear();level=mc.level;}
        if(level==null)return;
        for(var entry:targets.values()) TargetBlockEntity.clientTick(level,entry.visual.getBlockPos(),entry.visual.getBlockState(),entry.visual);
    }
    /** A visible remote target can itself supply the next sight-line range, including its current fallen shape. */
    public static Vec3 pick(Vec3 eye,Vec3 direction,double max,Vec3 previous) {
        if(!ScopedRendering.active() || level!=Minecraft.getInstance().level)return previous;
        Vec3 best=previous;double distance=best==null?max:eye.distanceTo(best);var end=eye.add(direction.scale(distance));
        for(var entry:targets.values()) for(int half=0;half<2;half++) {
            var pos=entry.visual.getBlockPos().above(half);var state=entry.visual.getBlockState().setValue(TargetBlock.HALF,half==0?DoubleBlockHalf.LOWER:DoubleBlockHalf.UPPER);
            var hit=state.getShape(EmptyBlockGetter.INSTANCE,pos).clip(eye,end,pos);
            if(hit!=null && eye.distanceTo(hit.getLocation())<distance){best=hit.getLocation();distance=eye.distanceTo(best);}
        }
        return best;
    }
    @SubscribeEvent public static void render(RenderLevelStageEvent event) {
        if(event.getStage()!=RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES || ShaderCompat.shadowPass())return;
        draws=0;var mc=Minecraft.getInstance();
        if(!ScopedRendering.active() || mc.level!=level || level==null || mc.player==null)return;
        float fogStart=RenderSystem.getShaderFogStart(),fogEnd=RenderSystem.getShaderFogEnd();
        var buffers=mc.renderBuffers().bufferSource();var stack=event.getPoseStack();var eye=event.getCamera().getPosition();
        try {
            // TACZ's BedrockModel uses this global buffer source internally; flush it while our fog is active.
            buffers.endBatch();RenderSystem.setShaderFogStart(ServerConfig.current().maxDistance());RenderSystem.setShaderFogEnd(ServerConfig.current().maxDistance()+64);
            for(var entry:targets.values()) {
                var target=entry.visual;var pos=target.getBlockPos();
                if(Vec3.atCenterOf(pos).distanceToSqr(eye)>Math.pow(ServerConfig.current().maxDistance(),2)
                        || !event.getFrustum().isVisible(new AABB(pos).expandTowards(0,1,0).inflate(.5)))continue;
                var chunk=level.getChunkSource().getChunk(pos.getX()>>4,pos.getZ()>>4,ChunkStatus.FULL,false);
                if(chunk!=null && chunk.getBlockEntity(pos,net.minecraft.world.level.chunk.LevelChunk.EntityCreationType.CHECK) instanceof TargetBlockEntity)continue; // Native renderer owns it.
                var renderer=mc.getBlockEntityRenderDispatcher().getRenderer(target);if(renderer==null)continue;
                stack.pushPose();
                try {
                    stack.translate(pos.getX()-eye.x,pos.getY()-eye.y,pos.getZ()-eye.z);
                    renderer.render(target,event.getPartialTick(),stack,buffers,entry.light,
                            net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
                    draws++;
                } finally {stack.popPose();}
            }
            buffers.endBatch();
        } finally {RenderSystem.setShaderFogStart(fogStart);RenderSystem.setShaderFogEnd(fogEnd);}
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event){targets.clear();level=null;tick=received=0;draws=0;}
}
