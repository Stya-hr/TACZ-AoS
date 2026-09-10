package tacz.longrangesniping.terrain;

import com.mojang.serialization.Codec;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.*;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import tacz.longrangesniping.config.ServerConfig;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Server-thread scheduler over Minecraft's existing region I/O. Never constructs a chunk or reads entity data. */
public final class SavedTerrainRecovery {
    private static final Codec<PalettedContainer<BlockState>> STATES = PalettedContainer.codecRW(
            Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());
    private static final int MAX_TAG_BYTES = 2 * 1024 * 1024, MAX_QUEUE = 32, MAX_COLUMNS = 64;
    private record Address(ServerLevel level, int x, int z) {}
    private static final class Request {
        final Set<SectionKey> wanted = new LinkedHashSet<>();
        CollectFields fields;
        CompletableFuture<Void> future;
        boolean invalid;
    }
    private record Column(Map<Integer, CompoundTag> sections, long bytes) {}
    public record Status(int queued, int inFlight, int cachedColumns, long bytes, long reads, long restored, long rejected) {}
    private final Map<Address, Request> requests = new LinkedHashMap<>();
    private final Map<Address, Column> columns = new LinkedHashMap<>(16, .75f, true);
    private final Map<Address, Long> negatives = new LinkedHashMap<>();
    private final Set<Address> verified = new LinkedHashSet<>();
    private final Deque<Long> starts = new ArrayDeque<>();
    private final Consumer<SectionKey> ready;
    private long tick, bytes, reads, restored, rejected;

    public SavedTerrainRecovery(Consumer<SectionKey> ready) { this.ready = ready; }

    public void request(ServerLevel level, SectionKey section) {
        var address = new Address(level, section.x(), section.z());
        if (!available(level, address)) return;
        if (columns.containsKey(address)) { ready.accept(section); return; }
        var request = enqueue(address);
        if (request != null && section.y() >= level.getMinSection() && section.y() < level.getMaxSection()) request.wanted.add(section);
    }

    /** FULL saved chunks prove past generation/residency; this does not grant usable collision coverage. */
    public boolean known(ServerLevel level, int x, int z) {
        var address = new Address(level, x, z);
        if (!available(level, address)) return false;
        if (verified.contains(address)) return true;
        enqueue(address);
        return false;
    }

    private boolean available(ServerLevel level, Address address) {
        return ServerConfig.recovery().enabled() && level.getServer().isSameThread()
                && level.getWorldBorder().isWithinBounds(new ChunkPos(address.x, address.z));
    }
    private Request enqueue(Address address) {
        if (negatives.getOrDefault(address, 0L) > tick) return null;
        var existing = requests.get(address);
        if (existing != null) return existing.invalid ? null : existing;
        if (requests.size() >= MAX_QUEUE) return null;
        var request = new Request(); requests.put(address, request); return request;
    }

    public void tick() {
        tick++;
        var config = ServerConfig.recovery();
        starts.removeIf(start -> tick - start >= 20);
        negatives.values().removeIf(until -> until <= tick);
        // Futures are polled only after completion. No join(), blocking disk read or ChunkSerializer.read().
        for (var iterator = requests.entrySet().iterator(); iterator.hasNext();) {
            var entry = iterator.next(); var request = entry.getValue(); var address = entry.getKey();
            if ((request.invalid || !config.enabled()) && request.future == null) { iterator.remove(); continue; }
            if (request.future == null || !request.future.isDone()) continue;
            iterator.remove();
            if (request.invalid || !config.enabled() || address.level.getChunkSource().getChunkNow(address.x, address.z) != null) continue;
            Column column = null;
            if (!request.future.isCompletedExceptionally() && !request.future.isCancelled()
                    && request.fields.getResult() instanceof CompoundTag tag) column = decode(address, tag);
            if (column == null) {
                rejected++; negatives.put(address, tick + 200);
                while (negatives.size() > 1024) negatives.remove(negatives.keySet().iterator().next());
                continue;
            }
            verified.add(address);
            while (verified.size() > 65536) verified.remove(verified.iterator().next());
            var old = columns.put(address, column); if (old != null) bytes -= old.bytes;
            bytes += column.bytes;
            trim(config.cacheMiB() * 1048576L);
            if (columns.containsKey(address)) for (var key : request.wanted) ready.accept(key);
        }
        trim(config.cacheMiB() * 1048576L);
        if (!config.enabled()) { requests.values().forEach(r -> r.invalid = true); return; }
        int active = (int) requests.values().stream().filter(r -> r.future != null).count();
        for (var entry : requests.entrySet()) {
            if (active >= config.maxConcurrentReads() || starts.size() >= config.readsPerSecond()) break;
            var request = entry.getValue(); var address = entry.getKey();
            if (request.future != null || request.invalid) continue;
            request.fields = new CollectFields(new FieldSelector(IntTag.TYPE, "DataVersion"),
                    new FieldSelector(StringTag.TYPE, "Status"), new FieldSelector(IntTag.TYPE, "xPos"),
                    new FieldSelector(IntTag.TYPE, "zPos"), new FieldSelector(ListTag.TYPE, "sections"));
            request.future = address.level.getChunkSource().chunkMap.chunkScanner().scanChunk(new ChunkPos(address.x, address.z), request.fields);
            starts.addLast(tick); reads++; active++;
        }
    }

