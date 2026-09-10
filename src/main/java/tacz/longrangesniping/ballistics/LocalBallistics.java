package tacz.longrangesniping.ballistics;

import com.tacz.guns.entity.EntityKineticBullet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.network.PacketDistributor;
import tacz.longrangesniping.TaczLongRangeSniping;
import tacz.longrangesniping.compat.tacz.TaczBridge.ShotContext;
import tacz.longrangesniping.config.ServerConfig;
import tacz.longrangesniping.mixin.KineticBulletAccessor;
import tacz.longrangesniping.scoping.ScopeActivity;
import tacz.longrangesniping.network.ShotTrailsPayload;
import tacz.longrangesniping.trails.TrailSegment;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;

/** Owns detached TACZ projectiles; only this scheduler advances accepted shots. Server thread only. */
public final class LocalBallistics {
    public enum Admission { ACCEPTED, DISABLED, UNSUPPORTED, CAPACITY, ALREADY_REGISTERED }
    public record Status(int active, long accepted, long finished, long rangeLimited,
                         long lifetimeLimited, long blocked, String lastTermination) {
        public Status(int active, long accepted, long finished, long rangeLimited) {
            this(active, accepted, finished, rangeLimited, 0, 0, "none");
        }
    }
    private static final class Flight {
        final EntityKineticBullet bullet;
        final ShotContext context;
        final ServerConfig.Ballistics limits;
        final ScopeActivity.FlightLease lease;
        final java.util.Set<UUID> heardBy = new java.util.HashSet<>();
        final int originalLife, installedLife;
        double traveled;
        int ticks;
        Vec3 visualStart, visualEnd;
        float visualSpeed;
        Flight(EntityKineticBullet bullet, ShotContext context, ServerConfig.Ballistics limits) {
            this.bullet = bullet;
            this.context = context;
            this.limits = limits;
            var fields = (KineticBulletAccessor) bullet;
            originalLife = fields.taczlrs$getLife();
            installedLife = limits.extendLifetime() ? Math.max(originalLife, limits.maxFlightTicks() + 1) : originalLife;
            fields.taczlrs$setLife(installedLife);
            lease = ScopeActivity.INSTANCE.retainForShot(bullet, bullet.getOwner(), limits.maxFlightTicks(), limits.maxDistance());
        }
    }

    private record Viewer(ServerPlayer player, boolean localOnly) {}
    private final ServerLevel level;
    private final Map<UUID, Flight> active = new LinkedHashMap<>();
    // A hit callback may spawn another bullet while the active map is being iterated.
    private final ArrayDeque<Flight> pending = new ArrayDeque<>();
    private long accepted, finished, rangeLimited, lifetimeLimited, blocked;
    private String lastTermination = "none";

    public LocalBallistics(ServerLevel level) { this.level = level; }

    public Admission admit(EntityKineticBullet bullet, ShotContext context, ServerConfig.Ballistics limits) {
        if (!limits.enabled()) return Admission.DISABLED;
        if (bullet.getClass() != EntityKineticBullet.class || !context.eligibilityAtFire().eligible()
                || !context.parameters().isPlainKinetic() || !plainKinetic(bullet) || bullet.level() != level
                || bullet.isRemoved() || bullet.tickCount != 0) return Admission.UNSUPPORTED;
        if (level.getEntity(bullet.getUUID()) != null) return Admission.ALREADY_REGISTERED;
        if (active.containsKey(bullet.getUUID()) || pending.stream().anyMatch(f -> f.bullet == bullet)) return Admission.ACCEPTED;
        if (active.size() + pending.size() >= limits.maxActiveBullets()) return Admission.CAPACITY;
        pending.add(new Flight(bullet, context, limits));
        accepted++;
        return Admission.ACCEPTED;
    }

    public void tick() {
        var visuals = new LinkedHashMap<Viewer, List<TrailSegment>>();
        while (!pending.isEmpty()) {
            Flight flight = pending.remove();
            active.put(flight.bullet.getUUID(), flight);
        }
        var iterator = active.values().iterator();
        while (iterator.hasNext()) {
            Flight flight = iterator.next();
            String termination;
            try { termination = advance(flight); }
            catch (RuntimeException | Error failure) { flight.lease.close(); throw failure; }
            collectVisual(flight, termination != null, visuals);
            if (termination != null) {
                flight.lease.close();
                iterator.remove();
                finished++;
                lastTermination = termination;
                if ("lifetime".equals(termination) || "timeout".equals(termination)) lifetimeLimited++;
                if ("BLOCK".equals(termination)) blocked++;
                if (ServerConfig.current().diagnostics()) {
                    TaczLongRangeSniping.LOGGER.info("Scoped shot {} ended: {}, ticks={}, distance={}",
                            flight.bullet.getUUID(), termination, flight.ticks, flight.traveled);
                }
            }
        }
        visuals.forEach((viewer, segments) -> PacketDistributor.sendToPlayer(viewer.player(),
                new ShotTrailsPayload(level.dimension().location(), viewer.localOnly(), segments)));
    }

