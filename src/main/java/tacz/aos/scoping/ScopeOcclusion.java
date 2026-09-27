package tacz.aos.scoping;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.*;
import tacz.aos.config.ServerConfig;
import tacz.aos.network.*;
import tacz.aos.occlusion.*;
/** Bounded visual section jobs. Scans resume, share data across targets, and never load terrain. */
public final class ScopeOcclusion {
    private static final Map<UUID,Session> sessions=new HashMap<>();
    private static int remaining;private static long tick;
    private static class Session {ServerLevel level;Map<Long,Job> jobs=new LinkedHashMap<>();int cursor;Map<UUID,Motion> motion=new HashMap<>();ScopeOcclusionPayload last;long sent;}
    private record Motion(Vec3 position,long time) {}
    public static final class Job {
        public final long key;private int cursor;private long[] bits=new long[64];private Map<Integer,Integer> plants=new HashMap<>();
        private Object source;public SectionMask mask;public long revision,published,next,lastWanted;
        public Job(long key){this.key=key;}
        public int progress(){return cursor;}
        public int scan(ServerLevel level,int allowance,long time,int refresh) {
            if(time<next||allowance<=0)return 0;
            var chunk=level.getChunkSource().getChunkNow(SectionPos.x(key),SectionPos.z(key));
            if(chunk==null){cursor=0;source=null;Arrays.fill(bits,0);plants.clear();return 0;}
            int sy=SectionPos.y(key);if(sy<level.getMinSection()||sy>=level.getMaxSection())return 0;
            var section=chunk.getSection(sy-level.getMinSection());
            if(source!=section){cursor=0;Arrays.fill(bits,0);plants.clear();source=section;}
            int used=0;
            if(section.hasOnlyAir()){Arrays.fill(bits,0);plants.clear();cursor=4096;used=1;}
            else while(cursor<4096&&used<allowance) {
                int i=cursor++,x=i&15,z=(i>>4)&15,y=i>>8;used++;
                var state=section.getBlockState(x,y,z);
                if(OcclusionShapes.textured(state))plants.put(i,Block.getId(state));
                else if(OcclusionShapes.solid(state))bits[i>>>6]|=1L<<(i&63);
            }
            if(cursor==4096) {
                var result=new SectionMask(Arrays.stream(bits).boxed().toList(),plants);
                if(!result.equals(mask)){mask=result;revision++;}
                cursor=0;bits=new long[64];plants=new HashMap<>();next=time+refresh;
            }
            return used;
        }
    }
    public static void beginTick(long time,Set<UUID> online){tick=time;remaining=ServerConfig.occlusion().cellsPerTick();sessions.keySet().retainAll(online);}
    public static void clear(){sessions.clear();remaining=0;}
    public static List<Long> required(Vec3 eye,AABB entity,int radius,ServerLevel level) {
        return OcclusionCoverage.required(eye,entity,radius,level.getMinSection(),level.getMaxSection());
    }

