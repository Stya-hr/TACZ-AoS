package tacz.aos.scoping;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import tacz.aos.network.PacketDelivery;

import tacz.aos.ballistics.SegmentTrace;
import tacz.aos.compat.tacz.BallisticPreview;
import tacz.aos.config.ServerConfig;
import tacz.aos.network.RangePayload;

import java.util.List;

/** Shares subscription candidates and cadence. Own global 2ms soft budget, at most two lines per server tick. */
public final class ScopeRangefinder {
    private static long nanos;
    private static int queries;
    private ScopeRangefinder() {}
    public static void beginTick() { nanos = 0; queries = 0; }

    public static void update(ServerPlayer player, List<? extends Entity> visible) {
        if (queries >= 2 || nanos >= 2_000_000 || !tacz.aos.compat.tacz.TaczBridge.evaluate(player).eligible()) return;
        queries++;
        long started = System.nanoTime();
        var settings = ServerConfig.current();
        Vec3 eye = player.getEyePosition(), direction = player.getViewVector(1).normalize();
        var boxes = visible.stream().filter(e -> e != player && !e.isRemoved() && e.isPickable())
                .limit(settings.maxTrackedEntities()).map(Entity::getBoundingBox).toList();
        Vec3 target=ScopeAim.target(player);
        float distance=target==null?-1:(float)eye.distanceTo(target);
        double closest=distance<0?settings.maxDistance():distance;
        Vec3 end=eye.add(direction.scale(closest));
        for(var box:boxes) {
            var hit=box.clip(eye,end);
            if(hit.isPresent() && eye.distanceTo(hit.get())<closest) {closest=eye.distanceTo(hit.get());distance=(float)closest;}
        }
        nanos += System.nanoTime() - started;
        var wind = tacz.aos.wind.WindField.current(player.serverLevel());
        var humidity = tacz.aos.humidity.HumidityField.current(player.serverLevel());
        PacketDelivery.sendToPlayer(player, new RangePayload(player.serverLevel().dimension().location(), eye, direction, distance,
                ServerConfig.wind().enabled(), (float) wind.x(), (float) wind.z(), humidity.enabled(), (float) humidity.percent()));
    }

    /** Zero gravity/drag here denotes a geometric straight ray, not guessed firearm parameters. */
    public static float measure(Vec3 eye, Vec3 direction, SegmentTrace.Source source, List<AABB> entities,
                                int range, int cells, long deadline) {
        var ray = new BallisticPreview(eye, direction.normalize().scale(128), 0, 0, 1);
        // A conservative safety stop is not a measured surface. Keep it opaque, but report
        // unknown instead of inventing a 0.0 / near-field distance at the edge of its halo.
        SegmentTrace.Source measurable = (x, y, z) -> {
            var cell = source.cell(x, y, z);
            return cell != null && cell.kind() == tacz.aos.terrain.CollisionCell.Kind.CONSERVATIVE ? null : cell;
        };
        var result = ImpactPrediction.predict(ray, measurable, entities, range, (range + 127) / 128,
                cells, 32768, deadline);
        return result.target() == null ? -1 : (float) Math.min(range, eye.distanceTo(result.target()));
    }
}
