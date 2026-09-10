package tacz.longrangesniping.scoping;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import tacz.longrangesniping.compat.tacz.TaczBridge;
import tacz.longrangesniping.config.ServerConfig;

import java.util.*;

/** Temporary native simulation. Only ticket admission is performed here; never synchronously requests a chunk. */
public final class ScopeActivity {
    public static final ScopeActivity INSTANCE = new ScopeActivity();
    private static final TicketType<ChunkPos> TICKET = TicketType.create("taczlrs_scope_activity",
            Comparator.comparingLong(ChunkPos::toLong));
    private record Address(ServerLevel level, int x, int z) {}
    private record Key(ServerLevel level, int x, int z, int radius) {}
    private record Pending(Key key, long since) {}
    private record View(Vec3 eye, Vec3 direction) {}
    private static final class Region {
        final Key key;
        final long started;
        final Map<UUID, Long> owners = new HashMap<>();
        final Map<UUID, View> views = new HashMap<>();
        boolean ready;
        Region(Key key, long started) { this.key = key; this.started = started; }
    }
    public record Status(int regions, int ready, int fullChunks, int dependencyChunks, long admitted,
                         long unknown, long budgetDenied, long timedOut) {}
    private final Map<Key, Region> regions = new LinkedHashMap<>();
    private final Map<Address, Integer> cores = new HashMap<>();
    private final Set<Address> known = new LinkedHashSet<>();
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Deque<Long> starts = new ArrayDeque<>();
    private ServerConfig.Activity previous;
    private long clock, admitted, unknown, budgetDenied, timedOut;
    private int queries;
    private ScopeActivity() {}

    @SubscribeEvent public void loaded(ChunkEvent.Load event) {
        if (!(event.getChunk() instanceof LevelChunk chunk) || !(chunk.getLevel() instanceof ServerLevel level)
                || !level.getServer().isSameThread()) return;
        var pos = chunk.getPos();
        // Scope loading must not grow its own permission footprint into unexplored territory.
        var address = new Address(level, pos.x, pos.z);
        if (known.contains(address) || regions.keySet().stream().noneMatch(k -> contains(k, address, k.radius + 3))) {
            known.remove(address); known.add(address);
            while (known.size() > 65536) known.remove(known.iterator().next());
        }
    }

    @SubscribeEvent public void tick(ServerTickEvent.Pre event) {
        clock++;
        queries = 0;
        var settings = ServerConfig.current();
        var config = settings.activity();
        if (!Objects.equals(previous, config) || !settings.available() || !config.enabled() || !settings.scopeRendering().enabled()) {
            releaseAll(); previous = config;
        }
        starts.removeIf(t -> clock - t >= 20);
        pending.entrySet().removeIf(e -> {
            var player = event.getServer().getPlayerList().getPlayer(e.getKey());
            return player == null || !TaczBridge.evaluate(player).eligible() || player.serverLevel() != e.getValue().key.level;
        });
        for (var iterator = regions.values().iterator(); iterator.hasNext();) {
            var region = iterator.next();
            region.owners.entrySet().removeIf(e -> {
                var player = event.getServer().getPlayerList().getPlayer(e.getKey());
                var view = region.views.get(e.getKey());
                // Reload invalidates old collision snapshots before the new live chunks become queryable.
                // A still-held, unchanged reticle may finish its already-authorized load, bounded by the loading deadline.
                if (!region.ready && player != null && enabled(player) && player.serverLevel() == region.key.level
                        && view != null && player.getEyePosition().distanceToSqr(view.eye) < 1
                        && player.getViewVector(1).dot(view.direction) > 0.99996) {
                    e.setValue(clock + config.retentionTicks());
                }
                return e.getValue() < clock || player == null || player.serverLevel() != region.key.level;
            });
            region.views.keySet().retainAll(region.owners.keySet());
            region.ready = ready(region.key);
            boolean timeout = !region.ready && clock - region.started >= config.loadTimeoutTicks();
            if (region.owners.isEmpty() || timeout) {
                if (timeout) timedOut++;
                removeTickets(region.key); iterator.remove();
            }
        }
    }

