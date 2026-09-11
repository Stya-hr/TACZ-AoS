package tacz.aos.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.function.Consumer;

/** Client installation supplies the receiver; this class never links client-only Minecraft classes. */
public final class ScopeNetworking {
    private static Consumer<ScopeTargetsPayload> receiver = ignored -> {};
    private static Consumer<ScopeBlockTargetsPayload> blockTargetReceiver = ignored -> {};
    public static void setBlockTargetReceiver(Consumer<ScopeBlockTargetsPayload> handler){blockTargetReceiver=handler;}
    private static Consumer<ShotTrailsPayload> trailReceiver = ignored -> {};
    private static Consumer<RangePayload> rangeReceiver = ignored -> {};
    public static void setRangeReceiver(Consumer<RangePayload> handler) { rangeReceiver = handler; }
    private static Consumer<NearMissPayload> nearMissReceiver = ignored -> {};
    public static void setNearMissReceiver(Consumer<NearMissPayload> handler) { nearMissReceiver = handler; }
    private ScopeNetworking() {}
    public static void setClientReceiver(Consumer<ScopeTargetsPayload> handler) { receiver = handler; }
    public static void setTrailReceiver(Consumer<ShotTrailsPayload> handler) { trailReceiver = handler; }
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("10");
        registrar.playToClient(ScopeBlockTargetsPayload.TYPE, ScopeBlockTargetsPayload.STREAM_CODEC,
                (payload,context)->blockTargetReceiver.accept(payload));
        registrar.playToClient(NearMissPayload.TYPE, NearMissPayload.STREAM_CODEC,
                (payload, context) -> nearMissReceiver.accept(payload));
        registrar.playToClient(RangePayload.TYPE, RangePayload.STREAM_CODEC,
                (payload, context) -> rangeReceiver.accept(payload));
        registrar.playToClient(ScopeTargetsPayload.TYPE, ScopeTargetsPayload.STREAM_CODEC,
                (payload, context) -> receiver.accept(payload));
        registrar.playToClient(ShotTrailsPayload.TYPE, ShotTrailsPayload.STREAM_CODEC,
                (payload, context) -> trailReceiver.accept(payload));
        registrar.playToServer(ScopeAimPayload.TYPE,ScopeAimPayload.STREAM_CODEC,(payload,context)-> {
            if(context.player() instanceof net.minecraft.server.level.ServerPlayer player)
                tacz.aos.scoping.ScopeAim.receive(player,payload);
        });
        registrar.playToServer(ScopeEntityRepairPayload.TYPE, ScopeEntityRepairPayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof net.minecraft.server.level.ServerPlayer player)
                        tacz.aos.scoping.ScopeSubscriptions.INSTANCE.repair(player, payload);
                });
        registrar.playToServer(ScopeViewPayload.TYPE, ScopeViewPayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof net.minecraft.server.level.ServerPlayer player)
                        tacz.aos.scoping.ScopeViews.INSTANCE.receive(player, payload);
                });
    }
}
