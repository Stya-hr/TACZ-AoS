package tacz.aos.network;

import net.minecraft.network.FriendlyByteBuf;
import tacz.aos.network.PayloadCodec;
import tacz.aos.network.ModPayload;
import net.minecraft.resources.ResourceLocation;
import tacz.aos.TaczArtOfSniping;

/** Visual projection only. Contains no target, position, rotation or loading command. */
public record ScopeViewPayload(ResourceLocation dimension, float zoom, float halfAngle) implements ModPayload {
    public static final Type<ScopeViewPayload> TYPE = new Type<>(new ResourceLocation(TaczArtOfSniping.MOD_ID, "scope_view"), ScopeViewPayload.class);
    public static final PayloadCodec<FriendlyByteBuf, ScopeViewPayload> STREAM_CODEC = new PayloadCodec<>() {
        @Override public ScopeViewPayload decode(FriendlyByteBuf buf) {
            return new ScopeViewPayload(buf.readResourceLocation(), buf.readFloat(), buf.readFloat());
        }
        @Override public void encode(FriendlyByteBuf buf, ScopeViewPayload payload) {
            buf.writeResourceLocation(payload.dimension()); buf.writeFloat(payload.zoom()); buf.writeFloat(payload.halfAngle());
        }
    };
    public ScopeViewPayload {
        if (!Float.isFinite(zoom) || zoom <= 0 || !Float.isFinite(halfAngle) || halfAngle <= 0 || halfAngle >= Math.PI / 2)
            throw new IllegalArgumentException("Invalid scope projection");
    }
    @Override public Type<? extends ModPayload> type() { return TYPE; }
}
