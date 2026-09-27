package tacz.aos.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import java.util.function.BiConsumer;

import java.util.function.Consumer;

/** Client installation supplies the receiver; this class never links client-only Minecraft classes. */
public final class ScopeNetworking {
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("tacz_aos", "main"), () -> "forge-10", "forge-10"::equals, "forge-10"::equals);
    private static Consumer<ScopeOcclusionPayload> occlusionReceiver = ignored -> {};
    public static void setOcclusionReceiver(Consumer<ScopeOcclusionPayload> handler){occlusionReceiver=handler;}
    private static Consumer<ScopeTargetsPayload> receiver = ignored -> {};
    private static Consumer<ScopeBlockTargetsPayload> blockTargetReceiver = ignored -> {};
    public static void setBlockTargetReceiver(Consumer<ScopeBlockTargetsPayload> handler){blockTargetReceiver=handler;}
    private static Consumer<ShotTrailsPayload> trailReceiver = ignored -> {};
    private static Consumer<RangePayload> rangeReceiver = ignored -> {};
    public static void setRangeReceiver(Consumer<RangePayload> handler) { rangeReceiver = handler; }
    private static Consumer<NearMissPayload> nearMissReceiver = ignored -> {};
    private static Consumer<DistantGunshotPayload> distantGunshotReceiver = ignored -> {};
    public static void setDistantGunshotReceiver(Consumer<DistantGunshotPayload> handler) { distantGunshotReceiver = handler; }
    public static void setNearMissReceiver(Consumer<NearMissPayload> handler) { nearMissReceiver = handler; }
    private ScopeNetworking() {}
    public static void setClientReceiver(Consumer<ScopeTargetsPayload> handler) { receiver = handler; }
    public static void setTrailReceiver(Consumer<ShotTrailsPayload> handler) { trailReceiver = handler; }
    public static void register() {
        var registrar = new Registrar();
        registrar.playToClient(ScopeOcclusionPayload.TYPE,ScopeOcclusionPayload.STREAM_CODEC,(payload,context)->occlusionReceiver.accept(payload));
        registrar.playToClient(DistantGunshotPayload.TYPE, DistantGunshotPayload.STREAM_CODEC,
                (payload, context) -> distantGunshotReceiver.accept(payload));
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
    private static final class Registrar {
        private int nextId;
        <T extends ModPayload> void playToClient(ModPayload.Type<T> type, PayloadCodec<FriendlyByteBuf, T> codec,
                                                 BiConsumer<T, Context> handler) {
            register(type, codec, handler, NetworkDirection.PLAY_TO_CLIENT);
        }
        <T extends ModPayload> void playToServer(ModPayload.Type<T> type, PayloadCodec<FriendlyByteBuf, T> codec,
                                                 BiConsumer<T, Context> handler) {
            register(type, codec, handler, NetworkDirection.PLAY_TO_SERVER);
        }
        private <T extends ModPayload> void register(ModPayload.Type<T> type, PayloadCodec<FriendlyByteBuf, T> codec,
                                                     BiConsumer<T, Context> handler, NetworkDirection direction) {
            CHANNEL.messageBuilder(type.javaType(), nextId++, direction)
                    .encoder((message, buffer) -> codec.encode(buffer, message)).decoder(codec::decode)
                    .consumerMainThread((message, supplier) -> {
                        var context = supplier.get();
                        handler.accept(message, new Context(context));
                        context.setPacketHandled(true);
                    }).add();
        }
    }
    private record Context(NetworkEvent.Context context) {
        net.minecraft.world.entity.player.Player player() { return context.getSender(); }
    }
}
