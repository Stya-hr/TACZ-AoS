package tacz.aos.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import tacz.aos.TaczArtOfSniping;

/** One small, server-selected closest-approach notification; no client projectile entity required. */
public record NearMissPayload(ResourceLocation dimension, double x, double y, double z, float volume) implements CustomPacketPayload {
    public static final Type<NearMissPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(TaczArtOfSniping.MOD_ID, "near_miss"));
    public static final StreamCodec<RegistryFriendlyByteBuf, NearMissPayload> STREAM_CODEC = new StreamCodec<>() {
        public NearMissPayload decode(RegistryFriendlyByteBuf b) {
            return new NearMissPayload(b.readResourceLocation(), b.readDouble(), b.readDouble(), b.readDouble(), b.readFloat());
        }
        public void encode(RegistryFriendlyByteBuf b, NearMissPayload p) {
            b.writeResourceLocation(p.dimension); b.writeDouble(p.x); b.writeDouble(p.y); b.writeDouble(p.z); b.writeFloat(p.volume);
        }
    };
    public NearMissPayload {
        if (!Double.isFinite(x+y+z) || Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z))) > 30_000_000
                || !Float.isFinite(volume) || volume < 0 || volume > 1) throw new IllegalArgumentException("Invalid near miss");
    }
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
