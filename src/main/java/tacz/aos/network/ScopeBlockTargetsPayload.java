package tacz.aos.network;

import com.tacz.guns.block.TargetBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.network.FriendlyByteBuf;
import tacz.aos.network.PayloadCodec;
import tacz.aos.network.ModPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import tacz.aos.TaczArtOfSniping;
import java.util.*;

/** Render-only lower halves. Never installs blocks or chunks in the client's world. */
public record ScopeBlockTargetsPayload(ResourceLocation dimension, List<Target> targets) implements ModPayload {
    public static final int MAX_TARGETS = 64, MAX_TAG_BYTES = 8192, MAX_TOTAL_TAG_BYTES = 32768;
    public record Target(BlockPos pos, BlockState state, int light, CompoundTag data) {
        public Target { pos=pos.immutable(); data=data.copy(); }
    }
    public static final Type<ScopeBlockTargetsPayload> TYPE = new Type<>(new ResourceLocation(TaczArtOfSniping.MOD_ID,"scope_block_targets"), ScopeBlockTargetsPayload.class);
    public static final PayloadCodec<FriendlyByteBuf,ScopeBlockTargetsPayload> STREAM_CODEC = new PayloadCodec<>() {
        public ScopeBlockTargetsPayload decode(FriendlyByteBuf b) {
            var dim=b.readResourceLocation(); int count=b.readVarInt();
            if(count<0 || count>MAX_TARGETS) throw new IllegalArgumentException("Too many block targets");
            var result=new ArrayList<Target>(); long total=0;
            for(int i=0;i<count;i++) {
                var pos=b.readBlockPos();var state=Block.stateById(b.readVarInt());int light=b.readInt();
                var data=b.readNbt(new NbtAccounter(MAX_TAG_BYTES));
                if(data == null || (total+=data.sizeInBytes())>MAX_TOTAL_TAG_BYTES)
                    throw new IllegalArgumentException("Invalid block target metadata");
                result.add(new Target(pos,state,light,data));
            }
            return new ScopeBlockTargetsPayload(dim,result);
        }
        public void encode(FriendlyByteBuf b,ScopeBlockTargetsPayload p) {
            b.writeResourceLocation(p.dimension);b.writeVarInt(p.targets.size());
            for(var t:p.targets) {b.writeBlockPos(t.pos);b.writeVarInt(Block.getId(t.state));b.writeInt(t.light);b.writeNbt(t.data);}
        }
    };
    public ScopeBlockTargetsPayload {
        targets=List.copyOf(targets);long total=0;
        if(targets.size()>MAX_TARGETS) throw new IllegalArgumentException("Too many block targets");
        for(var t:targets) {
            if(!(t.state.getBlock() instanceof TargetBlock) || t.state.getValue(TargetBlock.HALF)!=DoubleBlockHalf.LOWER
                    || Math.abs((long)t.pos.getX())>30_000_000 || Math.abs((long)t.pos.getZ())>30_000_000
                    || t.data.sizeInBytes()>MAX_TAG_BYTES || (total+=t.data.sizeInBytes())>MAX_TOTAL_TAG_BYTES)
                throw new IllegalArgumentException("Invalid block target");
        }
    }
    public Type<? extends ModPayload> type(){return TYPE;}
}
