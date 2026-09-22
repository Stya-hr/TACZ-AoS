package tacz.aos.network;

import net.minecraft.network.FriendlyByteBuf;
import tacz.aos.network.PayloadCodec;
import tacz.aos.network.ModPayload;
import net.minecraft.resources.ResourceLocation;
import tacz.aos.TaczArtOfSniping;

public record DistantGunshotPayload(ResourceLocation dimension, double x, double y, double z, float volume, float pitch)
        implements ModPayload {
    public static final Type<DistantGunshotPayload> TYPE = new Type<>(new ResourceLocation(TaczArtOfSniping.MOD_ID, "distant_gunshot"), DistantGunshotPayload.class);
    public static final PayloadCodec<FriendlyByteBuf, DistantGunshotPayload> STREAM_CODEC = new PayloadCodec<>() {
        public DistantGunshotPayload decode(FriendlyByteBuf b) {
            return new DistantGunshotPayload(b.readResourceLocation(), b.readDouble(), b.readDouble(), b.readDouble(), b.readFloat(), b.readFloat());
        }
        public void encode(FriendlyByteBuf b, DistantGunshotPayload p) {
            b.writeResourceLocation(p.dimension); b.writeDouble(p.x); b.writeDouble(p.y); b.writeDouble(p.z); b.writeFloat(p.volume); b.writeFloat(p.pitch);
        }
    };
    public DistantGunshotPayload {
        if (!Double.isFinite(x+y+z) || Math.max(Math.abs(x),Math.max(Math.abs(y),Math.abs(z))) > 30_000_000
                || !Float.isFinite(volume) || volume < 0 || volume > 1 || !Float.isFinite(pitch) || pitch < .8f || pitch > 1.2f)
            throw new IllegalArgumentException("Invalid distant gunshot");
    }
    public Type<? extends ModPayload> type() { return TYPE; }
}
