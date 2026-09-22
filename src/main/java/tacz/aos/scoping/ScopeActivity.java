package tacz.aos.scoping;

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
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.TickEvent;
import tacz.aos.compat.tacz.TaczBridge;
import tacz.aos.config.ServerConfig;

import java.util.*;

/** Temporary native simulation. Only ticket admission is performed here; never synchronously requests a chunk. */
public final class ScopeActivity {
    public static final ScopeActivity INSTANCE = new ScopeActivity();
    private static final TicketType<ChunkPos> TICKET = TicketType.create("tacz_aos_scope_activity",
            Comparator.comparingLong(ChunkPos::toLong));
    private record Address(ServerLevel level, int x, int z) {}
    private record Key(ServerLevel level, int x, int z, int radius) {}
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
    private final Map<UUID,AimStability> stability = new HashMap<>();
    private final Map<UUID,ScopeLoadThrottle> throttles = new HashMap<>();
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

    @SubscribeEvent public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        clock++;
        ScopeAim.prune(net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer());
        queries = 0;
        predictionNanos = 0;
        predictions.entrySet().removeIf(e -> {
            var player = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayer(e.getKey());
            return player == null || player.serverLevel() != e.getValue().level || !enabled(player);
        });
        var settings = ServerConfig.current();
        var config = settings.activity();
        if (!Objects.equals(previous, config) || !settings.available() || !config.enabled() || !settings.scopeRendering().enabled()) {
            releaseAll(); previous = config;
        }
        starts.removeIf(t -> clock - t >= 20);
        for (var iterator = regions.values().iterator(); iterator.hasNext();) {
            var region = iterator.next();
            region.owners.entrySet().removeIf(e -> {
                var player = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayer(e.getKey());
                var view = region.views.get(e.getKey());
                // A still-held, unchanged reticle may finish its already-authorized load, bounded by the loading deadline.
                if (!region.ready && player != null && enabled(player) && player.serverLevel() == region.key.level
                        && view != null && player.getEyePosition().distanceToSqr(view.eye) < 1
                        && player.getViewVector(1).dot(view.direction) > 0.99996) {
                    e.setValue(clock + config.retentionTicks());
                }
                return e.getValue() < clock || player == null || player.serverLevel() != region.key.level;
            });
            region.views.keySet().retainAll(region.owners.keySet());
            region.flights.values().removeIf(hold -> {
                var player = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayer(hold.owner);
                return hold.deadline < clock || player == null || player.serverLevel() != region.key.level;
            });
            region.ready = ready(region.key);
            boolean timeout = !region.ready && clock - region.started >= config.loadTimeoutTicks();
            if ((region.owners.isEmpty() && region.flights.isEmpty()) || timeout) {
                if (timeout) timedOut++;
                removeTickets(region.key); iterator.remove();
            }
        }
        for(var player:net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayers()) {
            UUID id=player.getUUID();
            if(!enabled(player)) { stability.remove(id); ScopeAim.remove(id); continue; }
            Vec3 direction=player.getViewVector(1).normalize();
            boolean stable=stability.computeIfAbsent(id,k->new AimStability()).update(direction.x,direction.y,direction.z,clock,
                    ServerConfig.targeting().stableDegreesPerSecond(),config.stableTicks());
            Vec3 target=ScopeAim.target(player);
            // Read the current aim only; metadata completion never schedules an obsolete target.
            if(target!=null) consider(player,target,stable);
        }
        stability.keySet().removeIf(id->net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayer(id)==null);
        throttles.keySet().removeIf(id->net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayer(id)==null);
    }

    /** Called on the server's bounded subscription refresh, using server-validated visible candidates. */
    public void update(ServerPlayer player, List<? extends Entity> visible) {
        var id = player.getUUID();
        if (!enabled(player)) { predictions.remove(id); return; }
        var settings = ServerConfig.current();
        var last = predictions.get(id);
        if (last != null && last.level == player.serverLevel() && clock < last.nextTick) return;
        long budget = (long) (settings.prediction().maxMillisPerTick() * 1_000_000);
        if (queries >= settings.activity().maxQueriesPerTick() || predictionNanos >= budget) return;
        queries++;
        long started = System.nanoTime();
        var shot = tacz.aos.compat.tacz.BallisticPreview.read(player).orElse(null);
        Vec3 target = null;
        var crossed = new HashSet<Region>();
        String reason = "UNSUPPORTED_PARAMETERS";
        if (shot != null) {
            var limits = settings.ballistics();
            int life = Math.max(1, shot.lifeTicks() - 1);
            int ticks = limits.enabled() && limits.extendLifetime() ? limits.maxFlightTicks()
                    : Math.min(life, limits.maxFlightTicks());
            double distance = limits.enabled() ? Math.min(settings.maxDistance(), limits.maxDistance()) : settings.maxDistance();
            var observed=new LinkedHashMap<Region,AABB>();
            for(var region:regions.values()) if(region.key.level==player.serverLevel() && region.owners.containsKey(id))
                observed.put(region,bounds(region.key));
            var targetBounds=List.copyOf(observed.values());
            var boxes = visible.stream().filter(e -> !e.isRemoved() && e != player && e.isPickable() && targetBounds.stream().anyMatch(b->b.intersects(e.getBoundingBox())))
                    .limit(settings.maxTrackedEntities()).map(Entity::getBoundingBox).toList();
            var result = ImpactPrediction.predict(shot, new tacz.aos.ballistics.TargetCollisionSource(player.serverLevel(),targetBounds),
                    boxes, distance, ticks, settings.scopeRendering().maxTraceCells(),
                    settings.prediction().maxEntityChecks(), started + budget - predictionNanos, (from,to)-> {
                        for(var entry:observed.entrySet()) if(!crossed.contains(entry.getKey())
                                && (entry.getValue().contains(from) || entry.getValue().clip(from,to).isPresent())) crossed.add(entry.getKey());
                    });
            target = result.target(); reason = result.end().name();
        }
        predictionNanos += System.nanoTime() - started;
        if (settings.diagnostics() && (last == null || !last.reason.equals(reason))) {
            tacz.aos.TaczArtOfSniping.LOGGER.info("Scope prediction for {}: {}, target={}",
                    player.getGameProfile().getName(), reason, target);
        }
        predictions.put(id, new PredictionState(player.serverLevel(), clock + settings.prediction().intervalTicks(), reason));
        // A path through air still keeps its already-owned core alive. Prediction never admits new terrain.
        for(var region:crossed) region.owners.put(id,clock+settings.activity().retentionTicks());
        if(target!=null) for(var region:regions.values()) {
            var k=region.key;
            if(k.level==player.serverLevel() && region.owners.containsKey(id)
                && contains(k,new Address(k.level,net.minecraft.util.Mth.floor(target.x/16),net.minecraft.util.Mth.floor(target.z/16)),k.radius))
                region.owners.put(id,clock+settings.activity().retentionTicks());
        }
    }

    /** Admission entry point for server-validated candidates. Every new region requires generated terrain metadata. */
    public void consider(ServerPlayer player, Vec3 target) {
        consider(player,target,true);
    }
    private void consider(ServerPlayer player, Vec3 target, boolean allowNewRegion) {
        var id = player.getUUID();
        if (!enabled(player) || target == null || !Double.isFinite(target.x + target.y + target.z)
                || target.distanceToSqr(player.getEyePosition()) > Math.pow(ServerConfig.current().maxDistance(), 2)) {
            return;
        }
        var config = ServerConfig.current().activity();
        var pos = new ChunkPos(BlockPos.containing(target));
        Key key = new Key(player.serverLevel(), pos.x, pos.z, config.radius());
        Region retained=null;
        for(var region:regions.values()) if(ownedBy(region,id)
                && contains(region.key,new Address(key.level,key.x,key.z),region.key.radius)) {retained=region;break;}
        var shared=regions.get(key);
        // Fast movement blocks new admission, not renewal of the region we already own.
        // Reusing tickets needs neither disk metadata nor another request cooldown.
        if(retained!=null) {
            retained.owners.put(id,clock+config.retentionTicks());
            retained.views.put(id,new View(player.getEyePosition(),player.getViewVector(1)));
            return;
        }
        if(!allowNewRegion)return;
        // All new attempts, even unknown terrain or budget refusals, share the short check interval.
        var throttle=throttles.computeIfAbsent(id,ignored->new ScopeLoadThrottle());
        var targeting=ServerConfig.targeting();
        if(!throttle.tryCheck(clock,targeting.requestIntervalTicks()))return;
        // Cheap reuse keeps observed entities alive even when a new region would still be on cooldown.
        if(retained==null && shared==null && !throttle.canStart(clock,targeting.newRegionIntervalTicks()))return;
        var generated=tacz.aos.terrain.GeneratedChunks.INSTANCE;
        if(generated.path(player.serverLevel(),player.getEyePosition(),target)!=tacz.aos.terrain.GeneratedChunks.State.FULL) {unknown++;return;}
        long owned = regions.values().stream().filter(r -> ownedBy(r, id)).count();
        if (owned >= config.maxRegionsPerPlayer()) { budgetDenied++; return; }
        if (shared != null) {
            shared.owners.put(id, clock + config.retentionTicks());
            shared.views.put(id, new View(player.getEyePosition(), player.getViewVector(1)));
            return;
        }
        if (regions.size() >= config.maxRegions() || starts.size() >= config.startsPerSecond()
                || footprint(key, 2).size() > config.maxFullChunks()
                || footprint(key, 2 + net.minecraft.world.level.chunk.ChunkStatus.maxDistance()).size() > config.maxDependencyChunks()) {
            budgetDenied++; return;
        }
        // Batch all halo checks, not one unknown column per refresh. Never generate a frontier.
        boolean complete=true;
        for (var address:square(key,key.radius+3)) {
            var state=generated.state(key.level,address.x,address.z);
            if(state==tacz.aos.terrain.GeneratedChunks.State.ABSENT){unknown++;return;}
            complete &= state==tacz.aos.terrain.GeneratedChunks.State.FULL;
        }
        if(!complete){unknown++;return;}
        var region = new Region(key, clock);
        region.owners.put(id, clock + config.retentionTicks());
        region.views.put(id, new View(player.getEyePosition(), player.getViewVector(1)));
        regions.put(key, region);
        for (var address : square(key, key.radius)) {
            if (cores.merge(address, 1, Integer::sum) == 1) {
                var chunk = new ChunkPos(address.x, address.z);
                // Level 31 per core chunk, including Forge random ticking; no Future.join/getChunk call.
                key.level.getChunkSource().addRegionTicket(TICKET, chunk, 2, chunk, true);
            }
        }
        throttle.started(clock);
        starts.addLast(clock); admitted++;
        if (ServerConfig.current().diagnostics()) tacz.aos.TaczArtOfSniping.LOGGER.info(
                "Scope activity admitted {} at tick {}", key, clock);
    }

    private boolean enabled(ServerPlayer player) {
        var settings = ServerConfig.current();
        return settings.available() && settings.activity().enabled() && settings.scopeRendering().enabled()
                && player.isAlive() && !player.isSpectator() && TaczBridge.evaluate(player).eligible();
    }
    /** Small, already budgeted cores first; querying these boxes cannot request any terrain. */
    public List<AABB> observationBounds(ServerPlayer player) {
        var result = new ArrayList<AABB>();
        for (var region : regions.values()) {
            var k=region.key;
            if (k.level != player.serverLevel() || !region.owners.containsKey(player.getUUID())) continue;
            result.add(bounds(k));
        }
        return result;
    }
    private static AABB bounds(Key k) {
        return new AABB((k.x-k.radius)*16,k.level.getMinBuildHeight(),(k.z-k.radius)*16,
                (k.x+k.radius+1)*16,k.level.getMaxBuildHeight(),(k.z+k.radius+1)*16);
    }

    /** A displayable remote body must fit wholly in this observer's collision cores.
     * Readiness checks never acquire terrain; dependency halos alone cannot authorize display.
     */
    public boolean observable(ServerPlayer player, AABB body) {
        var level=player.serverLevel();
        boolean covered=false;
        for(var region:regions.values()) {
            if(region.key.level!=level || !region.owners.containsKey(player.getUUID()))continue;
            var core=bounds(region.key);
            if(body.minX>=core.minX && body.maxX<core.maxX && body.minY>=core.minY && body.maxY<core.maxY
                    && body.minZ>=core.minZ && body.maxZ<core.maxZ && ready(region.key)) {covered=true;break;}
        }
        if(!covered)return false;
        int x0=net.minecraft.util.Mth.floor(body.minX)>>4,x1=net.minecraft.util.Mth.floor(body.maxX)>>4;
        int z0=net.minecraft.util.Mth.floor(body.minZ)>>4,z1=net.minecraft.util.Mth.floor(body.maxZ)>>4;
        for(int x=x0-1;x<=x1+1;x++)for(int z=z0-1;z<=z1+1;z++)
            if(level.getChunkSource().getChunkNow(x,z)==null)return false;
        return true;
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

    /** Holds the shooter's already budgeted regions for this flight. Never adds a ticket or searches the corridor. */
    public FlightLease retainForShot(Entity bullet, Entity owner, int maxFlightTicks, int maxDistance) {
        var held = new ArrayList<Region>();
        Vec3 start = bullet.position(), velocity = bullet.getDeltaMovement();
        if (owner == null || !Double.isFinite(velocity.lengthSqr()) || velocity.lengthSqr() == 0) return new FlightLease(bullet.getUUID(), held);
        // Capture all owned cores, bounded by per-player/global budgets; wind can bend the horizontal path.
        for (var region : regions.values()) {
            var key = region.key;
            if (key.level != bullet.level() || !region.owners.containsKey(owner.getUUID())) continue;
                region.flights.put(bullet.getUUID(), new FlightHold(owner.getUUID(), clock + maxFlightTicks + 2L));
                held.add(region);
        }
        return new FlightLease(bullet.getUUID(), held);
    }

    public static final class FlightLease implements AutoCloseable {
        private final UUID bullet;
        private final List<Region> held;
        private FlightLease(UUID bullet, List<Region> held) { this.bullet = bullet; this.held = held; }
        public List<AABB> bounds() {
            return held.stream().filter(r->r.flights.containsKey(bullet)).map(r->{var k=r.key;
                return new AABB((k.x-k.radius)*16,k.level.getMinBuildHeight(),(k.z-k.radius)*16,
                        (k.x+k.radius+1)*16,k.level.getMaxBuildHeight(),(k.z+k.radius+1)*16);}).toList();
        }
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
        if (ServerConfig.current().diagnostics()) tacz.aos.TaczArtOfSniping.LOGGER.info(
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
                footprint(null, 2).size(), footprint(null, 2 + net.minecraft.world.level.chunk.ChunkStatus.maxDistance()).size(),
                regions.values().stream().mapToInt(r -> r.flights.size()).sum(), admitted, unknown, budgetDenied, timedOut);
    }
    public void releaseAll() {
        for (var key : regions.keySet()) removeTickets(key);
        regions.clear(); starts.clear(); predictions.clear();
    }
    @SubscribeEvent public void unload(LevelEvent.Unload event) {
        regions.entrySet().removeIf(e -> {
            if (e.getKey().level != event.getLevel()) return false;
            removeTickets(e.getKey()); return true;
        });
        predictions.values().removeIf(p -> p.level == event.getLevel());
        ScopeAim.clear(); stability.clear();
    }
    @SubscribeEvent public void stopped(ServerStoppedEvent event) {
        releaseAll(); ScopeAim.clear(); stability.clear(); throttles.clear(); previous = null; clock = admitted = unknown = budgetDenied = timedOut = 0;
    }
}
