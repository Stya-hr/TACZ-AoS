package tacz.aos.network;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

public final class PacketDelivery {
    private PacketDelivery() {}
    public static void sendToPlayer(ServerPlayer player, ModPayload message) {
        ScopeNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }
    public static void sendToServer(ModPayload message) {
        ScopeNetworking.CHANNEL.sendToServer(message);
    }
}
