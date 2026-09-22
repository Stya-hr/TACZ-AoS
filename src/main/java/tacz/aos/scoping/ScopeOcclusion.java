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
    private record Cached(ScopeOcclusionPayload.Target data, Vec3 eye, AABB coverage, long checked) {
        boolean usable(Entity target, Vec3 currentEye) {
            return data.uuid().equals(target.getUUID()) && tick-checked<=20 && eye.distanceToSqr(currentEye)<=.0625
                    && covers(target.getBoundingBox());
        }
        boolean covers(AABB b) { return b.minX>=coverage.minX && b.minY>=coverage.minY && b.minZ>=coverage.minZ
                && b.maxX<=coverage.maxX && b.maxY<=coverage.maxY && b.maxZ<=coverage.maxZ; }
    }
    private record Observations(ServerLevel level, Map<Integer,Cached> targets, Map<Integer,Long> attempts) {}
    private static final Map<UUID,Observations> observations=new HashMap<>();
    private static final Map<UUID,Sent> sent=new HashMap<>();
    private static int remaining;
    private static long tick;
    private ScopeOcclusion(){}
    public static void beginTick(long time,Set<UUID> online) {
        tick=time;remaining=ServerConfig.occlusion().cellsPerTick();sent.keySet().removeIf(id->!online.contains(id));
        observations.keySet().removeIf(id->!online.contains(id));
    }
    public static void clear(){sent.clear();observations.clear();remaining=0;}
    public static void update(ServerPlayer player,List<? extends Entity> targets) {
        var cfg=ServerConfig.occlusion();var entries=new ArrayList<ScopeOcclusionPayload.Target>();
        var state=observations.get(player.getUUID());
        if(state==null || state.level!=player.serverLevel()) {
            state=new Observations(player.serverLevel(),new HashMap<>(),new HashMap<>());observations.put(player.getUUID(),state);
        }
        var cache=state.targets;var attempts=state.attempts;
        var selected=new HashSet<Integer>();for(var target:targets)selected.add(target.getId());
        cache.keySet().retainAll(selected);attempts.keySet().retainAll(selected);
        int[] budget={Math.min(remaining,cfg.cellsPerPlayer())};int initial=budget[0];
        var reader=new Resident(player.serverLevel(),budget);
        var eye=player.getEyePosition();
        // Renew moving observations before they leave their verified volume, then use fair age order.
        var schedule=new ArrayList<Entity>(targets);
        schedule.sort(Comparator.<Entity>comparingInt(e->{var c=cache.get(e.getId());
                    return c!=null && (!c.usable(e,eye) || !c.covers(e.getBoundingBox().inflate(.75)))?0:1;})
                .thenComparingLong(e->attempts.getOrDefault(e.getId(),Long.MIN_VALUE))
                .thenComparingInt(Entity::getId));
        if(cfg.enabled())for(var target:schedule) {
            if(budget[0]<=0)break;
            attempts.put(target.getId(),tick);
            var coverage=motionCoverage(target);
            var geometry=collectGeometry(reader,eye,coverage,cfg.radius(),ScopeOcclusionPayload.MAX_PER_TARGET,
                    ScopeOcclusionPayload.MAX_TEXTURED_PER_TARGET);
            var origin=target.blockPosition();
            if(geometry.complete) {
                var data=new ScopeOcclusionPayload.Target(target.getId(),target.getUUID(),origin,
                        geometry.boxes.stream().map(b->b.move(-origin.getX(),-origin.getY(),-origin.getZ())).toList(),
                        geometry.plants.stream().map(p->new ScopeOcclusionPayload.Textured(p.pos.subtract(origin),p.state)).toList());
                cache.put(target.getId(),new Cached(data,eye,coverage,tick));
            }
            // A failed replacement does not invalidate a still-covered position. Packing below checks
            // identity, coverage, camera movement and age; no unchecked geometry becomes visible.
        }
        remaining-=initial-budget[0];
        int boxes=0,plants=0;
        // Stable packing prevents a change of aim from displacing already valid masks.
        schedule.sort(Comparator.comparingInt(Entity::getId));
        if(cfg.enabled())for(var target:schedule) {
            var known=cache.get(target.getId());
            if(known!=null && known.usable(target,eye) && boxes+known.data.boxes().size()<=cfg.boxesPerPlayer()
                    && plants+known.data.textured().size()<=ScopeOcclusionPayload.MAX_TEXTURED) {
                entries.add(known.data);boxes+=known.data.boxes().size();plants+=known.data.textured().size();
            } else entries.add(new ScopeOcclusionPayload.Target(target.getId(),target.getUUID(),target.blockPosition(),List.of(),List.of(),false));
        }
        if(!cfg.enabled())cache.clear();
        var payload=new ScopeOcclusionPayload(player.level().dimension().location(),entries);var previous=sent.get(player.getUUID());
        if(previous==null || !previous.payload.equals(payload) || tick-previous.tick>=20) {
            PacketDelivery.sendToPlayer(player,payload);sent.put(player.getUUID(),new Sent(payload,tick));
        }
    }
    private static AABB motionCoverage(Entity target) {
        // Cover normal walking during a skipped refresh, including client interpolation behind the server pose.
        // Faster entities get up to four blocks of horizontal look-ahead; this does not relax the proof.
        double horizontal=Math.min(4,Math.max(2,target.getDeltaMovement().horizontalDistance()*8));
        return target.getBoundingBox().inflate(horizontal,1,horizontal);
    }
    public static List<OccluderBox> collect(ServerLevel level,Vec3 eye,AABB entity,int radius,int[] budget,int limit) {
        return collectGeometry(level,eye,entity,radius,budget,limit,0).boxes;
    }
    public static Geometry collectGeometry(ServerLevel level,Vec3 eye,AABB entity,int radius,int[] budget,int limit,int plantLimit) {
        return collectGeometry(new Resident(level,budget),eye,entity,radius,limit,plantLimit);
    }
    private static Geometry collectGeometry(Resident getter,Vec3 eye,AABB entity,int radius,int limit,int plantLimit) {
        getter.incomplete=false;getter.exhausted=false;
        var center=entity.getCenter();var forward=eye.subtract(center).normalize();
        var end=center.add(forward.scale(Math.min(radius,eye.distanceTo(center))));
        // Thin local corridor encloses the target silhouette and nearby cover, with a motion margin.
        double pad=Math.min(4,Math.max(entity.getXsize(),Math.max(entity.getYsize(),entity.getZsize()))*.5+.75);
        var area=new AABB(center,end).inflate(pad);
        var boxes=new ArrayList<OccluderBox>();var plants=new ArrayList<Plant>();
        var bounds=entity.inflate(.5);
        outer:for(int y=(int)Math.floor(area.minY);y<=Math.floor(area.maxY);y++)
            for(int z=(int)Math.floor(area.minZ);z<=Math.floor(area.maxZ);z++) {
                int run=Integer.MIN_VALUE;int xmax=(int)Math.floor(area.maxX);
                for(int x=(int)Math.floor(area.minX);x<=xmax+1;x++) {
                    boolean full=false;
                    if(x<=xmax) {
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
        boolean complete=!getter.incomplete && selectedBoxes.size()<=limit && selectedPlants.size()<=plantLimit;
        return new Geometry(selectedBoxes.stream().limit(limit).toList(),selectedPlants.stream().limit(plantLimit).toList(),complete);
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
        private final Set<Long> missing=new HashSet<>();
        private boolean incomplete,exhausted;
        Resident(ServerLevel level,int[] budget){this.level=level;this.budget=budget;}
        public BlockState getBlockState(BlockPos pos) {
            if(level.isOutsideBuildHeight(pos))return Blocks.AIR.defaultBlockState();
            long key=pos.asLong();var known=cells.get(key);
            if(known!=null){if(missing.contains(key))incomplete=true;return known;}
            if(budget[0]<=0){incomplete=true;exhausted=true;return Blocks.AIR.defaultBlockState();}
            budget[0]--;
            var chunk=level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);
            if(chunk==null){incomplete=true;missing.add(key);known=Blocks.AIR.defaultBlockState();}
            else known=chunk.getBlockState(pos);
            cells.put(key,known);return known;
        }
        public net.minecraft.world.level.material.FluidState getFluidState(BlockPos p){return getBlockState(p).getFluidState();}
        public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos p){var c=level.getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4);return c==null?null:c.getBlockEntity(p,net.minecraft.world.level.chunk.LevelChunk.EntityCreationType.CHECK);}
        public int getHeight(){return level.getHeight();}public int getMinBuildHeight(){return level.getMinBuildHeight();}
    }
}
