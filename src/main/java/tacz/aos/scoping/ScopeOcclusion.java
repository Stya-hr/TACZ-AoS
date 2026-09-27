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
    private static class Session {ServerLevel level;Map<Long,Job> jobs=new LinkedHashMap<>();int cursor;ScopeOcclusionPayload last;long sent;}
    public static final class Job {
        public final long key;private int cursor;private long[] bits=new long[64];private Map<Integer,Integer> plants=new HashMap<>();
        private Object source;public SectionMask mask;public long revision,published,next;
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
        var bounds=entity.inflate(2,1,2);var center=bounds.getCenter();var end=center.add(eye.subtract(center).normalize().scale(Math.min(radius,eye.distanceTo(center))));
        var area=bounds.minmax(bounds.move(end.subtract(center)));
        // Observation owns a bounded target neighbourhood, not the entire eye-to-target path.
        int cx=((int)Math.floor(center.x))>>4,cz=((int)Math.floor(center.z))>>4;
        var result=new ArrayList<Long>();double pad=Math.max(bounds.getXsize(),Math.max(bounds.getYsize(),bounds.getZsize()))/2+.5;
        for(int x=((int)Math.floor(area.minX))>>4;x<=((int)Math.floor(area.maxX))>>4;x++)
            for(int y=Math.max(level.getMinSection(),((int)Math.floor(area.minY))>>4);y<=Math.min(level.getMaxSection()-1,((int)Math.floor(area.maxY))>>4);y++)
                for(int z=((int)Math.floor(area.minZ))>>4;z<=((int)Math.floor(area.maxZ))>>4;z++) {
                    if(Math.abs(x-cx)>2||Math.abs(z-cz)>2)continue;
                    var box=new AABB(x*16,y*16,z*16,x*16+16,y*16+16,z*16+16).inflate(pad);
                    if(box.contains(center)||box.clip(center,end).isPresent())result.add(SectionPos.asLong(x,y,z));
                }
        return result;
    }
    public static void update(ServerPlayer player,List<? extends Entity> targets) {
        var cfg=ServerConfig.occlusion();var s=sessions.computeIfAbsent(player.getUUID(),id->new Session());
        if(s.level!=player.serverLevel()){s.jobs.clear();s.last=null;s.level=player.serverLevel();}
        var desired=new LinkedHashSet<Long>();var roster=new ArrayList<ScopeOcclusionPayload.Target>();
        var ordered=new ArrayList<Entity>(targets);ordered.sort(Comparator.comparingInt(Entity::getId));
        if(cfg.enabled())for(var entity:ordered) {
            var needs=required(player.getEyePosition(),entity.getBoundingBox(),cfg.radius(),s.level);
            long extra=needs.stream().filter(k->!desired.contains(k)).count();boolean admitted=desired.size()+extra<=cfg.maxSections();
            if(admitted)desired.addAll(needs);
            roster.add(new ScopeOcclusionPayload.Target(entity.getId(),entity.getUUID(),admitted,admitted?needs:List.of()));
        }
        s.jobs.keySet().retainAll(desired);for(long key:desired)s.jobs.computeIfAbsent(key,Job::new);
        var jobs=new ArrayList<>(s.jobs.values());if(!jobs.isEmpty())Collections.rotate(jobs,-Math.floorMod(s.cursor++,jobs.size()));
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
