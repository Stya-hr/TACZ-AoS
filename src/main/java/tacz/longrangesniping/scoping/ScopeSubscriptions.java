package tacz.longrangesniping.scoping;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import tacz.longrangesniping.compat.tacz.TaczBridge;
import tacz.longrangesniping.config.ServerConfig;
import tacz.longrangesniping.mixin.ChunkMapAccessor;
import tacz.longrangesniping.mixin.TrackedEntityAccessor;
import tacz.longrangesniping.network.ScopeTargetsPayload;

import java.util.*;

/** Server-authoritative selective observers; no chunk tickets, disk reads or client-supplied entity IDs. */
public final class ScopeSubscriptions {
    public static final ScopeSubscriptions INSTANCE = new ScopeSubscriptions();
    private record Lease(ServerLevel level, Map<Integer, UUID> targets) {}
    private final Map<UUID, Lease> leases = new HashMap<>();
    private long tick;
    private ScopeSubscriptions() {}

    @SubscribeEvent public void tick(ServerTickEvent.Post event) {
        tick++;
        var players = new ArrayList<>(event.getServer().getPlayerList().getPlayers());
        // Rotate priority between refresh rounds so the global activity query cap does not always favor the same players.
        if (!players.isEmpty()) Collections.rotate(players, (int) (tick / ServerConfig.current().scopeRendering().intervalTicks() % players.size()));
        for (ServerPlayer player : players) {
            var settings = ServerConfig.current();
            var decision = TaczBridge.evaluate(player);
            var lease = leases.get(player.getUUID());
            if (!settings.available() || !settings.scopeRendering().enabled() || !decision.eligible()) {
                if (lease != null) replace(player, List.of());
                continue;
            }
            if (lease != null && lease.level != player.serverLevel()) replace(player, List.of());
            // Spread players over refresh slots; an initial scope gets an immediate refresh.
            if (lease == null || Math.floorMod(tick + player.getId(), settings.scopeRendering().intervalTicks()) == 0) {
                var selected = select(player, decision.zoom());
                ScopeActivity.INSTANCE.update(player, selected);
                replace(player, selected);
            }
        }
    }

    public List<Entity> select(ServerPlayer player, double zoom) {
        var level = player.serverLevel();
        var settings = ServerConfig.current();
        var config = settings.scopeRendering();
        Vec3 start = player.getEyePosition(), direction = player.getViewVector(1);
        double distance = settings.maxDistance();
        Vec3 end = start.add(direction.scale(distance));
        double radius = distance * Math.tan(ScopeCone.halfAngle(zoom)) + 4;
        AABB bounds = new AABB(start, end).inflate(radius);
        var candidates = new ArrayList<Entity>();
        int[] visited = {0};
        // The entity section index visits existing entities only, and the callback can abort without building an unbounded list.
        level.getEntities().get(EntityTypeTest.forClass(Entity.class), bounds, entity -> {
            if (++visited[0] > config.maxCandidates()) return AbortableIterationConsumer.Continuation.ABORT;
            var box = entity.getBoundingBox();
            Vec3 offset = box.getCenter().subtract(start);
            if (entity != player && entity.isAlive() && !entity.isInvisibleTo(player) && entity.broadcastToPlayer(player)
                    && !entity.isSpectator()
                    && level.getChunkSource().getChunkNow(entity.chunkPosition().x, entity.chunkPosition().z) != null
                    && ScopeCone.contains(offset.x, offset.y, offset.z, direction.x, direction.y, direction.z,
                    ScopeCone.radius(box.getXsize(), box.getYsize(), box.getZsize()), distance, zoom)) candidates.add(entity);
            return AbortableIterationConsumer.Continuation.CONTINUE;
        });
        // Centerline targets first, then nearer targets; deterministic tie-break avoids packet churn.
        candidates.sort(Comparator.<Entity>comparingDouble(entity -> {
            Vec3 offset = entity.getBoundingBox().getCenter().subtract(start);
            return 1 - offset.normalize().dot(direction);
        }).thenComparingDouble(player::distanceToSqr).thenComparingInt(Entity::getId));
        var visibility = new ScopeVisibility(level, config.maxTraceCells());
        var selected = new ArrayList<Entity>();
        for (var candidate : candidates) {
            if (selected.size() >= settings.maxTrackedEntities() || visibility.remaining() <= 0) break;
            if (visibility.clear(start, candidate.getEyePosition())
                    || visibility.clear(start, candidate.getBoundingBox().getCenter())) selected.add(candidate);
        }
        return List.copyOf(selected);
    }

