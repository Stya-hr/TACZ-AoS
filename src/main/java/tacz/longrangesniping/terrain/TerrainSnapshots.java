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
    private final SavedTerrainRecovery recovery = new SavedTerrainRecovery(key -> collector.prioritize(key, this.clock));
    public SavedTerrainRecovery.Status recoveryStatus() { return recovery.status(); }
    private long clock, untracked;
    private SnapshotCollector.Work lastWork = new SnapshotCollector.Work(0, 0);
    private boolean enabled;
    private SnapshotDiskCache disk;
    private boolean diskActive;
    private static final int[] HALO = {-1, 1};
    private net.minecraft.server.MinecraftServer server;
    private ServerConfig.Persistence diskConfig;
    private TerrainSnapshots() {
        store.listeners(snapshot -> { if (diskActive && disk != null) disk.save(snapshot); }, key -> { if (diskActive && disk != null) disk.invalidate(key); });
    }
    @SubscribeEvent public void starting(net.neoforged.neoforge.event.server.ServerAboutToStartEvent event) {
        server = event.getServer(); diskConfig = ServerConfig.current().persistence();
        store.pinLimits(diskConfig.pinMiB() * 1048576L, diskConfig.maxPinnedSections());
        diskActive = diskConfig.enabled() && collecting();
        String versions = net.neoforged.fml.ModList.get().getMods().stream()
                .map(mod -> mod.getModId() + "=" + mod.getVersion()).sorted().collect(java.util.stream.Collectors.joining(";"));
        String fingerprint;
        try {
            fingerprint = "geometry=" + SnapshotCodec.FORMAT + ":" + MinecraftSnapshotSource.GEOMETRY_REVISION + ":" + java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(versions.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        // Running this mod with caching disabled must invalidate a prior session's files too.
        if (!diskActive) fingerprint = "disabled";
        disk = new SnapshotDiskCache(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("taczlrs-collision-cache"), fingerprint,
                new SnapshotDiskCache.Limits(diskConfig.diskMiB() * 1048576L, diskConfig.maxDiskSections(),
                        diskConfig.maxQueuedSections(), diskConfig.queueMiB() * 1048576L,
                        diskConfig.ioMiBPerSecond() * 1048576L, diskConfig.operationsPerSecond()),
                message -> tacz.longrangesniping.TaczLongRangeSniping.LOGGER.warn("Snapshot disk cache disabled: {}", message),
                server.getWorldPath(net.minecraft.world.level.storage.LevelResource.LEVEL_DATA_FILE));
    }
    public SnapshotDiskCache.Status diskStatus() { return disk == null || !diskActive ? null : disk.status(); }
    public int pinnedSections() { return store.pinnedSections(); }
    public long pinnedBytes() { return store.pinnedBytes(); }
    public SnapshotLease lease() { return new SnapshotLease(store); }

    /** Bounded look-ahead over existing memory/disk addresses; never fetches world chunks or waits for I/O. */
    public void prepare(ServerLevel level, net.minecraft.world.phys.Vec3 start, net.minecraft.world.phys.Vec3 end, SnapshotLease lease) {
        if (!collecting()) return;
        double distance = start.distanceTo(end);
        if (!Double.isFinite(distance) || distance > 16384) return;
        String dimension = dimension(level);
        int steps = Math.max(1, (int) Math.ceil(distance / 8));
        var seen = new java.util.HashSet<SectionKey>();
        int checks = 0;
        for (int i = 0; i <= steps && checks < 1024; i++) {
            var point = start.lerp(end, (double) i / steps);
            int x = net.minecraft.util.Mth.floor(point.x), y = net.minecraft.util.Mth.floor(point.y), z = net.minecraft.util.Mth.floor(point.z);
            var center = SectionKey.atBlock(dimension, x, y, z);
            // Stop warming at a truly unknown section, even if disk records exist farther along the ray.
            if (level.getChunkSource().getChunkNow(center.x(), center.z()) == null && !store.contains(center)
                    && (!diskActive || disk == null || !disk.known(center))) break;
            for (int ox : HALO) for (int oy : HALO) for (int oz : HALO) {
                if (++checks > 1024) break;
                var key = SectionKey.atBlock(dimension, x + ox, y + oy, z + oz);
                if (!seen.add(key)) continue;
                if (lease != null) lease.retain(key);
                if (!store.contains(key) && diskActive && disk != null) disk.request(key);
            }
        }
    }

    @SubscribeEvent public void onLoad(ChunkEvent.Load event) {
        if (!(event.getChunk() instanceof LevelChunk chunk) || !(chunk.getLevel() instanceof ServerLevel level)
                || !level.getServer().isSameThread()) return;
        track(level, chunk.getPos().x, chunk.getPos().z);
        recovery.invalidate(level, chunk.getPos().x, chunk.getPos().z);
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
        recovery.invalidate(level, chunk.getPos().x, chunk.getPos().z);
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
        // Passive terrain history must survive exploration before a player deploys a rangefinder.
        // This only reads naturally resident chunks under the existing budget: it cannot load,
        // simulate, subscribe to entities or grant scoped gameplay to an unauthorized player.
        tick();
    }
    public void tick() {
        clock++;
        if (disk != null) {
            // Restore only on this thread and only if the real chunk is still absent; live terrain always wins.
            for (var snapshot : disk.drain(diskConfig.restoresPerTick())) {
                var level = server == null ? null : server.getAllLevels().iterator();
                if (!collecting()) continue;
                if (level != null) while (level.hasNext()) {
                    var candidate = level.next();
                    if (dimension(candidate).equals(snapshot.key().dimension())
                            && snapshot.key().y() >= candidate.getMinSection() && snapshot.key().y() < candidate.getMaxSection()
                            && candidate.getChunkSource().getChunkNow(snapshot.key().x(), snapshot.key().z()) == null) {
                        store.restore(snapshot); break;
                    }
                }
            }
        }
        var pinConfig = ServerConfig.current().persistence();
        store.pinLimits(pinConfig.pinMiB() * 1048576L, pinConfig.maxPinnedSections());
        boolean nextEnabled = collecting();
        if (!nextEnabled) {
            if (enabled || store.size() != 0 || collector.pendingCount() != 0) collector.clear();
            enabled = false;
            lastWork = new SnapshotCollector.Work(0, 0);
            return;
        }
        enabled = true;
        recovery.tick();
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
        lastWork = collector.tick(key -> {
            var live = source.open(key);
            var level = levels.get(key.dimension());
            return live != null || level == null ? live : recovery.open(key, level);
        }, clock, config.blocksPerTick(), config.sectionsPerTick(),
                (long) (config.maxMillisPerTick() * 1_000_000));
    }

    public SnapshotStore.Coverage coverage(SectionKey key) {
        return collecting() ? store.coverage(key) : SnapshotStore.Coverage.UNKNOWN;
    }
    /** Residency evidence only. Missing evidence may enqueue a bounded saved-terrain scan, never a chunk load. */
    public boolean knownChunk(ServerLevel level, int x, int z) {
        if (!collecting()) return false;
        String dimension = dimension(level);
        for (int y = level.getMinSection(); y < level.getMaxSection(); y++) {
            var key = new SectionKey(dimension, x, y, z);
            if (store.contains(key) || diskActive && disk != null && disk.known(key)) return true;
        }
        return recovery.known(level, x, z);
    }
    public Optional<SectionSnapshot> find(SectionKey key) {
        if (!collecting()) return Optional.empty();
        var snapshot = store.find(key);
        if (snapshot.isEmpty() && diskActive && disk != null) disk.request(key);
        else if (diskActive && disk != null) snapshot.ifPresent(disk::retrySave);
        if (snapshot.isEmpty() && (!diskActive || disk == null || !disk.known(key))) {
            var level = levels.get(key.dimension());
            if (level != null) recovery.request(level, key);
        }
        return snapshot;
    }
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
        recovery.unload(level);
        String dimension = dimension(level);
        loaded.keySet().removeIf(key -> key.dimension().equals(dimension));
        collector.removeDimension(dimension);
        levels.remove(dimension);
    }
    @SubscribeEvent public void onStop(ServerStoppedEvent event) {
        if (disk != null) { disk.close(); disk = null; }
        server = null; clear();
    }
    public void clear() {
        recovery.clear();
        collector.clear();
        loaded.clear();
        levels.clear();
        enabled = false;
        clock = 0;
        untracked = 0;
        lastWork = new SnapshotCollector.Work(0, 0);
    }
}
