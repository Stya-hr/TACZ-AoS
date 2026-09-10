package tacz.longrangesniping.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.function.Consumer;

/** Client installation supplies the receiver; this class never links client-only Minecraft classes. */
public final class ScopeNetworking {
    private static Consumer<ScopeTargetsPayload> receiver = ignored -> {};
    private ScopeNetworking() {}
    public static void setClientReceiver(Consumer<ScopeTargetsPayload> handler) { receiver = handler; }
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(ScopeTargetsPayload.TYPE, ScopeTargetsPayload.STREAM_CODEC,
                (payload, context) -> receiver.accept(payload));
    }
}
