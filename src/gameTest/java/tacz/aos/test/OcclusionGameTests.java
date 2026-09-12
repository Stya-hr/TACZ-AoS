package tacz.aos.test;

import io.netty.buffer.Unpooled;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.gametest.*;
import tacz.aos.network.ScopeOcclusionPayload;
import tacz.aos.occlusion.OccluderBox;
import tacz.aos.occlusion.OcclusionShapes;
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
    @GameTest(template="ballistics",batch="occlusion")
    public static void vegetationAndTransparentMaterialsDoNotOccludeButSolidCoverAndLeavesDo(GameTestHelper h) {
        for(int x=2;x<=18;x++)for(int y=4;y<=10;y++)for(int z=4;z<=12;z++)h.setBlock(new BlockPos(x,y,z),Blocks.AIR);
        var eye=h.absoluteVec(new Vec3(4,6,8.5));
        var target=new AABB(h.absoluteVec(new Vec3(14,5,8)),h.absoluteVec(new Vec3(15,7,9)));
        var pos=new BlockPos(10,5,8);
        for(var block:List.of(Blocks.STONE,Blocks.OAK_LEAVES,Blocks.AZALEA_LEAVES,
                Blocks.OAK_DOOR,Blocks.OAK_TRAPDOOR,Blocks.OAK_FENCE,Blocks.IRON_BARS,Blocks.CHEST)) {
            h.setBlock(pos,block);
            var boxes=ScopeOcclusion.collect(h.getLevel(),eye,target,12,new int[]{8192},32);
            h.assertTrue(!boxes.isEmpty(),"Material was skipped: "+block);
            if(block==Blocks.OAK_LEAVES) {
                var a=h.absolutePos(pos);
                h.assertTrue(boxes.stream().anyMatch(b->b.minX()==a.getX() && b.maxX()==a.getX()+1 && b.minY()==a.getY() && b.maxY()==a.getY()+1),
                        "Leaf texture holes were preserved instead of opaque leaf geometry");
            }
        }
        for(var clear:List.of(Blocks.SHORT_GRASS,Blocks.TALL_GRASS,Blocks.FERN,Blocks.LARGE_FERN,Blocks.WHEAT,
                Blocks.CARROTS,Blocks.POTATOES,Blocks.BEETROOTS,Blocks.NETHER_WART,Blocks.TORCHFLOWER_CROP,
                Blocks.PITCHER_CROP,Blocks.COCOA,Blocks.SUGAR_CANE,Blocks.SWEET_BERRY_BUSH,
                Blocks.DANDELION,Blocks.OAK_SAPLING,Blocks.VINE,Blocks.GLOW_LICHEN,Blocks.KELP,
                Blocks.HANGING_ROOTS,Blocks.SPORE_BLOSSOM,Blocks.WATER,Blocks.FIRE,
                Blocks.GLASS,Blocks.GLASS_PANE,Blocks.TINTED_GLASS,Blocks.RED_STAINED_GLASS,Blocks.BLUE_STAINED_GLASS_PANE,
                Blocks.ICE,Blocks.SLIME_BLOCK,Blocks.HONEY_BLOCK)) {
            // Test the state directly too: unsupported plants must not pass merely by breaking into air.
            h.assertTrue(OcclusionShapes.boxes(clear.defaultBlockState(),h.getLevel(),h.absolutePos(pos)).isEmpty(),
                    "Transparent material/vegetation became cover: "+clear);
            h.setBlock(pos,clear);
            h.assertTrue(ScopeOcclusion.collect(h.getLevel(),eye,target,12,new int[]{8192},32).isEmpty(),"Excluded material was sent as cover: "+clear);
        }
        h.setBlock(pos,Blocks.AIR);
        h.assertTrue(ScopeOcclusion.collect(h.getLevel(),eye,target,12,new int[]{8192},32).isEmpty(),"Air became an occluder");
        h.succeed();
    }

    @GameTest(template="empty",batch="occlusion")
    public static void slabsStairsDoorsAndTrapdoorsKeepTheirActualOpenings(GameTestHelper h) {
        var level=h.getLevel();var pos=h.absolutePos(BlockPos.ZERO);
        var slab=Blocks.STONE_SLAB.defaultBlockState();
        var bottom=OcclusionShapes.boxes(slab.setValue(SlabBlock.TYPE,SlabType.BOTTOM),level,pos);
        var top=OcclusionShapes.boxes(slab.setValue(SlabBlock.TYPE,SlabType.TOP),level,pos);
        h.assertTrue(contains(bottom,.5,.25,.5) && !contains(bottom,.5,.75,.5),"Bottom slab hid its upper opening");
        h.assertTrue(!contains(top,.5,.25,.5) && contains(top,.5,.75,.5),"Top slab hid its lower opening");
        var stair=Blocks.STONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING,Direction.EAST)
                .setValue(StairBlock.HALF,Half.BOTTOM).setValue(StairBlock.SHAPE,StairsShape.STRAIGHT);
        var stairs=OcclusionShapes.boxes(stair,level,pos);
        h.assertTrue(contains(stairs,.25,.25,.5) && contains(stairs,.75,.75,.5) && !contains(stairs,.25,.75,.5),
                "Stair step became a full cube or lost its solid upper half");
        var door=Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING,Direction.NORTH).setValue(DoorBlock.OPEN,false);
        var closed=OcclusionShapes.boxes(door,level,pos);
        var open=OcclusionShapes.boxes(door.setValue(DoorBlock.OPEN,true),level,pos);
        var face=closed.getFirst();
        h.assertTrue(closed.size()==1 && face.getXsize()==1 && face.getZsize()==3.0/16
                        && open.size()==1 && open.getFirst().getXsize()==3.0/16 && open.getFirst().getZsize()==1,
                "Door did not rotate its thin opaque panel when opened");
        h.assertTrue(open.stream().noneMatch(b->b.contains(face.getCenter())),"Open door retained its old occlusion");
        var trap=Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.HALF,Half.BOTTOM).setValue(TrapDoorBlock.OPEN,false);
        var horizontal=OcclusionShapes.boxes(trap,level,pos);
        var vertical=OcclusionShapes.boxes(trap.setValue(TrapDoorBlock.OPEN,true),level,pos);
        h.assertTrue(horizontal.getFirst().getYsize()==3.0/16 && vertical.getFirst().getYsize()==1
                        && !contains(horizontal,.5,.5,.5) && !contains(vertical,.5,.5,.5),
                "Trapdoor became a full cube instead of a thin panel");
        var fence=OcclusionShapes.boxes(Blocks.OAK_FENCE.defaultBlockState(),level,pos);
        h.assertTrue(contains(fence,.5,.5,.5) && !contains(fence,.1,.5,.1),"Fence gaps were filled");
        h.succeed();
    }

    @GameTest(template="empty",batch="occlusion")
    public static void complexUnknownGeometryFallsBackToWholeCellWithoutLosingProtrusions(GameTestHelper h) {
        var shape=net.minecraft.world.phys.shapes.Shapes.empty();
        for(int i=0;i<8;i++)shape=net.minecraft.world.phys.shapes.Shapes.or(shape,
                net.minecraft.world.phys.shapes.Shapes.box(i*.1+.01,.2,.2,i*.1+.04,1.4,.8));
        var simple=OcclusionShapes.parts(shape);
        h.assertTrue(simple.size()==8 && !contains(simple,.06,.5,.5),"Simple eight-part geometry lost its holes");
        shape=net.minecraft.world.phys.shapes.Shapes.or(shape,net.minecraft.world.phys.shapes.Shapes.box(.81,.2,.2,.84,1.4,.8));
        h.assertTrue(shape.toAabbs().size()>OcclusionShapes.MAX_PARTS,"Test model was not complex enough");
        var complex=OcclusionShapes.parts(shape);
        h.assertTrue(complex.size()==1 && contains(complex,.06,.5,.5) && contains(complex,.95,.1,.95)
                        && contains(complex,.5,1.2,.5),"Complex model left cell holes or lost protruding geometry");
        var unknown=OcclusionShapes.parts(net.minecraft.world.phys.shapes.Shapes.empty());
        h.assertTrue(unknown.size()==1 && contains(unknown,.5,.5,.5),"Unknown empty model failed open");
        h.succeed();
    }

    private static boolean contains(List<AABB> boxes,double x,double y,double z) {
        return boxes.stream().anyMatch(b->b.contains(x,y,z));
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
