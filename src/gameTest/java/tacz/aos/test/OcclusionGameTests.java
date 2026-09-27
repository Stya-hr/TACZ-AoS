package tacz.aos.test;
import io.netty.buffer.Unpooled;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.*;
import net.minecraftforge.gametest.*;
import tacz.aos.network.ScopeOcclusionPayload;
import tacz.aos.occlusion.*;
import tacz.aos.scoping.ScopeOcclusion;
@GameTestHolder("tacz_aos") @PrefixGameTestTemplate(false)
public final class OcclusionGameTests {
    @GameTest(setupTicks=20, template="empty",batch="occlusion")
    public static void nonPlantShapesAlwaysCoverExactlyOneCell(GameTestHelper h) {
        var full=List.of(new AABB(0,0,0,1,1,1));
        for(var block:List.of(Blocks.STONE_SLAB,Blocks.STONE_STAIRS,Blocks.OAK_DOOR,Blocks.OAK_TRAPDOOR,
                Blocks.OAK_FENCE,Blocks.CAMPFIRE,Blocks.CHEST,Blocks.OAK_LEAVES,Blocks.GLASS,Blocks.GLASS_PANE,
                Blocks.COCOA,Blocks.AZALEA,Blocks.LILY_PAD)) {
            for(var state:block.getStateDefinition().getPossibleStates())
                h.assertTrue(OcclusionShapes.boxes(state,h.getLevel(),h.absolutePos(BlockPos.ZERO)).equals(full),
                        "Non-plant cell kept shape holes or protrusions: "+state);
        }
        h.succeed();
    }


    @GameTest(setupTicks=20,template="ballistics",batch="occlusion")
    public static void sectionScanResumesAndPublishesOnlyCompleteVersions(GameTestHelper h) {
        var p=h.absolutePos(new BlockPos(8,5,8));h.getLevel().setBlockAndUpdate(p,Blocks.OAK_LOG.defaultBlockState());
        var job=new ScopeOcclusion.Job(SectionPos.of(p).asLong());
        h.assertTrue(job.scan(h.getLevel(),17,0,1)==17&&job.progress()==17&&job.mask==null,"Partial scan was published");
        int work=17;while(job.mask==null)work+=job.scan(h.getLevel(),97,0,1);
        h.assertTrue(work==4096&&job.mask.occupied(p.getX()&15,p.getY()&15,p.getZ()&15),"Scan did not resume to full coverage");
        var original=job.mask;long revision=job.revision;
        h.getLevel().setBlockAndUpdate(p,Blocks.AIR.defaultBlockState());
        job.scan(h.getLevel(),17,1,1);
        h.assertTrue(job.mask==original&&job.revision==revision,"Refresh discarded complete old mask");
        while(job.progress()!=0)job.scan(h.getLevel(),97,1,1);
        h.assertTrue(job.revision==revision+1&&!job.mask.occupied(p.getX()&15,p.getY()&15,p.getZ()&15),"Removal was not published atomically");
        job.scan(h.getLevel(),4096,2,1);h.assertTrue(job.revision==revision+1,"Unchanged section retransmitted a new version");h.succeed();
    }
    @GameTest(setupTicks=20,template="ballistics",batch="occlusion")
    public static void unknownCoverageNeverLoadsChunksOrPublishesAir(GameTestHelper h) {
        int loaded=h.getLevel().getChunkSource().getLoadedChunksCount();
        var job=new ScopeOcclusion.Job(SectionPos.asLong(800000,5,800000));
        h.assertTrue(job.scan(h.getLevel(),4096,0,1)==0&&job.mask==null,"Unknown section became confirmed air");
        h.assertTrue(loaded==h.getLevel().getChunkSource().getLoadedChunksCount(),"Occlusion loaded terrain");h.succeed();
    }
    @GameTest(setupTicks=20,template="ballistics",batch="occlusion")
    public static void materialChannelsAndCropGrowth(GameTestHelper h) {
        var p=h.absolutePos(new BlockPos(8,5,8));var job=new ScopeOcclusion.Job(SectionPos.of(p).asLong());
        int index=(p.getX()&15)+16*(p.getZ()&15)+256*(p.getY()&15),time=0;
        for(var block:List.of(Blocks.OAK_LEAVES,Blocks.GLASS,Blocks.STONE_SLAB,Blocks.OAK_STAIRS,Blocks.OAK_DOOR)) {
            h.getLevel().setBlock(p,block.defaultBlockState(),2);job.scan(h.getLevel(),4096,time++,1);
            h.assertTrue(job.mask.occupied(p.getX()&15,p.getY()&15,p.getZ()&15),"Full-cell policy missing "+block);
        }
        h.getLevel().setBlock(p.below(),Blocks.FARMLAND.defaultBlockState(),2);
        for(int age:List.of(0,7)) {
            var state=Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,age);h.getLevel().setBlock(p,state,2);job.scan(h.getLevel(),4096,time++,1);
            h.assertTrue(Objects.equals(job.mask.plants().get(index),Block.getId(state))&&!job.mask.occupied(p.getX()&15,p.getY()&15,p.getZ()&15),"Crop state lost or treated as cube");
        }
        h.getLevel().setBlock(p,Blocks.AIR.defaultBlockState(),2);job.scan(h.getLevel(),4096,time,1);
        h.assertTrue(!job.mask.plants().containsKey(index),"Removed plant retained cover");h.succeed();
    }
    @GameTest(setupTicks=20,template="empty",batch="occlusion")
    public static void sharedSectionPacketRoundTripAndBounds(GameTestHelper h) {
        long key=SectionPos.asLong(55,5,0);var mask=new SectionMask(Collections.nCopies(64,0L),Map.of(15,Block.getId(Blocks.GRASS.defaultBlockState())));
        var payload=new ScopeOcclusionPayload(h.getLevel().dimension().location(),List.of(new ScopeOcclusionPayload.Region(key,1)),
            List.of(new ScopeOcclusionPayload.Target(42,UUID.randomUUID(),true,List.of(key)),new ScopeOcclusionPayload.Target(43,UUID.randomUUID(),true,List.of(key))),
            List.of(new ScopeOcclusionPayload.Update(key,1,mask)));
        var wire=new FriendlyByteBuf(Unpooled.buffer());try {
            ScopeOcclusionPayload.STREAM_CODEC.encode(wire,payload);h.assertTrue(ScopeOcclusionPayload.STREAM_CODEC.decode(wire).equals(payload),"Section packet changed in transit");
            wire.clear();wire.writeResourceLocation(payload.dimension());wire.writeVarInt(129);boolean rejected=false;
            try{ScopeOcclusionPayload.STREAM_CODEC.decode(wire);}catch(IllegalArgumentException e){rejected=true;}
            h.assertTrue(rejected,"Unbounded region allocation accepted");
        } finally {wire.release();}h.succeed();
    }
}