    /** Required data wins admission. Optional prefetch and a bounded LRU grace never gate rendering. */
    public static void retainJobs(Map<Long,Job> jobs,Set<Long> required,Set<Long> prefetch,long time,int limit,int grace) {
        var keep=new LinkedHashSet<Long>(required);
        for(long key:prefetch){if(keep.size()>=limit)break;keep.add(key);}
        var active=new HashSet<>(keep);
        jobs.values().stream().filter(j->!keep.contains(j.key)&&time-j.lastWanted<=grace)
                .sorted(Comparator.comparingLong((Job j)->j.lastWanted).reversed().thenComparingLong(j->j.key))
                .limit(Math.max(0,limit-keep.size())).forEach(j->keep.add(j.key));
        jobs.keySet().retainAll(keep);
        for(long key:keep){var job=jobs.computeIfAbsent(key,Job::new);if(active.contains(key))job.lastWanted=time;}
    }
    public static void update(ServerPlayer player,List<? extends Entity> targets) {
        var cfg=ServerConfig.occlusion();var s=sessions.computeIfAbsent(player.getUUID(),id->new Session());
        if(s.level!=player.serverLevel()){s.jobs.clear();s.motion.clear();s.last=null;s.level=player.serverLevel();}
        var desired=new LinkedHashSet<Long>();var prefetch=new LinkedHashSet<Long>();var roster=new ArrayList<ScopeOcclusionPayload.Target>();
        var ordered=new ArrayList<Entity>(targets);ordered.sort(Comparator.comparingInt(Entity::getId));
        if(cfg.enabled())for(var entity:ordered) {
            var needs=required(player.getEyePosition(),entity.getBoundingBox(),cfg.radius(),s.level);
            long extra=needs.stream().filter(k->!desired.contains(k)).count();boolean admitted=desired.size()+extra<=cfg.maxSections();
            if(admitted) {
                desired.addAll(needs);
                var previous=s.motion.put(entity.getUUID(),new Motion(entity.position(),tick));
                double speed=entity.getDeltaMovement().length();
                if(previous!=null&&tick>previous.time)speed=Math.max(speed,entity.position().distanceTo(previous.position)/(tick-previous.time));
                // Isotropic cover handles abrupt turns; velocity only sizes the margin, never chooses a direction.
                double margin=Math.min(cfg.maxPrefetchMargin(),Math.max(cfg.prefetchMargin(),speed*cfg.prefetchTicks()));
                for(long key:OcclusionCoverage.prefetch(player.getEyePosition(),entity.getBoundingBox(),cfg.radius(),s.level.getMinSection(),s.level.getMaxSection(),margin))
                    if(s.level.getChunkSource().getChunkNow(SectionPos.x(key),SectionPos.z(key))!=null)prefetch.add(key);
            }
            roster.add(new ScopeOcclusionPayload.Target(entity.getId(),entity.getUUID(),admitted,admitted?needs:List.of()));
        }
        s.motion.keySet().retainAll(ordered.stream().map(Entity::getUUID).collect(java.util.stream.Collectors.toSet()));
        if(targets.isEmpty()||!cfg.enabled())s.jobs.clear();
        else {
            // Under pressure prepare the nearest section ring on every side first, not X-order slices.
            // Prefer acknowledged data among equally close candidates to avoid optional-cache churn.
            var distance=new HashMap<Long,Integer>();
            for(long key:prefetch) {
                int best=Integer.MAX_VALUE;
                for(long needed:desired) {
                    int dx=SectionPos.x(key)-SectionPos.x(needed),dy=SectionPos.y(key)-SectionPos.y(needed),dz=SectionPos.z(key)-SectionPos.z(needed);
                    best=Math.min(best,dx*dx+dy*dy+dz*dz);
                }
                distance.put(key,best);
            }
            var ranked=prefetch.stream().sorted(Comparator.comparingInt((Long key)->distance.get(key))
                    .thenComparingInt(key->s.jobs.containsKey(key)&&s.jobs.get(key).published>0?0:1).thenComparingLong(Long::longValue)).toList();
            retainJobs(s.jobs,desired,new LinkedHashSet<>(ranked),tick,cfg.maxSections(),cfg.retentionTicks());
        }
        var jobs=new ArrayList<>(s.jobs.values());if(!jobs.isEmpty())Collections.rotate(jobs,-Math.floorMod(s.cursor++,jobs.size()));
        // Stable sort preserves rotating fairness within each priority class.
        jobs.sort(Comparator.comparingInt(j->desired.contains(j.key)?(j.published==0?0:1):(j.published==0?2:3)));
        int budget=Math.min(remaining,cfg.cellsPerPlayer()),initial=budget;
        for(var job:jobs){int used=job.scan(s.level,budget,tick,cfg.refreshTicks());budget-=used;if(budget<=0)break;}
        remaining-=initial-budget;
        var updates=new ArrayList<ScopeOcclusionPayload.Update>();
        for(var job:jobs)if(job.mask!=null&&job.revision!=job.published&&updates.size()<cfg.sectionsPerPacket()) {
            job.published=job.revision;updates.add(new ScopeOcclusionPayload.Update(job.key,job.revision,job.mask));
        }
        var regions=s.jobs.values().stream().map(j->new ScopeOcclusionPayload.Region(j.key,j.published)).toList();
        var packet=new ScopeOcclusionPayload(player.level().dimension().location(),regions,roster,updates);
        var manifest=new ScopeOcclusionPayload(packet.dimension(),regions,roster,List.of());
        if(!updates.isEmpty()||!manifest.equals(s.last)||tick-s.sent>=20){PacketDelivery.sendToPlayer(player,packet);s.last=manifest;s.sent=tick;}
        if(targets.isEmpty()||!cfg.enabled())sessions.remove(player.getUUID());
    }
}
