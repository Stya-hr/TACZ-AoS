package tacz.longrangesniping.terrain;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import tacz.longrangesniping.config.ServerConfig;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** One memory/work budget for the whole server, with dimension-separated addresses. Server thread only. */
public final class TerrainSnapshots {
    public static final TerrainSnapshots INSTANCE = new TerrainSnapshots();
    private record ChunkKey(String dimension, int x, int z) {}
    private record Height(int min, int max) {}
    public record Status(int sections, int queued, int trackedChunks, long bytes, long evictions,
                         long droppedRequests, long untrackedChunks, int lastCells, int lastSections) {}

    private final SnapshotStore store = new SnapshotStore(128L * 1024 * 1024);
    private final SnapshotCollector collector = new SnapshotCollector(store, 8192);
    private final Map<String, ServerLevel> levels = new HashMap<>();
    // Coordinate-only round-robin index. No chunk/section/entity objects are kept after acquisition.
    private final Map<ChunkKey, Height> loaded = new LinkedHashMap<>();
    private final MinecraftSnapshotSource source = new MinecraftSnapshotSource(levels::get);
    private long clock, untracked;
    private SnapshotCollector.Work lastWork = new SnapshotCollector.Work(0, 0);
    private boolean enabled;
    private TerrainSnapshots() {}

    @SubscribeEvent public void onLoad(ChunkEvent.Load event) {
        if (!(event.getChunk() instanceof LevelChunk chunk) || !(chunk.getLevel() instanceof ServerLevel level)
                || !level.getServer().isSameThread()) return;
        track(level, chunk.getPos().x, chunk.getPos().z);
        String dimension = dimension(level);
        for (int y = level.getMinSection(); y < level.getMaxSection(); y++) {
            SectionKey key = new SectionKey(dimension, chunk.getPos().x, y, chunk.getPos().z);
            // A reload must not reuse a previous incarnation's possibly outdated snapshot.
            store.invalidate(key);
            collector.forgetWork(key);
            if (collecting()) collector.request(key, clock);
        }
    }

    @SubscribeEvent public void onUnload(ChunkEvent.Unload event) {
        if (!(event.getChunk() instanceof LevelChunk chunk) || !(chunk.getLevel() instanceof ServerLevel level)
                || !level.getServer().isSameThread()) return;
        String dimension = dimension(level);
        loaded.remove(new ChunkKey(dimension, chunk.getPos().x, chunk.getPos().z));
        for (int y = level.getMinSection(); y < level.getMaxSection(); y++) {
            collector.forgetWork(new SectionKey(dimension, chunk.getPos().x, y, chunk.getPos().z));
        }
        // Complete snapshots survive. An incomplete job was never part of the coverage index.
    }

    /** Called after successful LevelChunk.setBlockState, including pistons, fluids and commands. */
    public void changed(LevelChunk chunk, BlockPos position) {
        if (!(chunk.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()) return;
        SectionKey key = SectionKey.atBlock(dimension(level), position.getX(), position.getY(), position.getZ());
        store.invalidate(key);
        if (!collecting()) { collector.forgetWork(key); return; }
        track(level, chunk.getPos().x, chunk.getPos().z);
        collector.changed(key, clock + ServerConfig.current().snapshots().refreshDelayTicks());
    }

    private void track(ServerLevel level, int x, int z) {
        String dimension = dimension(level);
        levels.put(dimension, level);
        var key = new ChunkKey(dimension, x, z);
        if (loaded.containsKey(key)) return;
        loaded.put(key, new Height(level.getMinSection(), level.getMaxSection()));
        trimTracked();
    }
    private void trimTracked() {
        int limit = ServerConfig.current().snapshots().maxTrackedChunks();
        var iterator = loaded.keySet().iterator();
        while (loaded.size() > limit) { iterator.next(); iterator.remove(); untracked++; }
    }
    private boolean collecting() {
        return ServerConfig.current().available() && ServerConfig.current().snapshots().enabled();
    }

    @SubscribeEvent public void onTick(ServerTickEvent.Post event) {
        tick();
    }
    public void tick() {
        clock++;
        boolean nextEnabled = collecting();
        if (!nextEnabled) {
            if (enabled || store.size() != 0 || collector.pendingCount() != 0) collector.clear();
            enabled = false;
            lastWork = new SnapshotCollector.Work(0, 0);
            return;
        }
        enabled = true;
        var settings = ServerConfig.current();
        var config = settings.snapshots();
        store.resize(settings.snapshotMemoryMiB() * 1024L * 1024L);
        collector.resizeQueue(config.maxPendingSections());
        trimTracked();
        // Retry omitted/evicted sections and recover after enabling the cache mid-session.
        // Poll one naturally loaded chunk per tick; queue work never creates chunk tickets.
        if (!loaded.isEmpty()) {
            var entry = loaded.entrySet().iterator().next();
            var key = entry.getKey();
            var height = entry.getValue();
            loaded.remove(key);
            loaded.put(key, height);
            for (int y = height.min(); y < height.max(); y++) {
                collector.request(new SectionKey(key.dimension(), key.x(), y, key.z()), clock);
            }
        }
        lastWork = collector.tick(source, clock, config.blocksPerTick(), config.sectionsPerTick(),
                (long) (config.maxMillisPerTick() * 1_000_000));
    }

    public SnapshotStore.Coverage coverage(SectionKey key) {
        return collecting() ? store.coverage(key) : SnapshotStore.Coverage.UNKNOWN;
    }
    public Optional<SectionSnapshot> find(SectionKey key) { return collecting() ? store.find(key) : Optional.empty(); }
    public Optional<CollisionCell> cell(ServerLevel level, BlockPos pos) {
        return find(SectionKey.atBlock(dimension(level), pos.getX(), pos.getY(), pos.getZ()))
                .map(snapshot -> snapshot.cell(SectionKey.cellIndex(pos.getX(), pos.getY(), pos.getZ())));
    }
    public Status status() {
        return new Status(store.size(), collector.pendingCount(), loaded.size(), store.usedBytes(), store.evictions(),
                collector.droppedRequests(), untracked, lastWork.cells(), lastWork.sections());
    }
    public static String dimension(ServerLevel level) { return level.dimension().location().toString(); }

    @SubscribeEvent public void onLevelUnload(LevelEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()) return;
        String dimension = dimension(level);
        loaded.keySet().removeIf(key -> key.dimension().equals(dimension));
        collector.removeDimension(dimension);
        levels.remove(dimension);
    }
    @SubscribeEvent public void onStop(ServerStoppedEvent event) { clear(); }
    public void clear() {
        collector.clear();
        loaded.clear();
        levels.clear();
        enabled = false;
        clock = 0;
        untracked = 0;
        lastWork = new SnapshotCollector.Work(0, 0);
    }
}
