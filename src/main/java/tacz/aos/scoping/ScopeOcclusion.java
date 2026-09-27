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
import net.minecraft.world.level.chunk.LevelChunk;
import tacz.aos.network.PacketDelivery;
import tacz.aos.config.ServerConfig;
import tacz.aos.network.ScopeOcclusionPayload;
import tacz.aos.occlusion.*;

/** Bounded, live target-side visual geometry. Does not traverse the distant corridor or request chunks. */
public final class ScopeOcclusion {
    private static final AABB FULL_BLOCK=new AABB(0,0,0,1,1,1);
    private record Sent(ScopeOcclusionPayload payload,long tick) {}
    public record Plant(BlockPos pos, BlockState state) {}
    public record Geometry(List<OccluderBox> boxes, List<Plant> plants, boolean complete) {}
    private static final Map<UUID,Sent> sent=new HashMap<>();
    private static final Map<UUID,Integer> cursors=new HashMap<>();
    private static int remaining;
    private static long tick;
    private ScopeOcclusion(){}
    public static void beginTick(long time,Set<UUID> online) {
        tick=time;remaining=ServerConfig.occlusion().cellsPerTick();sent.keySet().removeIf(id->!online.contains(id));cursors.keySet().retainAll(online);
    }
    public static void clear(){sent.clear();cursors.clear();remaining=0;}
    public static void update(ServerPlayer player,List<? extends Entity> targets) {
        var cfg=ServerConfig.occlusion();var entries=new ArrayList<ScopeOcclusionPayload.Target>();
        int[] budget={Math.min(remaining,cfg.cellsPerPlayer())};int initial=budget[0],boxes=0,plants=0;
        var reader=new Resident(player.serverLevel(),budget);
        var cores=ScopeActivity.INSTANCE.observationBounds(player);
        // ScopeSubscriptions is aim-sorted. Geometry refresh must not inherit that priority.
        var ordered=new ArrayList<Entity>(targets);
        ordered.sort(Comparator.comparingInt(Entity::getId));
        if(!ordered.isEmpty())Collections.rotate(ordered,-Math.floorMod(cursors.merge(player.getUUID(),1,Integer::sum)-1,ordered.size()));
        if(cfg.enabled())for(var target:ordered) {
            if(budget[0]<=0 || boxes>=cfg.boxesPerPlayer()) {
                entries.add(new ScopeOcclusionPayload.Target(target.getId(),target.getUUID(),target.blockPosition(),List.of(),List.of(),false));
                continue;
            }
            var pos=target.chunkPosition();
            var neighborhood=new AABB((pos.x-2)*16,player.level().getMinBuildHeight(),(pos.z-2)*16,
                    (pos.x+3)*16,player.level().getMaxBuildHeight(),(pos.z+3)*16);
            for(var core:cores)if(core.intersects(target.getBoundingBox()))neighborhood=neighborhood.minmax(core);
            // Include ordinary interpolation between pose packets in this live geometric query.
            // This is not a cached readiness envelope and cannot revoke an entity subscription.
            var geometry=collectGeometry(reader,player.getEyePosition(),target.getBoundingBox().inflate(2,1,2),cfg.radius(),
                    Math.min(ScopeOcclusionPayload.MAX_PER_TARGET,cfg.boxesPerPlayer()-boxes),
                    Math.min(ScopeOcclusionPayload.MAX_TEXTURED_PER_TARGET,ScopeOcclusionPayload.MAX_TEXTURED-plants),neighborhood);
            var origin=target.blockPosition();
            entries.add(new ScopeOcclusionPayload.Target(target.getId(),target.getUUID(),origin,
                    geometry.boxes.stream().map(b->b.move(-origin.getX(),-origin.getY(),-origin.getZ())).toList(),
                    geometry.plants.stream().map(p->new ScopeOcclusionPayload.Textured(p.pos.subtract(origin),p.state)).toList(),geometry.complete));
            boxes+=geometry.boxes.size();plants+=geometry.plants.size();
        }
        remaining-=initial-budget[0];
        entries.sort(Comparator.comparingInt(ScopeOcclusionPayload.Target::id)); // Scheduling alone must not create packet churn.
        var payload=new ScopeOcclusionPayload(player.level().dimension().location(),entries);var previous=sent.get(player.getUUID());
        if(previous==null || !previous.payload.equals(payload) || tick-previous.tick>=20) {
            PacketDelivery.sendToPlayer(player,payload);sent.put(player.getUUID(),new Sent(payload,tick));
        }
    }
    public static List<OccluderBox> collect(ServerLevel level,Vec3 eye,AABB entity,int radius,int[] budget,int limit) {
        return collectGeometry(level,eye,entity,radius,budget,limit,0).boxes;
    }
    public static Geometry collectGeometry(ServerLevel level,Vec3 eye,AABB entity,int radius,int[] budget,int limit,int plantLimit) {
        return collectGeometry(new Resident(level,budget),eye,entity,radius,limit,plantLimit,null);
    }
    private static Geometry collectGeometry(Resident getter,Vec3 eye,AABB entity,int radius,int limit,int plantLimit,AABB neighborhood) {
        getter.exhausted=false;getter.incomplete=false;
        var center=entity.getCenter();var forward=eye.subtract(center).normalize();
        var end=center.add(forward.scale(Math.min(radius,eye.distanceTo(center))));
        // Thin local corridor encloses the target silhouette and nearby cover, with a motion margin.
        var area=entity.minmax(entity.move(end.subtract(center))).inflate(.75);
        // Cover the full selected core plus the entity's 5x5 neighborhood (3x3 plus a guard ring), not just 24 blocks.
        // Clamp before reading: the expanded corridor must not require any new terrain tickets.
        if(neighborhood!=null)area=area.intersect(neighborhood);
        var boxes=new ArrayList<OccluderBox>();var plants=new ArrayList<Plant>();
        var bounds=entity.inflate(.5);
        // The enclosing axis-aligned box grows dramatically for diagonal/elevated views. Only cells
        // intersecting the perspective cone can affect this entity, so don't read its unrelated corners.
        var sides=ShadowClipper.shadow(new OccluderBox(bounds.minX-eye.x,bounds.minY-eye.y,bounds.minZ-eye.z,
                bounds.maxX-eye.x,bounds.maxY-eye.y,bounds.maxZ-eye.z)).stream().filter(p->p.w()==0).toList();
        outer:for(int y=(int)Math.floor(area.minY);y<Math.ceil(area.maxY);y++)
            for(int z=(int)Math.floor(area.minZ);z<Math.ceil(area.maxZ);z++) {
                int run=Integer.MIN_VALUE;int xmax=(int)Math.ceil(area.maxX)-1;
                for(int x=(int)Math.floor(area.minX);x<=xmax+1;x++) {
                    boolean full=false;
                    if(x<=xmax && mayOcclude(sides,eye,x,y,z)) {
                        var pos=new BlockPos(x,y,z);var state=getter.getBlockState(pos);
                        if(getter.exhausted) {
                            if(run!=Integer.MIN_VALUE)boxes.add(new OccluderBox(run,y,z,x,y+1,z+1));
                            break outer;
                        }
                        if(plantLimit>0 && OcclusionShapes.textured(state)
                                && intersectsShadow(new OccluderBox(x-.5,y-.5,z-.5,x+1.5,y+1.5,z+1.5),eye,bounds))
                            plants.add(new Plant(pos,state));
                        var parts=OcclusionShapes.boxes(state,getter,pos);
                        if(parts.size()==1 && parts.get(0).equals(FULL_BLOCK))full=true;
                        else for(var a:parts)boxes.add(new OccluderBox(a.minX+x,a.minY+y,a.minZ+z,a.maxX+x,a.maxY+y,a.maxZ+z));
                    }
                    if(full){if(run==Integer.MIN_VALUE)run=x;}
                    else if(run!=Integer.MIN_VALUE){boxes.add(new OccluderBox(run,y,z,x,y+1,z+1));run=Integer.MIN_VALUE;}
                    if(boxes.size()>2048){getter.incomplete=true;break outer;}
                }
            }
        // Merge identical adjacent row spans vertically and along Z, before enforcing the network cap.
        boxes=merge(boxes,1);boxes=merge(boxes,2);
        var selectedBoxes=boxes.stream().filter(b->intersectsShadow(b,eye,bounds))
                .sorted(Comparator.comparingDouble(b->new AABB(b.minX(),b.minY(),b.minZ(),b.maxX(),b.maxY(),b.maxZ()).distanceToSqr(center)))
                .toList();
        var selectedPlants=plants.stream().sorted(Comparator.comparingDouble(p->Vec3.atCenterOf(p.pos).distanceToSqr(center)))
                .toList();
        return new Geometry(selectedBoxes.stream().limit(limit).toList(),selectedPlants.stream().limit(plantLimit).toList(),
                !getter.exhausted && !getter.incomplete && selectedBoxes.size()<=limit && selectedPlants.size()<=plantLimit);
    }
    private static boolean mayOcclude(List<ShadowClipper.Plane> sides,Vec3 eye,int x,int y,int z) {
        // Include common model offsets and shapes protruding into adjacent cells (fences, vegetation).
        for(var p:sides) {
            double px=(p.x()>=0?x+2:x-1)-eye.x,py=(p.y()>=0?y+2:y-1)-eye.y,pz=(p.z()>=0?z+2:z-1)-eye.z;
            if(p.x()*px+p.y()*py+p.z()*pz < -1e-7)return false;
        }
        return true;
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
    /** One refresh shares real cell reads between overlapping targets. Never requests or loads a chunk. */
    private static final class Resident implements BlockGetter {
        private final ServerLevel level;
        private final int[] budget;
        private final Map<Long,BlockState> cells=new HashMap<>();
        private final Map<Long,LevelChunk> chunks=new HashMap<>();
        private boolean exhausted,incomplete;
        Resident(ServerLevel level,int[] budget){this.level=level;this.budget=budget;}
        private LevelChunk observe(BlockPos pos) {
            long key=net.minecraft.world.level.ChunkPos.asLong(pos.getX()>>4,pos.getZ()>>4);
            if(!chunks.containsKey(key))chunks.put(key,level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4));
            var chunk=chunks.get(key);
            return chunk;
        }
        public BlockState getBlockState(BlockPos pos) {
            if(level.isOutsideBuildHeight(pos))return Blocks.AIR.defaultBlockState();
            var chunk=observe(pos);
            if(chunk==null)incomplete=true; // Unknown is not a confirmed transparent cell.
            long key=pos.asLong();var known=cells.get(key);
            if(known!=null)return known;
            if(budget[0]<=0){exhausted=true;return Blocks.AIR.defaultBlockState();}
            budget[0]--;
            if(chunk==null)known=Blocks.AIR.defaultBlockState();
            else known=chunk.getBlockState(pos);
            cells.put(key,known);return known;
        }
        public net.minecraft.world.level.material.FluidState getFluidState(BlockPos p){return getBlockState(p).getFluidState();}
        public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos p){var c=level.getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4);return c==null?null:c.getBlockEntity(p,net.minecraft.world.level.chunk.LevelChunk.EntityCreationType.CHECK);}
        public int getHeight(){return level.getHeight();}public int getMinBuildHeight(){return level.getMinBuildHeight();}
    }
}
