package tacz.aos.network;

import net.minecraft.network.FriendlyByteBuf;
import tacz.aos.network.PayloadCodec;
import tacz.aos.network.ModPayload;
import net.minecraft.resources.ResourceLocation;
import tacz.aos.TaczArtOfSniping;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Request pairing data only for missing entities in an existing server-authorized observation. */
public record ScopeEntityRepairPayload(ResourceLocation dimension, List<Target> targets) implements ModPayload {
    public static final int MAX_TARGETS = 8, INTERVAL_TICKS = 20, GRACE_TICKS = 8;
    public record Target(int id, UUID uuid) {}
    public static final Type<ScopeEntityRepairPayload> TYPE = new Type<>(new ResourceLocation(TaczArtOfSniping.MOD_ID,"scope_entity_repair"), ScopeEntityRepairPayload.class);
    public static final PayloadCodec<FriendlyByteBuf, ScopeEntityRepairPayload> STREAM_CODEC = new PayloadCodec<>() {
        public ScopeEntityRepairPayload decode(FriendlyByteBuf b) {
            var dimension = b.readResourceLocation(); int size = b.readVarInt();
            if (size < 1 || size > MAX_TARGETS) throw new IllegalArgumentException("Invalid repair request size");
            var targets = new ArrayList<Target>(size);
            for (int i=0;i<size;i++) targets.add(new Target(b.readVarInt(),b.readUUID()));
            return new ScopeEntityRepairPayload(dimension,targets);
        }
        public void encode(FriendlyByteBuf b, ScopeEntityRepairPayload p) {
            b.writeResourceLocation(p.dimension); b.writeVarInt(p.targets.size());
            for (var t:p.targets) { b.writeVarInt(t.id); b.writeUUID(t.uuid); }
        }
    };
    public ScopeEntityRepairPayload {
        targets = List.copyOf(targets);
        if (targets.isEmpty() || targets.size()>MAX_TARGETS) throw new IllegalArgumentException("Invalid repair request size");
    }
    public Type<? extends ModPayload> type() { return TYPE; }
}