    /** Called on the server's bounded subscription refresh, using server-validated visible candidates. */
    public void update(ServerPlayer player, List<? extends Entity> visible) {
        if (!enabled(player)) { pending.remove(player.getUUID()); return; }
        if (queries >= ServerConfig.current().activity().maxQueriesPerTick()) return;
        queries++;
        Vec3 start = player.getEyePosition(), direction = player.getViewVector(1);
        var settings = ServerConfig.current();
        var visibility = new ScopeVisibility(player.serverLevel(), settings.scopeRendering().maxTraceCells());
        Vec3 target = visibility.firstBlock(start, start.add(direction.scale(settings.maxDistance()))).orElse(null);
        double nearest = target == null ? settings.maxDistance() : target.distanceTo(start);
        // A visible entity intersecting the actual reticle can locate an air target before the terrain hit.
        for (var entity : visible) {
            var hit = entity.getBoundingBox().inflate(0.25).clip(start, start.add(direction.scale(nearest)));
            if (hit.isPresent() && hit.get().distanceTo(start) < nearest) {
                target = entity.position(); nearest = hit.get().distanceTo(start);
            }
        }
        consider(player, target);
    }

    /** Admission entry point; caller has already established a known, unobstructed target. No client coordinates accepted. */
    public void consider(ServerPlayer player, Vec3 target) {
        var id = player.getUUID();
        if (!enabled(player) || target == null || !Double.isFinite(target.x + target.y + target.z)
                || target.distanceToSqr(player.getEyePosition()) > Math.pow(ServerConfig.current().maxDistance(), 2)) {
            pending.remove(id); return;
        }
        var config = ServerConfig.current().activity();
        var pos = new ChunkPos(BlockPos.containing(target));
        Key key = new Key(player.serverLevel(), pos.x, pos.z, config.radius());
        // Keep an existing region while the reticle remains inside its core, avoiding border churn.
        for (var region : regions.values()) if (region.owners.containsKey(id)
                && contains(region.key, new Address(key.level, key.x, key.z), region.key.radius)) {
            region.owners.put(id, clock + config.retentionTicks()); pending.remove(id); return;
        }
        var wait = pending.get(id);
        if (wait == null || !wait.key.equals(key)) { pending.put(id, new Pending(key, clock)); return; }
        if (clock - wait.since < config.stableTicks()) return;
        long owned = regions.values().stream().filter(r -> r.owners.containsKey(id)).count();
        if (owned >= 2) { budgetDenied++; return; }
        var shared = regions.get(key);
        if (shared != null) {
            shared.owners.put(id, clock + config.retentionTicks());
            shared.views.put(id, new View(player.getEyePosition(), player.getViewVector(1)));
            pending.remove(id); return;
        }
        if (regions.size() >= config.maxRegions() || starts.size() >= config.startsPerSecond()
                || footprint(key, 2).size() > config.maxFullChunks()
                || footprint(key, 2 + ChunkLevel.RADIUS_AROUND_FULL_CHUNK).size() > config.maxDependencyChunks()) {
            budgetDenied++; return;
        }
        // Already completed chunks + one light-loading neighbor ring. This check never reads the disk or loads a chunk.
        for (var address : square(key, key.radius + 3)) {
            if (!key.level.getWorldBorder().isWithinBounds(new ChunkPos(address.x, address.z))
                    || !known.contains(address) && key.level.getChunkSource().getChunkNow(address.x, address.z) == null) {
                unknown++; return;
            }
        }
        var region = new Region(key, clock);
        region.owners.put(id, clock + config.retentionTicks());
        region.views.put(id, new View(player.getEyePosition(), player.getViewVector(1)));
        regions.put(key, region);
        for (var address : square(key, key.radius)) {
            if (cores.merge(address, 1, Integer::sum) == 1) {
                var chunk = new ChunkPos(address.x, address.z);
                // Level 31 per core chunk, including NeoForge random ticking; no Future.join/getChunk call.
                key.level.getChunkSource().addRegionTicket(TICKET, chunk, 2, chunk, true);
            }
        }
        starts.addLast(clock); admitted++; pending.remove(id);
        if (ServerConfig.current().diagnostics()) tacz.longrangesniping.TaczLongRangeSniping.LOGGER.info(
                "Scope activity admitted {} at tick {}", key, clock);
    }

