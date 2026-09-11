package tacz.aos.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import tacz.aos.TaczArtOfSniping;

public record DistantGunshotPayload(ResourceLocation dimension, double x, double y, double z, float volume, float pitch)
        implements CustomPacketPayload {
    public static final Type<DistantGunshotPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(TaczArtOfSniping.MOD_ID, "distant_gunshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DistantGunshotPayload> STREAM_CODEC = new StreamCodec<>() {
        public DistantGunshotPayload decode(RegistryFriendlyByteBuf b) {
            return new DistantGunshotPayload(b.readResourceLocation(), b.readDouble(), b.readDouble(), b.readDouble(), b.readFloat(), b.readFloat());
        }
        public void encode(RegistryFriendlyByteBuf b, DistantGunshotPayload p) {
            b.writeResourceLocation(p.dimension); b.writeDouble(p.x); b.writeDouble(p.y); b.writeDouble(p.z); b.writeFloat(p.volume); b.writeFloat(p.pitch);
        }
    };
    public DistantGunshotPayload {
        if (!Double.isFinite(x+y+z) || Math.max(Math.abs(x),Math.max(Math.abs(y),Math.abs(z))) > 30_000_000
                || !Float.isFinite(volume) || volume < 0 || volume > 1 || !Float.isFinite(pitch) || pitch < .8f || pitch > 1.2f)
            throw new IllegalArgumentException("Invalid distant gunshot");
    }
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
