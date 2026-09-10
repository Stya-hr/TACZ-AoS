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
import net.minecraft.world.phys.AABB;
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
    private record FlightHold(UUID owner, long deadline) {}
    private static final class Region {
        final Key key;
        final long started;
        final Map<UUID, Long> owners = new HashMap<>();
        final Map<UUID, View> views = new HashMap<>();
        final Map<UUID, FlightHold> flights = new HashMap<>();
        boolean ready;
        Region(Key key, long started) { this.key = key; this.started = started; }
    }
    public record Status(int regions, int ready, int fullChunks, int dependencyChunks, int flightPins, long admitted,
                         long unknown, long budgetDenied, long timedOut) {}
    private final Map<Key, Region> regions = new LinkedHashMap<>();
    private final Map<Address, Integer> cores = new HashMap<>();
    private final Set<Address> known = new LinkedHashSet<>();
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Deque<Long> starts = new ArrayDeque<>();
    private ServerConfig.Activity previous;
    private long clock, admitted, unknown, budgetDenied, timedOut;
    private int queries;
    private long predictionNanos;
    private record PredictionState(ServerLevel level, long nextTick, String reason) {}
    private final Map<UUID, PredictionState> predictions = new HashMap<>();
    public String predictionStatus(UUID player) {
        var value = predictions.get(player);
        return value == null ? "none" : value.reason;
    }
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
        if (event.getServer().tickRateManager().runsNormally()) clock++;
        queries = 0;
        predictionNanos = 0;
        predictions.entrySet().removeIf(e -> {
            var player = event.getServer().getPlayerList().getPlayer(e.getKey());
            return player == null || player.serverLevel() != e.getValue().level || !enabled(player);
        });
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
                return e.getValue() < clock || player == null || player.serverLevel() != region.key.level
                        || !tacz.longrangesniping.block.RangefinderAccess.nearby(player);
            });
            region.views.keySet().retainAll(region.owners.keySet());
            region.flights.values().removeIf(hold -> {
                var player = event.getServer().getPlayerList().getPlayer(hold.owner);
                return hold.deadline < clock || player == null || player.serverLevel() != region.key.level
                        || !tacz.longrangesniping.block.RangefinderAccess.nearby(player);
            });
            region.ready = ready(region.key);
            boolean timeout = !region.ready && clock - region.started >= config.loadTimeoutTicks();
            if ((region.owners.isEmpty() && region.flights.isEmpty()) || timeout) {
                if (timeout) timedOut++;
                removeTickets(region.key); iterator.remove();
            }
        }
    }

    /** Called on the server's bounded subscription refresh, using server-validated visible candidates. */
    public void update(ServerPlayer player, List<? extends Entity> visible) {
        var id = player.getUUID();
        if (!enabled(player)) { pending.remove(id); predictions.remove(id); return; }
        var settings = ServerConfig.current();
        var last = predictions.get(id);
        if (last != null && last.level == player.serverLevel() && clock < last.nextTick) return;
        long budget = (long) (settings.prediction().maxMillisPerTick() * 1_000_000);
        if (queries >= settings.activity().maxQueriesPerTick() || predictionNanos >= budget) return;
        queries++;
        long started = System.nanoTime();
        var shot = tacz.longrangesniping.compat.tacz.BallisticPreview.read(player).orElse(null);
        Vec3 target = null;
        String reason = "UNSUPPORTED_PARAMETERS";
        if (shot != null) {
            var limits = settings.ballistics();
            int life = Math.max(1, shot.lifeTicks() - 1);
            int ticks = limits.enabled() && limits.extendLifetime() ? limits.maxFlightTicks()
                    : Math.min(life, limits.maxFlightTicks());
            double distance = limits.enabled() ? Math.min(settings.maxDistance(), limits.maxDistance()) : settings.maxDistance();
            var boxes = visible.stream().filter(e -> !e.isRemoved() && e != player && e.isPickable())
                    .limit(settings.maxTrackedEntities()).map(Entity::getBoundingBox).toList();
            var result = ImpactPrediction.predict(shot, new tacz.longrangesniping.ballistics.NoLoadCollisionSource(player.serverLevel()),
                    boxes, distance, ticks, settings.scopeRendering().maxTraceCells(),
                    settings.prediction().maxEntityChecks(), started + budget - predictionNanos);
            target = result.target(); reason = result.end().name();
        }
        predictionNanos += System.nanoTime() - started;
        if (settings.diagnostics() && (last == null || !last.reason.equals(reason))) {
            tacz.longrangesniping.TaczLongRangeSniping.LOGGER.info("Scope prediction for {}: {}, target={}",
                    player.getGameProfile().getName(), reason, target);
        }
        predictions.put(id, new PredictionState(player.serverLevel(), clock + settings.prediction().intervalTicks(), reason));
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
        for (var region : regions.values()) if (ownedBy(region, id)
                && contains(region.key, new Address(key.level, key.x, key.z), region.key.radius)) {
            region.owners.put(id, clock + config.retentionTicks());
            region.views.put(id, new View(player.getEyePosition(), player.getViewVector(1)));
            pending.remove(id); return;
        }
        var wait = pending.get(id);
        if (wait == null || !wait.key.equals(key)) { pending.put(id, new Pending(key, clock)); return; }
        if (clock - wait.since < config.stableTicks()) return;
        long owned = regions.values().stream().filter(r -> ownedBy(r, id)).count();
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
    private static boolean ownedBy(Region region, UUID player) {
        return region.owners.containsKey(player) || region.flights.values().stream().anyMatch(hold -> hold.owner.equals(player));
    }

    /** Holds only already budgeted regions on the shot's horizontal path. Never adds a ticket or searches the corridor. */
    public FlightLease retainForShot(Entity bullet, Entity owner, int maxFlightTicks, int maxDistance) {
        var held = new ArrayList<Region>();
        Vec3 start = bullet.position(), velocity = bullet.getDeltaMovement();
        if (owner == null || !Double.isFinite(velocity.lengthSqr()) || velocity.lengthSqr() == 0) return new FlightLease(bullet.getUUID(), held);
        // Gravity changes height; retain the target column, not a straight 3D ray that could miss a compensated shot.
        Vec3 end = start.add(velocity.normalize().scale(maxDistance));
        for (var region : regions.values()) {
            var key = region.key;
            if (key.level != bullet.level() || !region.owners.containsKey(owner.getUUID())) continue;
            var bounds = new AABB((key.x-key.radius)*16, -30_000_000, (key.z-key.radius)*16,
                    (key.x+key.radius+1)*16, 30_000_000, (key.z+key.radius+1)*16).inflate(1);
            if (bounds.contains(start) || bounds.clip(start, end).isPresent()) {
                region.flights.put(bullet.getUUID(), new FlightHold(owner.getUUID(), clock + maxFlightTicks + 2L));
                held.add(region);
            }
        }
        return new FlightLease(bullet.getUUID(), held);
    }

    public static final class FlightLease implements AutoCloseable {
        private final UUID bullet;
        private final List<Region> held;
        private FlightLease(UUID bullet, List<Region> held) { this.bullet = bullet; this.held = held; }
        @Override public void close() {
            for (var region : held) region.flights.remove(bullet);
            held.clear();
        }
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
                regions.values().stream().mapToInt(r -> r.flights.size()).sum(), admitted, unknown, budgetDenied, timedOut);
    }
    public void releaseAll() {
        for (var key : regions.keySet()) removeTickets(key);
        regions.clear(); pending.clear(); starts.clear(); predictions.clear();
    }
    @SubscribeEvent public void unload(LevelEvent.Unload event) {
        regions.entrySet().removeIf(e -> {
            if (e.getKey().level != event.getLevel()) return false;
            removeTickets(e.getKey()); return true;
        });
        predictions.values().removeIf(p -> p.level == event.getLevel());
        known.removeIf(a -> a.level == event.getLevel());
        pending.values().removeIf(p -> p.key.level == event.getLevel());
    }
    @SubscribeEvent public void stopped(ServerStoppedEvent event) {
        releaseAll(); known.clear(); previous = null; clock = admitted = unknown = budgetDenied = timedOut = 0;
    }
}
