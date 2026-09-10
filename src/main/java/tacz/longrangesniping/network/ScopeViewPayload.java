package tacz.longrangesniping.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import tacz.longrangesniping.TaczLongRangeSniping;

/** Visual projection only. Contains no target, position, rotation or loading command. */
public record ScopeViewPayload(ResourceLocation dimension, float zoom, float halfAngle) implements CustomPacketPayload {
    public static final Type<ScopeViewPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(TaczLongRangeSniping.MOD_ID, "scope_view"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ScopeViewPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override public ScopeViewPayload decode(RegistryFriendlyByteBuf buf) {
            return new ScopeViewPayload(buf.readResourceLocation(), buf.readFloat(), buf.readFloat());
        }
        @Override public void encode(RegistryFriendlyByteBuf buf, ScopeViewPayload payload) {
            buf.writeResourceLocation(payload.dimension()); buf.writeFloat(payload.zoom()); buf.writeFloat(payload.halfAngle());
        }
    };
    public ScopeViewPayload {
        if (!Float.isFinite(zoom) || zoom <= 0 || !Float.isFinite(halfAngle) || halfAngle <= 0 || halfAngle >= Math.PI / 2)
            throw new IllegalArgumentException("Invalid scope projection");
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
