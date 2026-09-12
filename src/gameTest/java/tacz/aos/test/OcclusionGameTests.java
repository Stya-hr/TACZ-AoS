package tacz.aos.test;

import io.netty.buffer.Unpooled;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.gametest.*;
import tacz.aos.network.ScopeOcclusionPayload;
import tacz.aos.occlusion.OccluderBox;
import tacz.aos.scoping.ScopeOcclusion;

@GameTestHolder("tacz_aos") @PrefixGameTestTemplate(false)
public final class OcclusionGameTests {
    @GameTest(template="ballistics",batch="occlusion")
    public static void liveCoverTracksPlacementRemovalAndTransparentBlocksWithoutChunkLoads(GameTestHelper h) {
        for(int x=2;x<=18;x++)for(int y=4;y<=10;y++)for(int z=4;z<=12;z++)h.setBlock(new BlockPos(x,y,z),Blocks.AIR);
        var eye=h.absoluteVec(new Vec3(4,7,8));
        var target=new AABB(h.absoluteVec(new Vec3(14,5,7.5)),h.absoluteVec(new Vec3(15,8,8.5)));
        int loaded=h.getLevel().getChunkSource().getLoadedChunksCount();
        h.assertTrue(ScopeOcclusion.collect(h.getLevel(),eye,target,12,new int[]{8192},32).isEmpty(),"Unexpected cover before placement");
        for(int z=6;z<=10;z++)h.setBlock(new BlockPos(10,5,z),Blocks.STONE);
        var wall=ScopeOcclusion.collect(h.getLevel(),eye,target,12,new int[]{8192},32);
        h.assertTrue(!wall.isEmpty(),"New half-height wall did not supply occlusion");
        double top=h.absoluteVec(new Vec3(0,6,0)).y;
        h.assertTrue(wall.stream().allMatch(b->b.maxY()<=top+.001),"Half wall became full-height cover");
        for(int z=6;z<=10;z++)h.setBlock(new BlockPos(10,5,z),Blocks.GLASS);
        h.assertTrue(ScopeOcclusion.collect(h.getLevel(),eye,target,12,new int[]{8192},32).isEmpty(),"Glass became opaque cover");
        for(int z=6;z<=10;z++)h.setBlock(new BlockPos(10,5,z),Blocks.AIR);
        h.assertTrue(ScopeOcclusion.collect(h.getLevel(),eye,target,12,new int[]{8192},32).isEmpty(),"Removed wall retained occlusion");
        var missing=new AABB(13000000,80,13000000,13000001,82,13000001);
        int[] budget={17};ScopeOcclusion.collect(h.getLevel(),new Vec3(12999980,82,13000000),missing,24,budget,4);
        h.assertTrue(budget[0]==0,"Cell scan was not bounded");
        h.assertTrue(h.getLevel().getChunkSource().getLoadedChunksCount()==loaded,"Occlusion requested a missing chunk");h.succeed();
    }
    @GameTest(template="empty",batch="occlusion")
    public static void occlusionPacketRoundTripAndAllocationBounds(GameTestHelper h) {
        var payload=new ScopeOcclusionPayload(h.getLevel().dimension().location(),List.of(new ScopeOcclusionPayload.Target(42,UUID.randomUUID(),new BlockPos(900,80,8),
                List.of(new OccluderBox(-3,0,-1,-2,1,.5)))));
        var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),h.getLevel().registryAccess());
        try {
            ScopeOcclusionPayload.STREAM_CODEC.encode(b,payload);
            h.assertTrue(ScopeOcclusionPayload.STREAM_CODEC.decode(b).equals(payload),"Occlusion shape changed in transit");
            b.clear();b.writeResourceLocation(payload.dimension());b.writeVarInt(257);
            boolean bad=false;try{ScopeOcclusionPayload.STREAM_CODEC.decode(b);}catch(IllegalArgumentException e){bad=true;}
            h.assertTrue(bad,"Unbounded target allocation accepted");
            b.clear();b.writeResourceLocation(payload.dimension());b.writeVarInt(1);b.writeVarInt(1);b.writeUUID(UUID.randomUUID());b.writeBlockPos(BlockPos.ZERO);b.writeVarInt(33);
            bad=false;try{ScopeOcclusionPayload.STREAM_CODEC.decode(b);}catch(IllegalArgumentException e){bad=true;}
            h.assertTrue(bad,"Unbounded shape allocation accepted");
        } finally {b.release();}
        h.succeed();
    }
}