    private boolean enabled(ServerPlayer player) {
        var settings = ServerConfig.current();
        return settings.available() && settings.activity().enabled() && settings.scopeRendering().enabled()
                && player.isAlive() && !player.isSpectator() && TaczBridge.evaluate(player).eligible();
    }
    public boolean active(Entity entity) {
        var settings = ServerConfig.current();
        return settings.available() && settings.activity().enabled() && settings.scopeRendering().enabled()
                && entity.level() instanceof ServerLevel level
                && cores.containsKey(new Address(level, entity.chunkPosition().x, entity.chunkPosition().z));
    }
    private static boolean ready(Key key) {
        for (var a : square(key, key.radius)) {
            if (!key.level.isPositionEntityTicking(new BlockPos(a.x * 16 + 8, 0, a.z * 16 + 8))) return false;
        }
        return true;
    }
    private Set<Address> footprint(Key addition, int padding) {
        Set<Address> result = new HashSet<>();
        for (var key : regions.keySet()) result.addAll(square(key, key.radius + padding));
        if (addition != null) result.addAll(square(addition, addition.radius + padding));
        return result;
    }
    private static List<Address> square(Key key, int radius) {
        var result = new ArrayList<Address>();
        for (int x = key.x - radius; x <= key.x + radius; x++) for (int z = key.z - radius; z <= key.z + radius; z++)
            result.add(new Address(key.level, x, z));
        return result;
    }
    private static boolean contains(Key key, Address address, int radius) {
        return key.level == address.level && Math.abs(key.x - address.x) <= radius && Math.abs(key.z - address.z) <= radius;
    }
    private void removeTickets(Key key) {
        if (ServerConfig.current().diagnostics()) tacz.longrangesniping.TaczLongRangeSniping.LOGGER.info(
                "Scope activity released {} at tick {}", key, clock);
        for (var address : square(key, key.radius)) {
            int remaining = cores.get(address) - 1;
            if (remaining > 0) cores.put(address, remaining);
            else {
                cores.remove(address);
                var pos = new ChunkPos(address.x, address.z);
                key.level.getChunkSource().removeRegionTicket(TICKET, pos, 2, pos, true);
            }
        }
    }
    public Status status() {
        return new Status(regions.size(), (int) regions.values().stream().filter(r -> r.ready).count(),
                footprint(null, 2).size(), footprint(null, 2 + ChunkLevel.RADIUS_AROUND_FULL_CHUNK).size(),
                admitted, unknown, budgetDenied, timedOut);
    }
    public void releaseAll() {
        for (var key : regions.keySet()) removeTickets(key);
        regions.clear(); pending.clear(); starts.clear();
    }
    @SubscribeEvent public void unload(LevelEvent.Unload event) {
        regions.entrySet().removeIf(e -> {
            if (e.getKey().level != event.getLevel()) return false;
            removeTickets(e.getKey()); return true;
        });
        known.removeIf(a -> a.level == event.getLevel());
        pending.values().removeIf(p -> p.key.level == event.getLevel());
    }
    @SubscribeEvent public void stopped(ServerStoppedEvent event) {
        releaseAll(); known.clear(); previous = null; clock = admitted = unknown = budgetDenied = timedOut = 0;
    }
}
