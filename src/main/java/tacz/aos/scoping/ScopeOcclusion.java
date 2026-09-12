package tacz.aos.scoping;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.network.PacketDistributor;
import tacz.aos.config.ServerConfig;
import tacz.aos.network.ScopeOcclusionPayload;
import tacz.aos.occlusion.*;

/** Bounded, live target-side visual geometry. Does not traverse the distant corridor or request chunks. */
public final class ScopeOcclusion {
    private record Sent(ScopeOcclusionPayload payload,long tick) {}
    private static final Map<UUID,Sent> sent=new HashMap<>();
    private static int remaining;
    private static long tick;
    private ScopeOcclusion(){}
    public static void beginTick(long time,Set<UUID> online) {
        tick=time;remaining=ServerConfig.occlusion().cellsPerTick();sent.keySet().removeIf(id->!online.contains(id));
    }
    public static void clear(){sent.clear();remaining=0;}
    public static void update(ServerPlayer player,List<? extends Entity> targets) {
        var cfg=ServerConfig.occlusion();var entries=new ArrayList<ScopeOcclusionPayload.Target>();
        int[] budget={Math.min(remaining,cfg.cellsPerPlayer())};int initial=budget[0],boxes=0;
        if(cfg.enabled())for(var target:targets) {
            if(budget[0]<=0 || boxes>=cfg.boxesPerPlayer())break;
            // Native terrain already supplies exact occlusion here; do not duplicate its work.
            if(((tacz.aos.mixin.ChunkMapAccessor)player.serverLevel().getChunkSource().chunkMap)
                    .tacz_aos$isChunkTracked(player,target.chunkPosition().x,target.chunkPosition().z))continue;
            var geometry=collect(player.serverLevel(),player.getEyePosition(),target.getBoundingBox(),cfg.radius(),budget,
                    Math.min(ScopeOcclusionPayload.MAX_PER_TARGET,cfg.boxesPerPlayer()-boxes));
            var origin=target.blockPosition();
            if(!geometry.isEmpty())entries.add(new ScopeOcclusionPayload.Target(target.getId(),target.getUUID(),origin,
                    geometry.stream().map(b->b.move(-origin.getX(),-origin.getY(),-origin.getZ())).toList()));
            boxes+=geometry.size();
        }
        remaining-=initial-budget[0];
        var payload=new ScopeOcclusionPayload(player.level().dimension().location(),entries);var previous=sent.get(player.getUUID());
        if(previous==null || !previous.payload.equals(payload) || tick-previous.tick>=20) {
            PacketDistributor.sendToPlayer(player,payload);sent.put(player.getUUID(),new Sent(payload,tick));
        }
    }
    public static List<OccluderBox> collect(ServerLevel level,Vec3 eye,AABB entity,int radius,int[] budget,int limit) {
        var center=entity.getCenter();var forward=eye.subtract(center).normalize();
        var end=center.add(forward.scale(Math.min(radius,eye.distanceTo(center))));
        // Thin local corridor encloses the target silhouette and nearby cover, with a motion margin.
        double pad=Math.min(4,Math.max(entity.getXsize(),Math.max(entity.getYsize(),entity.getZsize()))*.5+.75);
        var area=new AABB(center,end).inflate(pad);
        var getter=new Resident(level);var boxes=new ArrayList<OccluderBox>();
        outer:for(int y=(int)Math.floor(area.minY);y<=Math.floor(area.maxY);y++)
            for(int z=(int)Math.floor(area.minZ);z<=Math.floor(area.maxZ);z++) {
                int run=Integer.MIN_VALUE;int xmax=(int)Math.floor(area.maxX);
                for(int x=(int)Math.floor(area.minX);x<=xmax+1;x++) {
                    boolean full=false;
                    if(x<=xmax) {
                        if(budget[0]--<=0){budget[0]=0;break outer;}
                        var pos=new BlockPos(x,y,z);var state=getter.getBlockState(pos);
                        if(opaque(state)) {
                            var shape=state.getShape(getter,pos);
                            if(net.minecraft.world.level.block.Block.isShapeFullBlock(shape))full=true;
                            else {
                                var parts=shape.toAabbs();
                                if(parts.size()<=8)for(var a:parts)boxes.add(new OccluderBox(a.minX+x,a.minY+y,a.minZ+z,a.maxX+x,a.maxY+y,a.maxZ+z));
                            }
                        }
                    }
                    if(full){if(run==Integer.MIN_VALUE)run=x;}
                    else if(run!=Integer.MIN_VALUE){boxes.add(new OccluderBox(run,y,z,x,y+1,z+1));run=Integer.MIN_VALUE;}
                    if(boxes.size()>2048)break outer;
                }
            }
        // Merge identical adjacent row spans vertically and along Z, before enforcing the network cap.
        boxes=merge(boxes,1);boxes=merge(boxes,2);
        var bounds=entity.inflate(.2);
        return boxes.stream().filter(b->intersectsShadow(b,eye,bounds))
                .sorted(Comparator.comparingDouble(b->new AABB(b.minX(),b.minY(),b.minZ(),b.maxX(),b.maxY(),b.maxZ()).distanceToSqr(center)))
                .limit(limit).toList();
    }
    private static boolean opaque(BlockState s) {
        var b=s.getBlock();
        if(s.isAir() || s.getRenderShape()!=RenderShape.MODEL || b instanceof HalfTransparentBlock || b instanceof LeavesBlock
                || b instanceof StainedGlassPaneBlock)return false;
        return s.canOcclude() || b instanceof FenceBlock || b instanceof WallBlock || b instanceof DoorBlock
                || b instanceof TrapDoorBlock || b instanceof StairBlock || b instanceof SlabBlock;
    }
    private static ArrayList<OccluderBox> merge(List<OccluderBox> source,int axis) {
        var groups=new HashMap<List<Double>,List<OccluderBox>>();
        for(var b:source){var key=axis==1?List.of(b.minX(),b.maxX(),b.minZ(),b.maxZ()):List.of(b.minX(),b.maxX(),b.minY(),b.maxY());
            groups.computeIfAbsent(key,k->new ArrayList<>()).add(b);}
        var out=new ArrayList<OccluderBox>();
        for(var group:groups.values()) {
            group.sort(Comparator.comparingDouble(b->axis==1?b.minY():b.minZ()));OccluderBox run=null;
            for(var b:group){if(run!=null && (axis==1?run.maxY()==b.minY():run.maxZ()==b.minZ()))
                    run=axis==1?new OccluderBox(run.minX(),run.minY(),run.minZ(),run.maxX(),b.maxY(),run.maxZ()):new OccluderBox(run.minX(),run.minY(),run.minZ(),run.maxX(),run.maxY(),b.maxZ());
                else{if(run!=null)out.add(run);run=b;}}
            if(run!=null)out.add(run);
        }
        return out;
    }
    private static boolean intersectsShadow(OccluderBox b,Vec3 eye,AABB target) {
        var planes=ShadowClipper.shadow(b.move(-eye.x,-eye.y,-eye.z));if(planes.isEmpty())return false;
        for(var p:planes) {
            double x=(p.x()>=0?target.maxX:target.minX)-eye.x,y=(p.y()>=0?target.maxY:target.minY)-eye.y,z=(p.z()>=0?target.maxZ:target.minZ)-eye.z;
            if(p.x()*x+p.y()*y+p.z()*z+p.w()<0)return false;
        }
        return true;
    }
    private record Resident(ServerLevel level) implements BlockGetter {
        public BlockState getBlockState(BlockPos pos){var c=level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);return c==null?Blocks.AIR.defaultBlockState():c.getBlockState(pos);}
        public net.minecraft.world.level.material.FluidState getFluidState(BlockPos p){return getBlockState(p).getFluidState();}
        public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos p){var c=level.getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4);return c==null?null:c.getBlockEntity(p,net.minecraft.world.level.chunk.LevelChunk.EntityCreationType.CHECK);}
        public int getHeight(){return level.getHeight();}public int getMinBuildHeight(){return level.getMinBuildHeight();}
    }
}
