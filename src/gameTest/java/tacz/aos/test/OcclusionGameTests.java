package tacz.aos.test;

import io.netty.buffer.Unpooled;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.*;
import net.minecraftforge.gametest.*;
import tacz.aos.network.ScopeOcclusionPayload;
import tacz.aos.occlusion.OccluderBox;
import tacz.aos.occlusion.OcclusionShapes;
import tacz.aos.scoping.ScopeOcclusion;

@GameTestHolder("tacz_aos") @PrefixGameTestTemplate(false)
public final class OcclusionGameTests {
    @GameTest(setupTicks=20, template="ballistics",batch="occlusion")
    public static void liveCoverTracksPlacementRemovalAndGlassWithoutChunkLoads(GameTestHelper h) {
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
        h.assertTrue(!ScopeOcclusion.collect(h.getLevel(),eye,target,12,new int[]{8192},32).isEmpty(),"Glass did not supply full-cell cover");
        for(int z=6;z<=10;z++)h.setBlock(new BlockPos(10,5,z),Blocks.AIR);
        h.assertTrue(ScopeOcclusion.collect(h.getLevel(),eye,target,12,new int[]{8192},32).isEmpty(),"Removed wall retained occlusion");
        var missing=new AABB(13000000,80,13000000,13000001,82,13000001);
        int[] budget={17};ScopeOcclusion.collect(h.getLevel(),new Vec3(12999980,82,13000000),missing,24,budget,4);
        h.assertTrue(budget[0]==0,"Cell scan was not bounded");
        h.assertTrue(h.getLevel().getChunkSource().getLoadedChunksCount()==loaded,"Occlusion requested a missing chunk");h.succeed();
    }
    @GameTest(setupTicks=20, template="ballistics",batch="occlusion")
    public static void vegetationUsesTextureChannelWhileSolidCoverAndLeavesUseBoxes(GameTestHelper h) {
        for(int x=2;x<=18;x++)for(int y=4;y<=10;y++)for(int z=4;z<=12;z++)h.setBlock(new BlockPos(x,y,z),Blocks.AIR);
        var eye=h.absoluteVec(new Vec3(4,6,8.5));
        var target=new AABB(h.absoluteVec(new Vec3(14,5,8)),h.absoluteVec(new Vec3(15,7,9)));
        var pos=new BlockPos(10,5,8);
        for(var block:List.of(Blocks.STONE,Blocks.OAK_LEAVES,Blocks.AZALEA_LEAVES,
                Blocks.OAK_DOOR,Blocks.OAK_TRAPDOOR,Blocks.OAK_FENCE,Blocks.IRON_BARS,Blocks.CHEST,
                Blocks.GLASS,Blocks.GLASS_PANE,Blocks.TINTED_GLASS,Blocks.RED_STAINED_GLASS,Blocks.BLUE_STAINED_GLASS_PANE,
                Blocks.ICE,Blocks.SLIME_BLOCK,Blocks.HONEY_BLOCK,Blocks.COCOA,Blocks.SPORE_BLOSSOM)) {
            h.setBlock(pos,block);
            var boxes=ScopeOcclusion.collect(h.getLevel(),eye,target,12,new int[]{8192},32);
            h.assertTrue(!boxes.isEmpty(),"Material was skipped: "+block);
            if(block==Blocks.OAK_LEAVES) {
                var a=h.absolutePos(pos);
                h.assertTrue(boxes.stream().anyMatch(b->b.minX()==a.getX() && b.maxX()==a.getX()+1 && b.minY()==a.getY() && b.maxY()==a.getY()+1),
                        "Leaf texture holes were preserved instead of opaque leaf geometry");
            }
        }
        for(var clear:List.of(Blocks.GRASS,Blocks.TALL_GRASS,Blocks.FERN,Blocks.LARGE_FERN,Blocks.WHEAT,
                Blocks.CARROTS,Blocks.POTATOES,Blocks.BEETROOTS,Blocks.NETHER_WART,Blocks.TORCHFLOWER_CROP,
                Blocks.PITCHER_CROP,Blocks.SUGAR_CANE,Blocks.SWEET_BERRY_BUSH,
                Blocks.DANDELION,Blocks.OAK_SAPLING,Blocks.VINE,Blocks.GLOW_LICHEN,Blocks.KELP,
                Blocks.HANGING_ROOTS,Blocks.WATER,Blocks.FIRE)) {
            // Test the state directly too: unsupported plants must not pass merely by breaking into air.
            h.assertTrue(OcclusionShapes.boxes(clear.defaultBlockState(),h.getLevel(),h.absolutePos(pos)).isEmpty(),
                    "Transparent material/vegetation became a solid selection-box wall: "+clear);
            h.setBlock(pos,clear);
            h.assertTrue(ScopeOcclusion.collect(h.getLevel(),eye,target,12,new int[]{8192},32).isEmpty(),"Excluded material was sent as cover: "+clear);
        }
        h.setBlock(pos,Blocks.AIR);
        h.assertTrue(ScopeOcclusion.collect(h.getLevel(),eye,target,12,new int[]{8192},32).isEmpty(),"Air became an occluder");
        h.succeed();
    }