    public boolean authorized(ServerPlayer player, Entity entity) {
        var lease = leases.get(player.getUUID());
        return lease != null && lease.level == entity.level() && lease.level == player.serverLevel()
                && entity.getUUID().equals(lease.targets.get(entity.getId())) && entity.isAlive() && !entity.isRemoved()
                && entity.broadcastToPlayer(player) && !entity.isInvisibleTo(player)
                && ServerConfig.current().scopeRendering().enabled() && TaczBridge.evaluate(player).eligible();
    }

    /** Reconciliation uses the existing native observer set, so returning to vanilla never duplicates or loses an entity. */
    public void replace(ServerPlayer player, List<? extends Entity> targets) {
        var previous = leases.remove(player.getUUID());
        var level = player.serverLevel();
        var ids = new LinkedHashMap<Integer, UUID>();
        for (var target : targets) ids.put(target.getId(), target.getUUID());
        if (ServerConfig.current().diagnostics() && (previous == null ? !ids.isEmpty() : !previous.targets.equals(ids))) {
            tacz.longrangesniping.TaczLongRangeSniping.LOGGER.info("Scoped entity subscription for {}: {} targets in {}",
                    player.getGameProfile().getName(), ids.size(), level.dimension().location());
        }
        if (!ids.isEmpty() || ServerConfig.current().scopeRendering().enabled() && TaczBridge.evaluate(player).eligible()) {
            leases.put(player.getUUID(), new Lease(level, Map.copyOf(ids)));
        }
        if (previous != null) for (int id : previous.targets.keySet()) {
            if (previous.level != level || !ids.containsKey(id)) {
                var tracker = tracker(previous.level, id);
                if (tracker != null) {
                    if (previous.level != level) tracker.taczlrs$removePlayer(player);
                    else tracker.taczlrs$updatePlayer(player);
                }
            }
        }
        var poses = new ArrayList<ScopeTargetsPayload.Target>();
        for (var target : targets) {
            var tracker = tracker(level, target.getId());
            if (tracker == null) continue;
            tracker.taczlrs$updatePlayer(player);
            if (!level.isPositionEntityTicking(target.blockPosition())) tracker.taczlrs$serverEntity().sendChanges();
            int light = level.getBrightness(LightLayer.BLOCK, target.blockPosition()) << 4
                    | level.getBrightness(LightLayer.SKY, target.blockPosition()) << 20;
            poses.add(new ScopeTargetsPayload.Target(target.getId(), target.getUUID(), target.getX(), target.getY(), target.getZ(),
                    target.getYRot(), target.getXRot(), target.getYHeadRot(), (target instanceof LivingEntity living ? living.yBodyRot : target.getYRot()), light, !normallyTracked(player, target, tracker)));
        }
        PacketDistributor.sendToPlayer(player, new ScopeTargetsPayload(level.dimension().location(),
                ServerConfig.current().scopeRendering().intervalTicks(), poses));
    }

    private static boolean normallyTracked(ServerPlayer player, Entity entity, TrackedEntityAccessor tracker) {
        var map = (ChunkMapAccessor) player.serverLevel().getChunkSource().chunkMap;
        Vec3 offset = entity.position().subtract(player.position());
        double range = Math.min(tracker.taczlrs$effectiveRange(), map.taczlrs$viewDistance(player) * 16);
        return offset.x * offset.x + offset.z * offset.z <= range * range
                && map.taczlrs$isChunkTracked(player, entity.chunkPosition().x, entity.chunkPosition().z);
    }

    public static TrackedEntityAccessor tracker(ServerLevel level, int id) {
        return (TrackedEntityAccessor) ((ChunkMapAccessor) level.getChunkSource().chunkMap).taczlrs$trackers().get(id);
    }
    public int count(ServerPlayer player) { var lease = leases.get(player.getUUID()); return lease == null ? 0 : lease.targets.size(); }
    @SubscribeEvent public void logout(PlayerEvent.PlayerLoggedOutEvent event) { leases.remove(event.getEntity().getUUID()); }
    @SubscribeEvent public void dimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) replace(player, List.of());
    }
    @SubscribeEvent public void unload(LevelEvent.Unload event) { leases.values().removeIf(lease -> lease.level == event.getLevel()); }
    @SubscribeEvent public void stopped(ServerStoppedEvent event) { leases.clear(); tick = 0; }
}
