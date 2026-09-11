package tacz.aos.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import tacz.aos.TaczArtOfSniping;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Bounded render leases and poses. Native tracking supplies spawn/type/metadata/equipment/removal packets. */
public record ScopeTargetsPayload(ResourceLocation dimension, int interval, List<Target> targets) implements CustomPacketPayload {
    public record Target(int id, UUID uuid, double x, double y, double z, float yaw, float pitch,
                         float headYaw, float bodyYaw, int light, boolean extra) {}
    public static final int MAX_TARGETS = 256;
    public static final Type<ScopeTargetsPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(TaczArtOfSniping.MOD_ID, "scope_targets"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ScopeTargetsPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override public ScopeTargetsPayload decode(RegistryFriendlyByteBuf buf) {
            var dimension = buf.readResourceLocation();
            int interval = buf.readVarInt(), size = buf.readVarInt();
            if (interval < 2 || interval > 20 || size < 0 || size > MAX_TARGETS) throw new IllegalArgumentException("Invalid scope lease bounds");
            var targets = new ArrayList<Target>(size);
            for (int i = 0; i < size; i++) {
                targets.add(new Target(buf.readVarInt(), buf.readUUID(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                        buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readInt(), buf.readBoolean()));
            }
            return new ScopeTargetsPayload(dimension, interval, targets);
        }
        @Override public void encode(RegistryFriendlyByteBuf buf, ScopeTargetsPayload payload) {
            buf.writeResourceLocation(payload.dimension);
            buf.writeVarInt(payload.interval);
            buf.writeVarInt(payload.targets.size());
            for (var target : payload.targets) {
                buf.writeVarInt(target.id); buf.writeUUID(target.uuid);
                buf.writeDouble(target.x); buf.writeDouble(target.y); buf.writeDouble(target.z);
                buf.writeFloat(target.yaw); buf.writeFloat(target.pitch); buf.writeFloat(target.headYaw); buf.writeFloat(target.bodyYaw);
                buf.writeInt(target.light); buf.writeBoolean(target.extra);
            }
        }
    };
    public ScopeTargetsPayload {
        targets = List.copyOf(targets);
        if (interval < 2 || interval > 20 || targets.size() > MAX_TARGETS) throw new IllegalArgumentException("Invalid scope lease bounds");
        for (var target : targets) {
            if (!Double.isFinite(target.x + target.y + target.z + target.yaw + target.pitch + target.headYaw + target.bodyYaw)
                    || Math.abs(target.x) > 30_000_000 || Math.abs(target.z) > 30_000_000) throw new IllegalArgumentException("Invalid scope pose");
        }
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
