package tacz.longrangesniping.command;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import tacz.longrangesniping.compat.tacz.TaczBridge;
import tacz.longrangesniping.config.ServerConfig;
import tacz.longrangesniping.ballistics.PrototypeBallistics;
import tacz.longrangesniping.terrain.TerrainSnapshots;
import tacz.longrangesniping.terrain.SectionKey;

public final class SnipingCommand {
    @SubscribeEvent
    public void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("taczlrs")
                .then(Commands.literal("status").executes(context -> {
                    var player = context.getSource().getPlayerOrException();
                    var decision = TaczBridge.evaluate(player);
                    context.getSource().sendSuccess(() -> Component.translatable("tacz_long_range_sniping.status",
                            Component.translatable("tacz_long_range_sniping.reason." + decision.reason().name().toLowerCase(java.util.Locale.ROOT)),
                            decision.gunId().isEmpty() ? "-" : decision.gunId(),
                            decision.scopeId().isEmpty() ? "-" : decision.scopeId(),
                            decision.slot() < 0 ? "-" : decision.slot() + 1,
                            decision.zoom() == 0 ? "-" : decision.zoom()), false);
                    var config = ServerConfig.current();
                    var status = PrototypeBallistics.INSTANCE.status(context.getSource().getLevel());
                    context.getSource().sendSuccess(() -> Component.translatable("tacz_long_range_sniping.prototype_status",
                            config.ballistics().enabled(), config.ballistics().maxDistance(), status.active(),
                            status.accepted(), status.unknown(), status.rangeLimited()), false);
                    context.getSource().sendSuccess(() -> Component.translatable("tacz_long_range_sniping.flight_status",
                            config.ballistics().extendLifetime(), config.ballistics().maxFlightTicks(),
                            status.lifetimeLimited(), status.blocked(), status.lastTermination(),
                            PrototypeBallistics.INSTANCE.lastAdmission(context.getSource().getLevel())), false);
                    context.getSource().sendSuccess(() -> Component.translatable("tacz_long_range_sniping.scope_status",
                            config.scopeRendering().enabled(),
                            tacz.longrangesniping.scoping.ScopeSubscriptions.INSTANCE.count(player),
                            config.maxTrackedEntities(), config.maxDistance(), config.scopeRendering().intervalTicks()), false);
                    var activity = tacz.longrangesniping.scoping.ScopeActivity.INSTANCE.status();
                    context.getSource().sendSuccess(() -> Component.translatable("tacz_long_range_sniping.activity_status",
                            config.activity().enabled(), activity.ready(), activity.regions(), activity.fullChunks(),
                            config.activity().maxFullChunks(), activity.dependencyChunks(), activity.unknown(),
                            activity.budgetDenied(), activity.timedOut(), activity.flightPins()), false);
                    context.getSource().sendSuccess(() -> Component.translatable("tacz_long_range_sniping.prediction_status",
                            tacz.longrangesniping.scoping.ScopeActivity.INSTANCE.predictionStatus(player.getUUID()),
                            config.prediction().intervalTicks(), config.prediction().maxMillisPerTick()), false);
                    return decision.eligible() ? 1 : 0;
                }))
                .then(Commands.literal("cache").requires(source -> source.hasPermission(2)).executes(context -> {
                    var source = context.getSource();
                    var position = net.minecraft.core.BlockPos.containing(source.getPosition());
                    var key = SectionKey.atBlock(TerrainSnapshots.dimension(source.getLevel()),
                            position.getX(), position.getY(), position.getZ());
                    var cache = TerrainSnapshots.INSTANCE;
                    var status = cache.status();
                    source.sendSuccess(() -> Component.translatable("tacz_long_range_sniping.cache_status",
                            ServerConfig.current().snapshots().enabled(), status.sections(), status.queued(),
                            String.format(java.util.Locale.ROOT, "%.2f", status.bytes() / 1048576.0),
                            status.evictions(), status.droppedRequests(), status.lastCells(), status.lastSections()), false);
                    var disk = cache.diskStatus();
                    source.sendSuccess(() -> Component.translatable("tacz_long_range_sniping.disk_status",
                            disk == null ? "off" : disk.failed() ? "failed" : disk.ready() ? "ready" : "indexing",
                            disk == null ? 0 : disk.indexed(), disk == null ? 0 : disk.diskBytes() / 1048576,
                            disk == null ? 0 : disk.queued(), disk == null ? 0 : disk.queueBytes() / 1048576,
                            disk == null ? 0 : disk.dropped(), disk == null ? 0 : disk.corrupt(),
                            cache.pinnedSections(), cache.pinnedBytes() / 1048576), false);
                    var snapshot = cache.find(key);
                    source.sendSuccess(() -> Component.translatable("tacz_long_range_sniping.cache_section",
                            key.dimension(), key.x(), key.y(), key.z(), cache.coverage(key).name(),
                            snapshot.map(s -> Long.toString(s.version())).orElse("-"),
                            snapshot.map(s -> Integer.toString(s.conservativeCells())).orElse("-")), false);
                    return 1;
                })));
    }
}
