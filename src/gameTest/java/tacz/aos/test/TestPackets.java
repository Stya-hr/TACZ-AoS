package tacz.aos.test;

import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import tacz.aos.network.*;

/** Decode the actual Forge wire packet captured by the test connection. */
final class TestPackets {
    static Object decode(ClientboundCustomPayloadPacket packet) {
        var buffer = new net.minecraft.network.FriendlyByteBuf(packet.getData().duplicate());
        if (packet.getIdentifier().getNamespace().equals("tacz")) { if (buffer.readVarInt() == 8) return com.tacz.guns.network.message.ServerMessageSound.decode(buffer); return null; }
        if (!packet.getIdentifier().toString().equals("tacz_aos:main")) return null;
        return switch (buffer.readVarInt()) {
            case 0 -> DistantGunshotPayload.STREAM_CODEC.decode(buffer);
            case 1 -> ScopeBlockTargetsPayload.STREAM_CODEC.decode(buffer);
            case 2 -> NearMissPayload.STREAM_CODEC.decode(buffer);
            case 3 -> RangePayload.STREAM_CODEC.decode(buffer);
            case 4 -> ScopeTargetsPayload.STREAM_CODEC.decode(buffer);
            case 5 -> ShotTrailsPayload.STREAM_CODEC.decode(buffer);
            default -> null;
        };
    }
}