    private void collectVisual(Flight flight, boolean terminal, Map<Viewer, List<TrailSegment>> batches) {
        if (flight.visualStart == null || flight.visualEnd == null) return;
        var a = flight.visualStart; var b = flight.visualEnd;
        final TrailSegment segment;
        try {
            segment = new TrailSegment(flight.bullet.getId(), flight.ticks, a.x, a.y, a.z, b.x, b.y, b.z, flight.visualSpeed, terminal);
        } catch (IllegalArgumentException ignored) { return; }
        var shooter = level.getServer().getPlayerList().getPlayer(flight.context.shooterId());
        if (shooter != null && shooter.serverLevel() == level) append(batches, new Viewer(shooter, false), List.of(segment));
        var map = (tacz.longrangesniping.mixin.ChunkMapAccessor) level.getChunkSource().chunkMap;
        for (var observer : level.players()) {
            if (observer.getUUID().equals(flight.context.shooterId()) || !observer.isAlive() || observer.isSpectator()) continue;
            // Cheap rejection before visiting chunk columns; tracking membership never requests chunks.
            double radius = (map.taczlrs$viewDistance(observer) + 2) * 16.0;
            if (observer.getX() < Math.min(a.x,b.x)-radius || observer.getX() > Math.max(a.x,b.x)+radius
                    || observer.getZ() < Math.min(a.z,b.z)-radius || observer.getZ() > Math.max(a.z,b.z)+radius) continue;
            var viewer = new Viewer(observer, true);
            if (!batches.containsKey(viewer) || batches.get(viewer).size() < ShotTrailsPayload.MAX_SEGMENTS) {
                append(batches, viewer, tacz.longrangesniping.trails.TrailAudience.clip(segment,
                        (x,z) -> map.taczlrs$isChunkTracked(observer, x, z)));
            }
            if (flight.heardBy.contains(observer.getUUID())) continue;
            // A direct hit has its own TACZ feedback. Use the real, collision-truncated segment only.
            if (observer.getBoundingBox().contains(a) || observer.getBoundingBox().clip(a,b).isPresent()) {
                flight.heardBy.add(observer.getUUID()); continue;
            }
            var ear = observer.getEyePosition();
            var pass = tacz.longrangesniping.trails.TrailAudience.nearMiss(segment, ear.x, ear.y, ear.z);
            if (pass != null && map.taczlrs$isChunkTracked(observer, (int)Math.floor(pass.x()/16), (int)Math.floor(pass.z()/16))) {
                flight.heardBy.add(observer.getUUID());
                PacketDistributor.sendToPlayer(observer, new tacz.longrangesniping.network.NearMissPayload(
                        level.dimension().location(), pass.x(), pass.y(), pass.z(), pass.volume()));
            }
        }
    }

    private static void append(Map<Viewer, List<TrailSegment>> batches, Viewer viewer, List<TrailSegment> pieces) {
        if (pieces.isEmpty()) return;
        var batch = batches.computeIfAbsent(viewer, ignored -> new ArrayList<>());
        for (var piece : pieces) {
            if (batch.size() >= ShotTrailsPayload.MAX_SEGMENTS) break;
            batch.add(piece);
        }
    }

    private String advance(Flight flight) {
        flight.visualStart = flight.visualEnd = null;
        EntityKineticBullet bullet = flight.bullet;
        // If a later event handler reversed spawn cancellation, world ticking owns it.
        // Relinquish it without ticking or discarding the now-registered entity.
        if (level.getEntity(bullet.getUUID()) == bullet) {
            var fields = (KineticBulletAccessor) bullet;
            if (fields.taczlrs$getLife() == flight.installedLife) fields.taczlrs$setLife(flight.originalLife);
            return "native_ownership_restored";
        }
        var owner = bullet.getOwner();
        if (bullet.isRemoved()) return "native_removed";
        if (!plainKinetic(bullet)) return stop(bullet, "unsupported_ammunition_changed");
        if (owner == null || !owner.isAlive() || owner.isRemoved() || owner.level() != level) {
            return stop(bullet, "owner_unavailable");
        }
        if (flight.ticks++ >= flight.limits.maxFlightTicks()) return stop(bullet, "timeout");
        // Mirror ServerLevel.tickNonPassenger, including NeoForge tick cancellation.
        bullet.setOldPosAndRot();
        bullet.tickCount++;
        if (EventHooks.fireEntityTickPre(bullet).isCanceled()) return null;
        if (bullet.isRemoved()) return "removed_by_tick_event";
        if (!plainKinetic(bullet)) return stop(bullet, "unsupported_ammunition_changed");
        flight.visualStart = bullet.position();
        flight.visualSpeed = (float) bullet.getDeltaMovement().length();
        var step = TargetBallistics.tick(level, bullet, flight.limits.maxDistance()-flight.traveled,new TargetCollisionSource(level,flight.lease.bounds()));
        flight.visualEnd=bullet.position();
        EventHooks.fireEntityTickPost(bullet);
        flight.traveled+=step.distance();
        if ("RANGE_LIMIT".equals(step.termination())) rangeLimited++;
        return step.termination();
    }

    private static String stop(EntityKineticBullet bullet, String reason) {
        bullet.discard();
        return reason;
    }

    private static boolean plainKinetic(EntityKineticBullet bullet) {
        var fields = (KineticBulletAccessor) bullet;
        return !fields.taczlrs$isExplosive() && !fields.taczlrs$ignitesEntities() && !fields.taczlrs$ignitesBlocks();
    }

    public Status status() { return new Status(active.size() + pending.size(), accepted, finished, rangeLimited,
            lifetimeLimited, blocked, lastTermination); }

    public void clear() {
        active.values().forEach(f -> { f.lease.close(); f.bullet.discard(); });
        pending.forEach(f -> { f.lease.close(); f.bullet.discard(); });
        active.clear();
        pending.clear();
    }
}
