package tacz.longrangesniping.ballistics;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.state.BlockState;
import tacz.longrangesniping.terrain.CollisionCell;
import tacz.longrangesniping.terrain.StaticCollisionShapes;
import java.util.*;
/** Per-query live geometry. Everything outside selected cores intentionally has no collision. */
public final class TargetCollisionSource implements SegmentTrace.Source {
    private final ServerLevel level;
    private final List<AABB> regions;
    private final net.minecraft.world.level.BlockGetter loaded = new net.minecraft.world.level.BlockGetter() {
        public BlockState getBlockState(BlockPos pos) {
            var chunk=level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);
            return chunk==null?net.minecraft.world.level.block.Blocks.AIR.defaultBlockState():chunk.getBlockState(pos);
        }
        public net.minecraft.world.level.material.FluidState getFluidState(BlockPos pos){return getBlockState(pos).getFluidState();}
        public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos pos){
            var chunk=level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);
            return chunk==null?null:chunk.getBlockEntity(pos,net.minecraft.world.level.chunk.LevelChunk.EntityCreationType.CHECK);
        }
        public int getHeight(){return level.getHeight();}
        public int getMinBuildHeight(){return level.getMinBuildHeight();}
    };
    private final IdentityHashMap<BlockState,CollisionCell> palette=new IdentityHashMap<>();
    public TargetCollisionSource(ServerLevel level,List<AABB> regions) { this.level=level;this.regions=List.copyOf(regions); }
    public boolean contains(Vec3 p) {return contains(p.x,p.y,p.z);}
    private boolean contains(double x,double y,double z) {
        for(var b:regions) if(x>=b.minX && x<b.maxX && y>=b.minY && y<b.maxY && z>=b.minZ && z<b.maxZ) return true;
        return false;
    }
    public boolean intersects(Vec3 a,Vec3 b) {return regions.stream().anyMatch(r->r.inflate(1).contains(a)||r.inflate(1).clip(a,b).isPresent());}
    public SegmentTrace.Result trace(Vec3 a,Vec3 move) {
        if(!intersects(a,a.add(move))) return new SegmentTrace.Result(SegmentTrace.End.CLEAR,1);
        return SegmentTrace.trace(this,a.x,a.y,a.z,move.x,move.y,move.z);
    }
    public CollisionCell cell(int x,int y,int z) {
        if(!contains(x+.5,y+.5,z+.5)) return CollisionCell.AIR;
        var chunk=level.getChunkSource().getChunkNow(x>>4,z>>4);
        if(chunk==null) return CollisionCell.AIR;
        var pos=new BlockPos(x,y,z);var state=chunk.getBlockState(pos);
        var cached=palette.computeIfAbsent(state,StaticCollisionShapes::classify);
        // Dynamic/modded shapes are queried only in the target, through a getter that cannot load neighbors.
        return cached.kind()==CollisionCell.Kind.CONSERVATIVE
            ? StaticCollisionShapes.fromShape(state.getCollisionShape(loaded,pos),state.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) : cached;
    }
}
