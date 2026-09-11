package tacz.aos.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import tacz.aos.TaczArtOfSniping;
import tacz.aos.trails.TrailSegment;

import java.util.ArrayList;
import java.util.List;

/** Bounded batches per recipient/dimension/tick, separately for full and locally clipped traces. No chunk data or entity spawning. */
public record ShotTrailsPayload(ResourceLocation dimension, boolean localOnly, List<TrailSegment> segments) implements CustomPacketPayload {
    public static final int MAX_SEGMENTS = 128;
    public static final Type<ShotTrailsPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(TaczArtOfSniping.MOD_ID, "shot_trails"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ShotTrailsPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override public ShotTrailsPayload decode(RegistryFriendlyByteBuf buf) {
            var dimension = buf.readResourceLocation();
            boolean localOnly = buf.readBoolean();
            int size = buf.readVarInt();
            if (size < 0 || size > MAX_SEGMENTS) throw new IllegalArgumentException("Invalid trail batch size");
            var segments = new ArrayList<TrailSegment>(size);
            for (int i = 0; i < size; i++) segments.add(new TrailSegment(buf.readVarInt(), buf.readVarInt(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readFloat(), buf.readBoolean()));
            return new ShotTrailsPayload(dimension, localOnly, segments);
        }
        @Override public void encode(RegistryFriendlyByteBuf buf, ShotTrailsPayload payload) {
            buf.writeResourceLocation(payload.dimension()); buf.writeBoolean(payload.localOnly()); buf.writeVarInt(payload.segments().size());
            for (var s : payload.segments()) {
                buf.writeVarInt(s.shot()); buf.writeVarInt(s.step());
                buf.writeDouble(s.x0()); buf.writeDouble(s.y0()); buf.writeDouble(s.z0());
                buf.writeDouble(s.x1()); buf.writeDouble(s.y1()); buf.writeDouble(s.z1());
                buf.writeFloat(s.speed()); buf.writeBoolean(s.terminal());
            }
        }
    };
    public ShotTrailsPayload(ResourceLocation dimension, List<TrailSegment> segments) { this(dimension, false, segments); }
    public ShotTrailsPayload {
        segments = List.copyOf(segments);
        if (segments.size() > MAX_SEGMENTS) throw new IllegalArgumentException("Invalid trail batch size");
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
