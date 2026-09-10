package tacz.longrangesniping.command;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import tacz.longrangesniping.compat.tacz.TaczBridge;
import tacz.longrangesniping.config.ServerConfig;
import tacz.longrangesniping.ballistics.ScopedBallistics;



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
                    var status = ScopedBallistics.INSTANCE.status(context.getSource().getLevel());
                    context.getSource().sendSuccess(() -> Component.translatable("tacz_long_range_sniping.prototype_status",
                            config.ballistics().enabled(), config.ballistics().maxDistance(), status.active(),
                            status.accepted(), status.rangeLimited()), false);
                    context.getSource().sendSuccess(() -> Component.translatable("tacz_long_range_sniping.flight_status",
                            config.ballistics().extendLifetime(), config.ballistics().maxFlightTicks(),
                            status.lifetimeLimited(), status.blocked(), status.lastTermination(),
                            ScopedBallistics.INSTANCE.lastAdmission(context.getSource().getLevel())), false);
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
                .then(Commands.literal("generation").requires(source -> source.hasPermission(2)).executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal(tacz.longrangesniping.terrain.GeneratedChunks.INSTANCE.status()),false);
                    return 1;
                })));
    }
}