    @GameTest(setupTicks=20, template="ballistics",batch="occlusion")
    public static void cropGrowthAndRemovalRefreshTexturedCoverWithoutLoadingChunks(GameTestHelper h) {
        for(int x=2;x<=18;x++)for(int y=4;y<=10;y++)for(int z=4;z<=12;z++)h.setBlock(new BlockPos(x,y,z),Blocks.AIR);
        var eye=h.absoluteVec(new Vec3(4,6,8.5));
        var target=new AABB(h.absoluteVec(new Vec3(14,5,8)),h.absoluteVec(new Vec3(15,7,9)));
        var pos=new BlockPos(10,5,8);var absolute=h.absolutePos(pos);
        h.setBlock(pos.below(),Blocks.FARMLAND);
        int loaded=h.getLevel().getChunkSource().getLoadedChunksCount();
        for(int age:List.of(0,7)) {
            h.setBlock(pos,Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,age));
            var geometry=ScopeOcclusion.collectGeometry(h.getLevel(),eye,target,12,new int[]{8192},32,64);
            h.assertTrue(geometry.plants().stream().anyMatch(p->p.pos().equals(absolute) && p.state().getValue(CropBlock.AGE)==age),
                    "Crop state/growth stage did not reach the textured channel");
            h.assertTrue(geometry.boxes().stream().noneMatch(b->b.minY()>=absolute.getY()),"Crop selection box blocked its transparent pixels");
        }
        h.setBlock(pos,Blocks.AIR);
        h.assertTrue(ScopeOcclusion.collectGeometry(h.getLevel(),eye,target,12,new int[]{8192},32,64).plants().isEmpty(),"Removed crop retained cover");
        h.assertTrue(!OcclusionShapes.textured(Blocks.OAK_LEAVES.defaultBlockState()),"Leaves were treated as alpha-cutout vegetation");
        h.assertTrue(OcclusionShapes.textured(Blocks.GRASS.defaultBlockState()),"Grass was excluded");
        h.assertTrue(h.getLevel().getChunkSource().getLoadedChunksCount()==loaded,"Plant occlusion loaded chunks");
        h.succeed();
    }

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

    @GameTest(setupTicks=20, template="ballistics",batch="occlusion")
    public static void scanBudgetPreservesLastCompletedWallSpan(GameTestHelper h) {
        for(int x=2;x<=18;x++)for(int y=4;y<=10;y++)for(int z=4;z<=12;z++)h.setBlock(new BlockPos(x,y,z),Blocks.AIR);
        var eye=h.absoluteVec(new Vec3(4,6,8.5));
        var target=new AABB(h.absoluteVec(new Vec3(14,5,8)),h.absoluteVec(new Vec3(15,7,9)));
        for(int x=8;x<=10;x++)h.setBlock(new BlockPos(x,5,8),Blocks.OAK_LEAVES);
        // This corridor has 15 X cells and 5 Z rows per Y layer. Stop just after X=10 in the leaf row.
        int[] budget={7*15+9};
        var boxes=ScopeOcclusion.collect(h.getLevel(),eye,target,12,budget,32);
        var p=h.absolutePos(new BlockPos(8,5,8));
        h.assertTrue(budget[0]==0 && boxes.stream().anyMatch(b->b.minX()==p.getX() && b.maxX()==p.getX()+3
                && b.minY()==p.getY() && b.minZ()==p.getZ()),"Budget exhaustion discarded already scanned leaf cells");
        h.succeed();
    }

    @GameTest(setupTicks=20, template="empty",batch="occlusion")
    public static void incompleteOffAxisRefreshKeepsCoverButConfirmedRemovalClearsIt(GameTestHelper h) {
        var dimension=h.getLevel().dimension().location();var uuid=UUID.randomUUID();
        var known=new ScopeOcclusionPayload.Target(42,uuid,new BlockPos(900,80,8),List.of(new OccluderBox(-30,0,-1,-29,3,1)));
        var state=new ScopeOcclusionPayload(dimension,List.of(known)).merge(Map.of());
        var skipped=new ScopeOcclusionPayload.Target(42,uuid,new BlockPos(901,80,8),List.of(),List.of(),false);
        var center=new ScopeOcclusionPayload.Target(43,UUID.randomUUID(),new BlockPos(900,80,10),List.of());
        // Repeated centre-first and edge-first packets must not change stationary world cover.
        for(int n=0;n<60;n++) {
            var packet=new ScopeOcclusionPayload(dimension,n%2==0?List.of(center,skipped):List.of(skipped,center));
            var wire=new FriendlyByteBuf(Unpooled.buffer());
            try {ScopeOcclusionPayload.STREAM_CODEC.encode(wire,packet);state=ScopeOcclusionPayload.STREAM_CODEC.decode(wire).merge(state);}
            finally {wire.release();}
            h.assertTrue(state.get(42).equals(known),"Skipped off-axis scan cleared or translated existing cover");
        }
        var cleared=new ScopeOcclusionPayload.Target(42,uuid,known.origin(),List.of());
        state=new ScopeOcclusionPayload(dimension,List.of(cleared)).merge(state);
        h.assertTrue(state.get(42).boxes().isEmpty() && !state.containsKey(43),"Confirmed removal or unsubscription kept stale cover");
        state=new ScopeOcclusionPayload(dimension,List.of(new ScopeOcclusionPayload.Target(42,UUID.randomUUID(),known.origin(),List.of(),List.of(),false))).merge(Map.of(42,known));
        h.assertTrue(state.get(42).boxes().isEmpty(),"Reused entity ID inherited another entity's occlusion");
        h.succeed();
    }

    @GameTest(setupTicks=20, template="ballistics",batch="occlusion")
    public static void unavailableCellsAndExhaustedBudgetsAreNotConfirmedEmptyCover(GameTestHelper h) {
        var missing=new AABB(13000000,80,13000000,13000001,82,13000001);
        int loaded=h.getLevel().getChunkSource().getLoadedChunksCount();
        var unknown=ScopeOcclusion.collectGeometry(h.getLevel(),new Vec3(12999980,82,13000000),missing,24,new int[]{8192},32,64);
        h.assertTrue(!unknown.complete(),"Missing chunks were reported as confirmed air");
        var target=new AABB(h.absoluteVec(new Vec3(14,5,8)),h.absoluteVec(new Vec3(15,7,9)));
        var eye=h.absoluteVec(new Vec3(4,6,8.5));
        var exhausted=ScopeOcclusion.collectGeometry(h.getLevel(),eye,target,12,new int[]{0},32,64);
        h.assertTrue(!exhausted.complete(),"Zero scan budget was reported as confirmed clear");
        for(int x=2;x<=18;x++)for(int y=3;y<=10;y++)for(int z=4;z<=12;z++)h.setBlock(new BlockPos(x,y,z),Blocks.AIR);
        var clear=ScopeOcclusion.collectGeometry(h.getLevel(),eye,target,12,new int[]{8192},32,64);
        h.assertTrue(clear.complete() && clear.boxes().isEmpty(),"Actual complete air scan could not clear stale geometry");
        h.assertTrue(loaded==h.getLevel().getChunkSource().getLoadedChunksCount(),"Coverage check loaded unknown terrain");
        h.succeed();
    }

    @GameTest(setupTicks=20, template="empty",batch="occlusion")
    public static void occlusionPacketRoundTripAndAllocationBounds(GameTestHelper h) {
        var payload=new ScopeOcclusionPayload(h.getLevel().dimension().location(),List.of(new ScopeOcclusionPayload.Target(42,UUID.randomUUID(),new BlockPos(900,80,8),
                List.of(new OccluderBox(-3,0,-1,-2,1,.5)),List.of(new ScopeOcclusionPayload.Textured(new BlockPos(-2,0,0),
                        Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,7))))));
        var b=new FriendlyByteBuf(Unpooled.buffer());
        try {
            ScopeOcclusionPayload.STREAM_CODEC.encode(b,payload);
            h.assertTrue(ScopeOcclusionPayload.STREAM_CODEC.decode(b).equals(payload),"Occlusion shape changed in transit");
            b.clear();b.writeResourceLocation(payload.dimension());b.writeVarInt(257);
            boolean bad=false;try{ScopeOcclusionPayload.STREAM_CODEC.decode(b);}catch(IllegalArgumentException e){bad=true;}
            h.assertTrue(bad,"Unbounded target allocation accepted");
            b.clear();b.writeResourceLocation(payload.dimension());b.writeVarInt(1);b.writeVarInt(1);b.writeUUID(UUID.randomUUID());b.writeBlockPos(BlockPos.ZERO);b.writeVarInt(33);
            bad=false;try{ScopeOcclusionPayload.STREAM_CODEC.decode(b);}catch(IllegalArgumentException e){bad=true;}
            h.assertTrue(bad,"Unbounded shape allocation accepted");
            b.clear();b.writeResourceLocation(payload.dimension());b.writeVarInt(1);b.writeVarInt(1);b.writeUUID(UUID.randomUUID());b.writeBlockPos(BlockPos.ZERO);b.writeVarInt(0);b.writeVarInt(65);
            bad=false;try{ScopeOcclusionPayload.STREAM_CODEC.decode(b);}catch(IllegalArgumentException e){bad=true;}
            h.assertTrue(bad,"Unbounded textured allocation accepted");
        } finally {b.release();}
        h.succeed();
    }
}
