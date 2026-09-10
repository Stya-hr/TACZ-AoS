package tacz.longrangesniping.scoping;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import tacz.longrangesniping.ballistics.NoLoadCollisionSource;
import tacz.longrangesniping.ballistics.SegmentTrace;
import tacz.longrangesniping.compat.tacz.BallisticPreview;
import tacz.longrangesniping.config.ServerConfig;
import tacz.longrangesniping.network.RangePayload;

import java.util.List;

/** Shares subscription candidates and cadence. Own global 2ms soft budget, at most two lines per server tick. */
public final class ScopeRangefinder {
    private static long nanos;
    private static int queries;
    private ScopeRangefinder() {}
    public static void beginTick() { nanos = 0; queries = 0; }

    public static void update(ServerPlayer player, List<? extends Entity> visible) {
        if (queries >= 2 || nanos >= 2_000_000) return;
        queries++;
        long started = System.nanoTime();
        var settings = ServerConfig.current();
        Vec3 eye = player.getEyePosition(), direction = player.getViewVector(1).normalize();
        var boxes = visible.stream().filter(e -> e != player && !e.isRemoved() && e.isPickable())
                .limit(settings.maxTrackedEntities()).map(Entity::getBoundingBox).toList();
        float distance = measure(eye, direction, new NoLoadCollisionSource(player.serverLevel()), boxes,
                settings.maxDistance(), settings.scopeRendering().maxTraceCells(), started + 2_000_000 - nanos);
        nanos += System.nanoTime() - started;
        PacketDistributor.sendToPlayer(player, new RangePayload(player.serverLevel().dimension().location(), eye, direction, distance));
    }

    /** Zero gravity/drag here denotes a geometric straight ray, not guessed firearm parameters. */
    public static float measure(Vec3 eye, Vec3 direction, SegmentTrace.Source source, List<AABB> entities,
                                int range, int cells, long deadline) {
        var ray = new BallisticPreview(eye, direction.normalize().scale(128), 0, 0, 1);
        var result = ImpactPrediction.predict(ray, source, entities, range, (range + 127) / 128,
                cells, 32768, deadline);
        return result.target() == null ? -1 : (float) Math.min(range, eye.distanceTo(result.target()));
    }
}
