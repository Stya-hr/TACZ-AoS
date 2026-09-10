package tacz.longrangesniping.network;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import tacz.longrangesniping.TaczLongRangeSniping;
/** Advisory visible surface. The server validates eligibility, sight direction, generation and budgets. */
public record ScopeAimPayload(ResourceLocation dimension, Vec3 eye, Vec3 direction, double distance) implements CustomPacketPayload {
    public static final Type<ScopeAimPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(TaczLongRangeSniping.MOD_ID,"scope_aim"));
    public static final StreamCodec<RegistryFriendlyByteBuf,ScopeAimPayload> STREAM_CODEC=new StreamCodec<>() {
        public ScopeAimPayload decode(RegistryFriendlyByteBuf b) {return new ScopeAimPayload(b.readResourceLocation(),b.readVec3(),b.readVec3(),b.readDouble());}
        public void encode(RegistryFriendlyByteBuf b,ScopeAimPayload p) {b.writeResourceLocation(p.dimension);b.writeVec3(p.eye);b.writeVec3(p.direction);b.writeDouble(p.distance);}
    };
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