    private static Column decode(Address address, CompoundTag tag) {
        if (tag.getInt("DataVersion") != SharedConstants.getCurrentVersion().getDataVersion().getVersion()
                || !tag.getString("Status").equals("minecraft:full")
                || !tag.contains("xPos", Tag.TAG_INT) || !tag.contains("zPos", Tag.TAG_INT)
                || tag.getInt("xPos") != address.x || tag.getInt("zPos") != address.z
                || !tag.contains("sections", Tag.TAG_LIST) || tag.sizeInBytes() > MAX_TAG_BYTES) return null;
        var list = tag.getList("sections", Tag.TAG_COMPOUND);
        if (list.isEmpty() || list.size() > address.level.getSectionsCount() + 2) return null;
        var sections = new HashMap<Integer, CompoundTag>();
        long bytes = 128;
        for (var value : list) {
            var section = (CompoundTag) value;
            if (!section.contains("Y", Tag.TAG_BYTE)) return null;
            int y = section.getByte("Y");
            if (y < address.level.getMinSection() || y >= address.level.getMaxSection()) continue;
            if (section.contains("block_states") && !section.contains("block_states", Tag.TAG_COMPOUND)) return null;
            if (section.contains("block_states") && section.getCompound("block_states").isEmpty()) return null;
            // Empty/missing block_states is represented as air by the native FULL chunk serializer.
            var states = section.contains("block_states", Tag.TAG_COMPOUND) ? section.getCompound("block_states") : new CompoundTag();
            if (sections.put(y, states) != null) return null;
            bytes += 64L + states.sizeInBytes();
        }
        return new Column(Map.copyOf(sections), bytes);
    }

    /** Called within the existing incremental snapshot collector's CPU/cell budget. */
    public SnapshotCollector.Reader open(SectionKey key, ServerLevel level) {
        if (!ServerConfig.recovery().enabled()) return null;
        var column = columns.get(new Address(level, key.x(), key.z()));
        if (column == null) return null;
        var tag = column.sections.get(key.y());
        final PalettedContainer<BlockState> states;
        if (tag == null || tag.isEmpty()) states = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES);
        else {
            var decoded = STATES.parse(NbtOps.INSTANCE, tag).result();
            if (decoded.isEmpty()) return null; // Unknown registry IDs / malformed palettes are never replaced with air.
            states = decoded.get();
        }
        restored++;
        return new SnapshotCollector.Reader() {
            private final IdentityHashMap<BlockState, CollisionCell> palette = new IdentityHashMap<>();
            public boolean allAir() { return !states.maybeHas(state -> !state.isAir()); }
            public CollisionCell uniform() {
                var first = states.get(0,0,0);
                return states.maybeHas(state -> state != first) ? null : MinecraftSnapshotSource.classify(first);
            }
            public CollisionCell read(int index) {
                return palette.computeIfAbsent(states.get(index & 15, (index >> 8) & 15, (index >> 4) & 15), MinecraftSnapshotSource::classify);
            }
        };
    }

    /** Any live incarnation invalidates an older in-flight read before it can publish geometry. */
    public void invalidate(ServerLevel level, int x, int z) {
        var address = new Address(level,x,z); var request = requests.get(address);
        if (request != null) {
            if (request.future == null) requests.remove(address); else request.invalid = true;
        }
        var old = columns.remove(address); if (old != null) bytes -= old.bytes;
        verified.remove(address); negatives.remove(address);
    }
    private void trim(long budget) {
        var iterator = columns.values().iterator();
        while (iterator.hasNext() && (bytes > budget || columns.size() > MAX_COLUMNS)) { bytes -= iterator.next().bytes; iterator.remove(); }
    }
    public Status status() { return new Status(requests.size(), (int) requests.values().stream().filter(r -> r.future != null).count(), columns.size(), bytes, reads, restored, rejected); }
    public void unload(ServerLevel level) {
        for (var address : new ArrayList<>(columns.keySet())) if (address.level == level) invalidate(level,address.x,address.z);
        requests.entrySet().removeIf(e -> e.getKey().level == level && e.getValue().future == null);
        requests.forEach((address,request) -> { if (address.level == level) request.invalid = true; });
        verified.removeIf(address -> address.level == level); negatives.keySet().removeIf(address -> address.level == level);
    }
    public void clear() { requests.clear(); columns.clear(); verified.clear(); negatives.clear(); starts.clear(); bytes = 0; }
}
