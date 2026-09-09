package tacz.longrangesniping.ballistics;

import com.tacz.guns.entity.EntityKineticBullet;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.EventHooks;
import tacz.longrangesniping.TaczLongRangeSniping;
import tacz.longrangesniping.compat.tacz.TaczBridge.ShotContext;
import tacz.longrangesniping.config.ServerConfig;
import tacz.longrangesniping.mixin.KineticBulletAccessor;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Owns detached TACZ projectiles; only this scheduler advances accepted shots. Server thread only. */
public final class LocalBallistics {
    public enum Admission { ACCEPTED, DISABLED, UNSUPPORTED, CAPACITY, ALREADY_REGISTERED }
    public record Status(int active, long accepted, long finished, long unknown, long rangeLimited) {}
    private static final class Flight {
        final EntityKineticBullet bullet;
        final ShotContext context;
        final ServerConfig.Prototype limits;
        double traveled;
        int ticks;
        Flight(EntityKineticBullet bullet, ShotContext context, ServerConfig.Prototype limits) {
            this.bullet = bullet;
            this.context = context;
            this.limits = limits;
        }
    }

    private final ServerLevel level;
    private final Map<UUID, Flight> active = new LinkedHashMap<>();
    // A hit callback may spawn another bullet while the active map is being iterated.
    private final ArrayDeque<Flight> pending = new ArrayDeque<>();
    private long accepted, finished, unknown, rangeLimited;

    public LocalBallistics(ServerLevel level) { this.level = level; }

    public Admission admit(EntityKineticBullet bullet, ShotContext context, ServerConfig.Prototype limits) {
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
        while (!pending.isEmpty()) {
            Flight flight = pending.remove();
            active.put(flight.bullet.getUUID(), flight);
        }
        var iterator = active.values().iterator();
        while (iterator.hasNext()) {
            Flight flight = iterator.next();
            String termination = advance(flight);
            if (termination != null) {
                iterator.remove();
                finished++;
                if (ServerConfig.current().diagnostics()) {
                    TaczLongRangeSniping.LOGGER.info("Prototype shot {} ended: {}, ticks={}, distance={}",
                            flight.bullet.getUUID(), termination, flight.ticks, flight.traveled);
                }
            }
        }
    }

    private String advance(Flight flight) {
        EntityKineticBullet bullet = flight.bullet;
        // If a later event handler reversed spawn cancellation, world ticking owns it.
        // Relinquish it without ticking or discarding the now-registered entity.
        if (level.getEntity(bullet.getUUID()) == bullet) return "native_ownership_restored";
        var owner = bullet.getOwner();
        if (bullet.isRemoved()) return "native_removed";
        if (!plainKinetic(bullet)) return stop(bullet, "unsupported_ammunition_changed");
        if (owner == null || !owner.isAlive() || owner.isRemoved() || owner.level() != level) {
            return stop(bullet, "owner_unavailable");
        }
        if (flight.ticks++ >= flight.limits.maxFlightTicks()) return stop(bullet, "timeout");
        var guard = LoadedAreaGuard.check(level, bullet, flight.limits.maxDistance() - flight.traveled);
        if (guard != LoadedAreaGuard.Result.READY) return stopAtGuard(bullet, guard);

        // Mirror ServerLevel.tickNonPassenger, including NeoForge tick cancellation.
        bullet.setOldPosAndRot();
        bullet.tickCount++;
        if (EventHooks.fireEntityTickPre(bullet).isCanceled()) return null;
        if (bullet.isRemoved()) return "removed_by_tick_event";
        if (!plainKinetic(bullet)) return stop(bullet, "unsupported_ammunition_changed");
        // A tick-pre listener can change the trajectory; validate it again before TACZ reads terrain.
        guard = LoadedAreaGuard.check(level, bullet, flight.limits.maxDistance() - flight.traveled);
        if (guard != LoadedAreaGuard.Result.READY) return stopAtGuard(bullet, guard);
        double stepLength = bullet.getDeltaMovement().length();
        // Reuse TACZ's exact physics, block ray tracing, headshots, piercing, damage and events.
        // Do not call addFreshEntity, shoot(), or a second damage implementation here.
        bullet.tick();
        EventHooks.fireEntityTickPost(bullet);
        flight.traveled += stepLength;
        return bullet.isRemoved() ? "native_hit_or_lifetime" : null;
    }

    private String stopAtGuard(EntityKineticBullet bullet, LoadedAreaGuard.Result result) {
        if (result == LoadedAreaGuard.Result.UNKNOWN_AREA) unknown++;
        if (result == LoadedAreaGuard.Result.RANGE_LIMIT) rangeLimited++;
        return stop(bullet, result.name());
    }

    private static String stop(EntityKineticBullet bullet, String reason) {
        bullet.discard();
        return reason;
    }

    private static boolean plainKinetic(EntityKineticBullet bullet) {
        var fields = (KineticBulletAccessor) bullet;
        return !fields.taczlrs$isExplosive() && !fields.taczlrs$ignitesEntities() && !fields.taczlrs$ignitesBlocks();
    }

    public Status status() { return new Status(active.size() + pending.size(), accepted, finished, unknown, rangeLimited); }

    public void clear() {
        active.values().forEach(f -> f.bullet.discard());
        pending.forEach(f -> f.bullet.discard());
        active.clear();
        pending.clear();
    }
}
