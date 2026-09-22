package tacz.aos.network;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import tacz.aos.network.PayloadCodec;
import tacz.aos.network.ModPayload;
import net.minecraft.resources.ResourceLocation;
import tacz.aos.occlusion.OccluderBox;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import tacz.aos.occlusion.OcclusionShapes;

/** Bounded replacement of short-lived visual occluders; never inserts client chunks. */
public record ScopeOcclusionPayload(ResourceLocation dimension,List<Target> targets) implements ModPayload {
    public static final int MAX_BOXES=256, MAX_PER_TARGET=32, MAX_TEXTURED=256, MAX_TEXTURED_PER_TARGET=64;
    public record Textured(BlockPos offset, BlockState state) {
        public Textured {
            offset=offset.immutable();
            if(Math.abs((long)offset.getX())>64 || Math.abs((long)offset.getY())>64 || Math.abs((long)offset.getZ())>64
                    || !OcclusionShapes.textured(state)) throw new IllegalArgumentException("Invalid textured occluder");
        }
    }
    public record Target(int id,UUID uuid,BlockPos origin,List<OccluderBox> boxes,List<Textured> textured) {
        public Target(int id,UUID uuid,BlockPos origin,List<OccluderBox> boxes) { this(id,uuid,origin,boxes,List.of()); }
        public Target {
            origin=origin.immutable();boxes=List.copyOf(boxes);textured=List.copyOf(textured);
            if(textured.size()>MAX_TEXTURED_PER_TARGET)throw new IllegalArgumentException("Too many textured occluders");
            if(boxes.size()>MAX_PER_TARGET)throw new IllegalArgumentException("Too many target occluders");
            for(var b:boxes)if(Math.max(Math.abs(b.minX()),Math.abs(b.maxX()))>64 || Math.max(Math.abs(b.minY()),Math.abs(b.maxY()))>64
                    || Math.max(Math.abs(b.minZ()),Math.abs(b.maxZ()))>64)throw new IllegalArgumentException("Occluder outside local region");
        }
    }
    public static final Type<ScopeOcclusionPayload> TYPE=new Type<>(new ResourceLocation("tacz_aos","scope_occlusion"), ScopeOcclusionPayload.class);
    public ScopeOcclusionPayload {
        targets=List.copyOf(targets);
        if(targets.size()>256 || targets.stream().mapToInt(t->t.boxes.size()).sum()>MAX_BOXES
                || targets.stream().mapToInt(t->t.textured.size()).sum()>MAX_TEXTURED)throw new IllegalArgumentException("Occlusion budget exceeded");
    }
    public static final PayloadCodec<FriendlyByteBuf,ScopeOcclusionPayload> STREAM_CODEC=new PayloadCodec<>() {
        public ScopeOcclusionPayload decode(FriendlyByteBuf b) {
            var dim=b.readResourceLocation();int n=b.readVarInt(),total=0,totalTextured=0;if(n<0 || n>256)throw new IllegalArgumentException("Invalid count");
            var targets=new ArrayList<Target>();
            for(int i=0;i<n;i++) {
                int id=b.readVarInt();var uuid=b.readUUID();var origin=b.readBlockPos();int count=b.readVarInt();total+=count;
                if(count<0 || count>MAX_PER_TARGET || total>MAX_BOXES)throw new IllegalArgumentException("Invalid box count");
                var boxes=new ArrayList<OccluderBox>();
                for(int j=0;j<count;j++)boxes.add(new OccluderBox(b.readFloat(),b.readFloat(),b.readFloat(),b.readFloat(),b.readFloat(),b.readFloat()));
                int plants=b.readVarInt();totalTextured+=plants;
                if(plants<0 || plants>MAX_TEXTURED_PER_TARGET || totalTextured>MAX_TEXTURED)throw new IllegalArgumentException("Invalid textured count");
                var textured=new ArrayList<Textured>();
                for(int j=0;j<plants;j++)textured.add(new Textured(b.readBlockPos(),Block.stateById(b.readVarInt())));
                targets.add(new Target(id,uuid,origin,boxes,textured));
            }
            return new ScopeOcclusionPayload(dim,targets);
        }
        public void encode(FriendlyByteBuf b,ScopeOcclusionPayload p) {
            b.writeResourceLocation(p.dimension);b.writeVarInt(p.targets.size());
            for(var t:p.targets){b.writeVarInt(t.id);b.writeUUID(t.uuid);b.writeBlockPos(t.origin);b.writeVarInt(t.boxes.size());
                for(var a:t.boxes){b.writeFloat((float)a.minX());b.writeFloat((float)a.minY());b.writeFloat((float)a.minZ());b.writeFloat((float)a.maxX());b.writeFloat((float)a.maxY());b.writeFloat((float)a.maxZ());}
                b.writeVarInt(t.textured.size());for(var plant:t.textured){b.writeBlockPos(plant.offset);b.writeVarInt(Block.getId(plant.state));}}
        }
    };
    public Type<? extends ModPayload> type(){return TYPE;}
}
