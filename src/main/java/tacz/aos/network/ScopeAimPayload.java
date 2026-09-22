package tacz.aos.network;
import net.minecraft.network.FriendlyByteBuf;
import tacz.aos.network.PayloadCodec;
import tacz.aos.network.ModPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import tacz.aos.TaczArtOfSniping;
/** Advisory visible surface. The server validates eligibility, sight direction, generation and budgets. */
public record ScopeAimPayload(ResourceLocation dimension, Vec3 eye, Vec3 direction, double distance) implements ModPayload {
    public static final Type<ScopeAimPayload> TYPE=new Type<>(new ResourceLocation(TaczArtOfSniping.MOD_ID,"scope_aim"), ScopeAimPayload.class);
    public static final PayloadCodec<FriendlyByteBuf,ScopeAimPayload> STREAM_CODEC=new PayloadCodec<>() {
        public ScopeAimPayload decode(FriendlyByteBuf b) {return new ScopeAimPayload(b.readResourceLocation(),new Vec3(b.readDouble(),b.readDouble(),b.readDouble()),new Vec3(b.readDouble(),b.readDouble(),b.readDouble()),b.readDouble());}
        public void encode(FriendlyByteBuf b,ScopeAimPayload p) {b.writeResourceLocation(p.dimension);b.writeDouble(p.eye.x);b.writeDouble(p.eye.y);b.writeDouble(p.eye.z);b.writeDouble(p.direction.x);b.writeDouble(p.direction.y);b.writeDouble(p.direction.z);b.writeDouble(p.distance);}
    };
    public Type<? extends ModPayload> type(){return TYPE;}
}
