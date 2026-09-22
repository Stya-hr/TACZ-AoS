package tacz.aos.network;

import net.minecraft.network.FriendlyByteBuf;
import tacz.aos.network.PayloadCodec;
import tacz.aos.network.ModPayload;
import net.minecraft.resources.ResourceLocation;
import tacz.aos.TaczArtOfSniping;

/** One small, server-selected closest-approach notification; no client projectile entity required. */
public record NearMissPayload(ResourceLocation dimension, double x, double y, double z, float volume) implements ModPayload {
    public static final Type<NearMissPayload> TYPE = new Type<>(new ResourceLocation(TaczArtOfSniping.MOD_ID, "near_miss"), NearMissPayload.class);
    public static final PayloadCodec<FriendlyByteBuf, NearMissPayload> STREAM_CODEC = new PayloadCodec<>() {
        public NearMissPayload decode(FriendlyByteBuf b) {
            return new NearMissPayload(b.readResourceLocation(), b.readDouble(), b.readDouble(), b.readDouble(), b.readFloat());
        }
        public void encode(FriendlyByteBuf b, NearMissPayload p) {
            b.writeResourceLocation(p.dimension); b.writeDouble(p.x); b.writeDouble(p.y); b.writeDouble(p.z); b.writeFloat(p.volume);
        }
    };
    public NearMissPayload {
        if (!Double.isFinite(x+y+z) || Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z))) > 30_000_000
                || !Float.isFinite(volume) || volume < 0 || volume > 1) throw new IllegalArgumentException("Invalid near miss");
    }
    public Type<? extends ModPayload> type() { return TYPE; }
}
