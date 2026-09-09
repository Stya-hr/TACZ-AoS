package tacz.longrangesniping.command;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import tacz.longrangesniping.compat.tacz.TaczBridge;
import tacz.longrangesniping.config.ServerConfig;

public final class SnipingCommand {
    @SubscribeEvent
    public void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("taczlrs")
                .then(Commands.literal("status").executes(context -> {
                    var decision = TaczBridge.evaluate(context.getSource().getPlayerOrException());
                    context.getSource().sendSuccess(() -> Component.translatable("tacz_long_range_sniping.status",
                            Component.translatable("tacz_long_range_sniping.reason." + decision.reason().name().toLowerCase(java.util.Locale.ROOT)),
                            decision.gunId().isEmpty() ? "-" : decision.gunId(),
                            decision.scopeId().isEmpty() ? "-" : decision.scopeId(),
                            decision.slot() < 0 ? "-" : decision.slot() + 1,
                            decision.zoom() == 0 ? "-" : decision.zoom()), false);
                    context.getSource().sendSuccess(() -> Component.translatable("tacz_long_range_sniping.stage_one",
                            ServerConfig.current().maxDistance()), false);
                    return decision.eligible() ? 1 : 0;
                })));
    }
}
