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
    public record Status(int active, long accepted, long finished, long unknown, long rangeLimited,
                         long lifetimeLimited, long blocked, String lastTermination) {
        public Status(int active, long accepted, long finished, long unknown, long rangeLimited) {
            this(active, accepted, finished, unknown, rangeLimited, 0, 0, "none");
        }
    }
    private static final class Flight {
        final EntityKineticBullet bullet;
        final ShotContext context;
        final ServerConfig.Ballistics limits;
        final ScopeActivity.FlightLease lease;
        final tacz.longrangesniping.terrain.SnapshotLease snapshots = tacz.longrangesniping.terrain.TerrainSnapshots.INSTANCE.lease();
        final int originalLife, installedLife;
        double traveled;
        int ticks;
        boolean snapshotPhysics;
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

    private final ServerLevel level;
    private final Map<UUID, Flight> active = new LinkedHashMap<>();
    // A hit callback may spawn another bullet while the active map is being iterated.
    private final ArrayDeque<Flight> pending = new ArrayDeque<>();
    private long accepted, finished, unknown, rangeLimited, lifetimeLimited, blocked;
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
        var visuals = new LinkedHashMap<ServerPlayer, List<TrailSegment>>();
        while (!pending.isEmpty()) {
            Flight flight = pending.remove();
            active.put(flight.bullet.getUUID(), flight);
        }
        var iterator = active.values().iterator();
        while (iterator.hasNext()) {
            Flight flight = iterator.next();
            String termination;
            try { termination = advance(flight); }
            catch (RuntimeException | Error failure) { flight.lease.close(); flight.snapshots.close(); throw failure; }
            collectVisual(flight, termination != null, visuals);
            if (termination != null) {
                flight.lease.close(); flight.snapshots.close();
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
        visuals.forEach((player, segments) -> PacketDistributor.sendToPlayer(player,
                new ShotTrailsPayload(level.dimension().location(), segments)));
    }

    private void collectVisual(Flight flight, boolean terminal, Map<ServerPlayer, List<TrailSegment>> batches) {
        if (flight.visualStart == null || flight.visualEnd == null) return;
        var player = level.getServer().getPlayerList().getPlayer(flight.context.shooterId());
        if (player == null || player.serverLevel() != level) return;
        var batch = batches.computeIfAbsent(player, ignored -> new ArrayList<>());
        if (batch.size() >= ShotTrailsPayload.MAX_SEGMENTS) return;
        var a = flight.visualStart; var b = flight.visualEnd;
        try {
            batch.add(new TrailSegment(flight.bullet.getId(), flight.ticks, a.x, a.y, a.z, b.x, b.y, b.z, flight.visualSpeed, terminal));
        } catch (IllegalArgumentException ignored) {
            // Third-party tick callbacks may teleport projectiles. Never draw a fictitious connecting segment.
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
        if (!(owner instanceof net.minecraft.world.entity.player.Player player)
                || !tacz.longrangesniping.block.RangefinderAccess.nearby(player)) return stop(bullet, "rangefinder_unavailable");
        if (flight.ticks++ >= flight.limits.maxFlightTicks()) return stop(bullet, "timeout");
        // Mirror ServerLevel.tickNonPassenger, including NeoForge tick cancellation.
        bullet.setOldPosAndRot();
        bullet.tickCount++;
        if (EventHooks.fireEntityTickPre(bullet).isCanceled()) return null;
        if (bullet.isRemoved()) return "removed_by_tick_event";
        if (!plainKinetic(bullet)) return stop(bullet, "unsupported_ammunition_changed");
        // A tick-pre listener can change the trajectory; validate it again before TACZ reads terrain.
        tacz.longrangesniping.terrain.TerrainSnapshots.INSTANCE.prepare(level, bullet.position(),
                bullet.position().add(bullet.getDeltaMovement().scale(2)), flight.snapshots);
        var guard = LoadedAreaGuard.check(level, bullet, flight.limits.maxDistance() - flight.traveled);
        if (guard == LoadedAreaGuard.Result.INVALID) return stop(bullet, "INVALID");
        flight.visualStart = bullet.position();
        flight.visualSpeed = (float) bullet.getDeltaMovement().length();
        if (flight.snapshotPhysics || guard != LoadedAreaGuard.Result.READY) {
            // Stay on this path after crossing; stale base-entity fluid/portal state is never resumed.
            flight.snapshotPhysics = true;
            var step = SnapshotBallistics.tick(level, bullet, flight.limits.maxDistance() - flight.traveled, flight.snapshots);
            flight.visualEnd = bullet.position();
            EventHooks.fireEntityTickPost(bullet);
            flight.traveled += step.distance();
            if ("UNKNOWN_AREA".equals(step.termination())) unknown++;
            if ("RANGE_LIMIT".equals(step.termination())) rangeLimited++;
            return step.termination();
        }
        double stepLength = bullet.getDeltaMovement().length();
        // Reuse TACZ's exact physics, block ray tracing, headshots, piercing, damage and events.
        // Do not call addFreshEntity, shoot(), or a second damage implementation here.
        var capture = (BulletVisualAccess) bullet;
        capture.taczlrs$beginVisualStep();
        Vec3 impact;
        try { bullet.tick(); }
        finally { impact = capture.taczlrs$endVisualStep(); }
        flight.visualEnd = impact == null ? bullet.position() : impact;
        EventHooks.fireEntityTickPost(bullet);
        flight.traveled += stepLength;
        return bullet.isRemoved() ? (bullet.tickCount >= ((KineticBulletAccessor) bullet).taczlrs$getLife() - 1
                ? "lifetime" : "native_hit") : null;
    }

    private static String stop(EntityKineticBullet bullet, String reason) {
        bullet.discard();
        return reason;
    }

    private static boolean plainKinetic(EntityKineticBullet bullet) {
        var fields = (KineticBulletAccessor) bullet;
        return !fields.taczlrs$isExplosive() && !fields.taczlrs$ignitesEntities() && !fields.taczlrs$ignitesBlocks();
    }

    public Status status() { return new Status(active.size() + pending.size(), accepted, finished, unknown, rangeLimited,
            lifetimeLimited, blocked, lastTermination); }

    public void clear() {
        active.values().forEach(f -> { f.lease.close(); f.snapshots.close(); f.bullet.discard(); });
        pending.forEach(f -> { f.lease.close(); f.snapshots.close(); f.bullet.discard(); });
        active.clear();
        pending.clear();
    }
}
