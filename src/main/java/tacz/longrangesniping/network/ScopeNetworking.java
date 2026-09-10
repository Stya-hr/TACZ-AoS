package tacz.longrangesniping.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.function.Consumer;

/** Client installation supplies the receiver; this class never links client-only Minecraft classes. */
public final class ScopeNetworking {
    private static Consumer<ScopeTargetsPayload> receiver = ignored -> {};
    private static Consumer<ShotTrailsPayload> trailReceiver = ignored -> {};
    private ScopeNetworking() {}
    public static void setClientReceiver(Consumer<ScopeTargetsPayload> handler) { receiver = handler; }
    public static void setTrailReceiver(Consumer<ShotTrailsPayload> handler) { trailReceiver = handler; }
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("2");
        registrar.playToClient(ScopeTargetsPayload.TYPE, ScopeTargetsPayload.STREAM_CODEC,
                (payload, context) -> receiver.accept(payload));
        registrar.playToClient(ShotTrailsPayload.TYPE, ShotTrailsPayload.STREAM_CODEC,
                (payload, context) -> trailReceiver.accept(payload));
    }
}
