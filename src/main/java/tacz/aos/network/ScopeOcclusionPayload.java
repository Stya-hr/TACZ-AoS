package tacz.aos.network;
import java.util.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import tacz.aos.occlusion.SectionMask;
/** Full bounded roster plus atomic changed sections. No normal terrain chunks are installed. */
public record ScopeOcclusionPayload(ResourceLocation dimension,List<Region> regions,List<Target> targets,List<Update> updates) implements ModPayload {
    public static final int MAX_REGIONS=128, MAX_UPDATES=8;
    public record Region(long key,long revision) {}
    public record Target(int id,UUID uuid,boolean admitted,List<Long> required) { public Target {required=List.copyOf(required);if(required.size()>MAX_REGIONS)throw new IllegalArgumentException();} }
    public record Update(long key,long revision,SectionMask mask) {}
    public ScopeOcclusionPayload {
        regions=List.copyOf(regions);targets=List.copyOf(targets);updates=List.copyOf(updates);
        if(regions.size()>MAX_REGIONS||targets.size()>256||updates.size()>MAX_UPDATES)throw new IllegalArgumentException("Occlusion packet limit");
        var keys=new HashMap<Long,Long>();for(var r:regions)if(r.revision<0||keys.put(r.key,r.revision)!=null)throw new IllegalArgumentException("Invalid region");
        for(var t:targets)if(!keys.keySet().containsAll(t.required))throw new IllegalArgumentException("Unknown required region");
        var changed=new HashSet<Long>();for(var u:updates)if(u.revision<=0||!Objects.equals(keys.get(u.key),u.revision)||!changed.add(u.key))throw new IllegalArgumentException("Invalid update");
    }
    public static final Type<ScopeOcclusionPayload> TYPE=new Type<>(new ResourceLocation("tacz_aos","scope_occlusion"),ScopeOcclusionPayload.class);
    private static int count(FriendlyByteBuf b,int max){int n=b.readVarInt();if(n<0||n>max)throw new IllegalArgumentException("Invalid count");return n;}
    public static final PayloadCodec<FriendlyByteBuf,ScopeOcclusionPayload> STREAM_CODEC=new PayloadCodec<>() {
        public ScopeOcclusionPayload decode(FriendlyByteBuf b) {
            var dim=b.readResourceLocation();var regions=new ArrayList<Region>();int n=count(b,MAX_REGIONS);
            for(int i=0;i<n;i++)regions.add(new Region(b.readLong(),b.readVarLong()));
            var targets=new ArrayList<Target>();n=count(b,256);
            for(int i=0;i<n;i++){int id=b.readVarInt();var uuid=b.readUUID();boolean admitted=b.readBoolean();int k=count(b,MAX_REGIONS);var required=new ArrayList<Long>();
                for(int j=0;j<k;j++){int index=count(b,regions.size()-1);required.add(regions.get(index).key);}
                targets.add(new Target(id,uuid,admitted,required));}
            var updates=new ArrayList<Update>();n=count(b,MAX_UPDATES);
            for(int i=0;i<n;i++){int index=count(b,regions.size()-1);var r=regions.get(index);var bits=new ArrayList<Long>();for(int j=0;j<64;j++)bits.add(b.readLong());
                int k=count(b,4096);var plants=new HashMap<Integer,Integer>();for(int j=0;j<k;j++){int cell=b.readUnsignedShort(),state=b.readVarInt();if(cell>=4096||plants.put(cell,state)!=null)throw new IllegalArgumentException("Invalid plant");}
                updates.add(new Update(r.key,r.revision,new SectionMask(bits,plants)));}
            return new ScopeOcclusionPayload(dim,regions,targets,updates);
        }
        public void encode(FriendlyByteBuf b,ScopeOcclusionPayload p) {
            b.writeResourceLocation(p.dimension);b.writeVarInt(p.regions.size());var indices=new HashMap<Long,Integer>();
            for(int i=0;i<p.regions.size();i++){var r=p.regions.get(i);indices.put(r.key,i);b.writeLong(r.key);b.writeVarLong(r.revision);}
            b.writeVarInt(p.targets.size());for(var t:p.targets){b.writeVarInt(t.id);b.writeUUID(t.uuid);b.writeBoolean(t.admitted);b.writeVarInt(t.required.size());for(long k:t.required)b.writeVarInt(indices.get(k));}
            b.writeVarInt(p.updates.size());for(var u:p.updates){b.writeVarInt(indices.get(u.key));for(long bits:u.mask.solid())b.writeLong(bits);b.writeVarInt(u.mask.plants().size());u.mask.plants().forEach((cell,state)->{b.writeShort(cell);b.writeVarInt(state);});}
        }
    };
    public Type<? extends ModPayload> type(){return TYPE;}
}
