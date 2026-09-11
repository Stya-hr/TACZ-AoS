package tacz.aos.terrain;

import net.minecraft.nbt.*;
import net.minecraft.nbt.visitors.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.*;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import tacz.aos.config.ServerConfig;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Metadata only. The native asynchronous scanner never loads/generates a chunk or decodes terrain. */
public final class GeneratedChunks {
    public static final GeneratedChunks INSTANCE = new GeneratedChunks();
    public enum State { FULL, ABSENT, UNKNOWN }
    private record Key(ServerLevel level, int x, int z) {}
    private record Entry(State state, long expires) {}
    private static final class Request {
        long touched;
        CollectFields fields;
        CompletableFuture<Void> future;
        Request(long tick) { touched=tick; }
    }
    private final Map<Key, Entry> cache = new LinkedHashMap<>(256,.75f,true);
    private final Map<Key, Request> requests = new LinkedHashMap<>();
    private final Deque<Long> starts = new ArrayDeque<>();
    private long tick, reads;
    public State state(ServerLevel level, int x, int z) {
        if (!level.getWorldBorder().isWithinBounds(new ChunkPos(x,z))) return State.ABSENT;
        Key key=new Key(level,x,z);
        if (level.getChunkSource().getChunkNow(x,z)!=null) { put(key,State.FULL); return State.FULL; }
        Entry entry=cache.get(key);
        if (entry!=null && entry.expires>tick) return entry.state;
        Request request=requests.get(key);
        if (request!=null) request.touched=tick;
        else if (requests.size()<ServerConfig.targeting().maxQueuedChecks()) requests.put(key,new Request(tick));
        return State.UNKNOWN;
    }
    public State path(ServerLevel level, net.minecraft.world.phys.Vec3 a, net.minecraft.world.phys.Vec3 b) {
        State result=State.FULL;
        for (long key: ChunkLine.columns(a.x,a.z,b.x,b.z)) {
            State value=state(level,ChunkPos.getX(key),ChunkPos.getZ(key));
            if (value==State.ABSENT) return State.ABSENT;
            if (value==State.UNKNOWN) result=State.UNKNOWN;
        }
        return result;
    }
    private void put(Key key,State state) {
        cache.put(key,new Entry(state,tick+(state==State.FULL?12000:40)));
        while(cache.size()>ServerConfig.targeting().maxIndexedChunks()) cache.remove(cache.keySet().iterator().next());
    }
    @SubscribeEvent public void loaded(ChunkEvent.Load event) {
        if(event.getChunk() instanceof LevelChunk chunk && chunk.getLevel() instanceof ServerLevel level && level.getServer().isSameThread())
            put(new Key(level,chunk.getPos().x,chunk.getPos().z),State.FULL);
    }
    @SubscribeEvent public void tick(ServerTickEvent.Pre event) {
        tick++;
        starts.removeIf(t->tick-t>=20);
        for(var it=requests.entrySet().iterator();it.hasNext();) {
            var e=it.next(); var r=e.getValue(); var k=e.getKey();
            // A configurable check interval can exceed 20 ticks. Keep an active target's
            // queued read alive until its next bounded refresh, without retaining abandoned work indefinitely.
            if(r.future==null) { if(tick-r.touched>Math.max(20,ServerConfig.targeting().requestIntervalTicks()+2)) it.remove(); continue; }
            if(!r.future.isDone()) continue;
            it.remove();
            // A FULL load that completed while the read was in flight wins over old disk data.
            var cached=cache.get(k);
            if(k.level.getChunkSource().getChunkNow(k.x,k.z)!=null || cached!=null && cached.state==State.FULL && cached.expires>tick) continue;
            boolean full=!r.future.isCompletedExceptionally() && !r.future.isCancelled()
                && r.fields.getResult() instanceof CompoundTag tag && isFull(tag,k.x,k.z);
            put(k,full?State.FULL:State.ABSENT);
        }
        var config=ServerConfig.targeting();
        int active=(int)requests.values().stream().filter(r->r.future!=null).count();
        for(var e:requests.entrySet()) {
            if(active>=config.concurrentChecks() || starts.size()>=config.checksPerSecond()) break;
            var r=e.getValue(); var k=e.getKey(); if(r.future!=null) continue;
            r.fields=new CollectFields(new FieldSelector(StringTag.TYPE,"Status"),new FieldSelector(IntTag.TYPE,"xPos"),new FieldSelector(IntTag.TYPE,"zPos"));
            r.future=k.level.getChunkSource().chunkMap.chunkScanner().scanChunk(new ChunkPos(k.x,k.z),r.fields);
            active++; reads++; starts.addLast(tick);
        }
    }
    public static boolean isFull(CompoundTag tag,int x,int z) {
        return tag.contains("xPos",Tag.TAG_INT) && tag.contains("zPos",Tag.TAG_INT)
            && tag.getInt("xPos")==x && tag.getInt("zPos")==z && tag.getString("Status").equals("minecraft:full");
    }
    public String status() { return "indexed="+cache.size()+", queued="+requests.size()+", scans="+reads; }
    @SubscribeEvent public void unload(LevelEvent.Unload e) {
        cache.keySet().removeIf(k->k.level==e.getLevel()); requests.keySet().removeIf(k->k.level==e.getLevel());
    }
    public void clear() { cache.clear(); requests.clear(); starts.clear(); tick=reads=0; }
    @SubscribeEvent public void stopped(ServerStoppedEvent e) { clear(); }
}
