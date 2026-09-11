package tacz.aos.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import tacz.aos.TaczArtOfSniping;

/** A measurement and its sampled sight line, never a client instruction to load terrain. */
public record RangePayload(ResourceLocation dimension, Vec3 eye, Vec3 direction, float distance, boolean windEnabled, float windX, float windZ) implements CustomPacketPayload {
    public RangePayload(ResourceLocation dimension, Vec3 eye, Vec3 direction, float distance) {
        this(dimension, eye, direction, distance, false, 0, 0);
    }
    public static final Type<RangePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(TaczArtOfSniping.MOD_ID, "range"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RangePayload> STREAM_CODEC = new StreamCodec<>() {
        public RangePayload decode(RegistryFriendlyByteBuf buf) {
            return new RangePayload(buf.readResourceLocation(), new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()),
                    new Vec3(buf.readFloat(), buf.readFloat(), buf.readFloat()), buf.readFloat(), buf.readBoolean(), buf.readFloat(), buf.readFloat());
        }
        public void encode(RegistryFriendlyByteBuf buf, RangePayload value) {
            buf.writeResourceLocation(value.dimension());
            buf.writeDouble(value.eye.x); buf.writeDouble(value.eye.y); buf.writeDouble(value.eye.z);
            buf.writeFloat((float) value.direction.x); buf.writeFloat((float) value.direction.y); buf.writeFloat((float) value.direction.z);
            buf.writeFloat(value.distance);
            buf.writeBoolean(value.windEnabled); buf.writeFloat(value.windX); buf.writeFloat(value.windZ);
        }
    };
    public RangePayload {
        if (!Double.isFinite(eye.x + eye.y + eye.z) || !Double.isFinite(direction.lengthSqr())
                || Math.abs(direction.lengthSqr() - 1) > .001 || !Float.isFinite(distance)
                || distance < -1 || distance > 16384 || !Float.isFinite(windX) || !Float.isFinite(windZ)
                || Math.hypot(windX, windZ) > 30.0001) throw new IllegalArgumentException("Invalid range sample");
    }
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
