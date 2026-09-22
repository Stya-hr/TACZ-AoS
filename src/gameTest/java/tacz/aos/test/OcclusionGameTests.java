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
    @GameTest(setupTicks=20, template="ballistics",batch="occlusion")
    public static void vegetationUsesTextureChannelWhileSolidCoverAndLeavesUseBoxes(GameTestHelper h) {
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
        for(var clear:List.of(Blocks.GRASS,Blocks.TALL_GRASS,Blocks.FERN,Blocks.LARGE_FERN,Blocks.WHEAT,
                Blocks.CARROTS,Blocks.POTATOES,Blocks.BEETROOTS,Blocks.NETHER_WART,Blocks.TORCHFLOWER_CROP,
                Blocks.PITCHER_CROP,Blocks.COCOA,Blocks.SUGAR_CANE,Blocks.SWEET_BERRY_BUSH,
                Blocks.DANDELION,Blocks.OAK_SAPLING,Blocks.VINE,Blocks.GLOW_LICHEN,Blocks.KELP,
                Blocks.HANGING_ROOTS,Blocks.SPORE_BLOSSOM,Blocks.WATER,Blocks.FIRE,
                Blocks.GLASS,Blocks.GLASS_PANE,Blocks.TINTED_GLASS,Blocks.RED_STAINED_GLASS,Blocks.BLUE_STAINED_GLASS_PANE,
                Blocks.ICE,Blocks.SLIME_BLOCK,Blocks.HONEY_BLOCK)) {
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
        var face=closed.get(0);
        h.assertTrue(closed.size()==1 && face.getXsize()==1 && face.getZsize()==3.0/16
                        && open.size()==1 && open.get(0).getXsize()==3.0/16 && open.get(0).getZsize()==1,
                "Door did not rotate its thin opaque panel when opened");
        h.assertTrue(open.stream().noneMatch(b->b.contains(face.getCenter())),"Open door retained its old occlusion");
        var trap=Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.HALF,Half.BOTTOM).setValue(TrapDoorBlock.OPEN,false);
        var horizontal=OcclusionShapes.boxes(trap,level,pos);
        var vertical=OcclusionShapes.boxes(trap.setValue(TrapDoorBlock.OPEN,true),level,pos);
        h.assertTrue(horizontal.get(0).getYsize()==3.0/16 && vertical.get(0).getYsize()==1
                        && !contains(horizontal,.5,.5,.5) && !contains(vertical,.5,.5,.5),
                "Trapdoor became a full cube instead of a thin panel");
        var fence=OcclusionShapes.boxes(Blocks.OAK_FENCE.defaultBlockState(),level,pos);
        h.assertTrue(contains(fence,.5,.5,.5) && !contains(fence,.1,.5,.1),"Fence gaps were filled");
        h.succeed();
    }

    @GameTest(setupTicks=20, template="empty",batch="occlusion")
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
        var campfire=OcclusionShapes.boxes(Blocks.CAMPFIRE.defaultBlockState(),h.getLevel(),h.absolutePos(BlockPos.ZERO));
        h.assertTrue(contains(campfire,.5,.9,.5),"Complex campfire model was mistaken for its short selection box");
        h.succeed();
    }

    private static boolean contains(List<AABB> boxes,double x,double y,double z) {
        return boxes.stream().anyMatch(b->b.contains(x,y,z));
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

    @GameTest(setupTicks=20, template="ballistics",batch="occlusion")
    public static void unknownOrTruncatedCoverNeverMeansVerifiedClear(GameTestHelper h) {
        var missing=new AABB(13000000,80,13000000,13000001,82,13000001);
        int loaded=h.getLevel().getChunkSource().getLoadedChunksCount();
        var unknown=ScopeOcclusion.collectGeometry(h.getLevel(),new Vec3(12999980,82,13000000),missing,24,new int[]{8192},32,64);
        h.assertTrue(!unknown.complete(),"Missing real chunks were advertised as a clear sightline");
        var eye=h.absoluteVec(new Vec3(4,6,8.5));
        var target=new AABB(h.absoluteVec(new Vec3(14,5,8)),h.absoluteVec(new Vec3(15,7,9)));
        h.setBlock(new BlockPos(10,5,8),Blocks.OAK_LOG);
        var truncated=ScopeOcclusion.collectGeometry(h.getLevel(),eye,target,12,new int[]{8192},0,64);
        h.assertTrue(!truncated.complete(),"Network shape cap silently discarded the log wall");
        h.assertTrue(h.getLevel().getChunkSource().getLoadedChunksCount()==loaded,"Occlusion loaded an unknown chunk");
        h.succeed();
    }

    @GameTest(setupTicks=20, template="ballistics",batch="occlusion")
    public static void retainedLogCoverSurvivesAimReorderingAndGlobalBudgetPressure(GameTestHelper h) {
        var level=h.getLevel();
        for(int x=2;x<=18;x++)for(int y=4;y<=10;y++)for(int z=4;z<=12;z++)h.setBlock(new BlockPos(x,y,z),Blocks.AIR);
        var packets=new ArrayList<ScopeOcclusionPayload>();
        var player=new net.minecraftforge.common.util.FakePlayer(level,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"cover-reader"));
        player.connection=new net.minecraft.server.network.ServerGamePacketListenerImpl(level.getServer(),
                new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND) {
                    @Override public void send(net.minecraft.network.protocol.Packet<?> packet) {
                        if(packet instanceof net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket p
                                && TestPackets.decode(p) instanceof ScopeOcclusionPayload cover)packets.add(cover);
                    }
                },player);
        player.setPos(h.absoluteVec(new Vec3(4,5,8.5)));
        player.setLastSectionPos(net.minecraft.core.SectionPos.of(player)); // Server watch radius is not client terrain proof.
        var cow=net.minecraft.world.entity.EntityType.COW.create(level);cow.setPos(h.absoluteVec(new Vec3(14,5,8.5)));
        var unseen=net.minecraft.world.entity.EntityType.COW.create(level);unseen.setPos(h.absoluteVec(new Vec3(15,5,9)));
        h.setBlock(new BlockPos(10,5,8),Blocks.OAK_LOG);h.setBlock(new BlockPos(10,6,8),Blocks.OAK_LOG);
        ScopeOcclusion.clear();ScopeOcclusion.beginTick(100,Set.of(player.getUUID()));
        ScopeOcclusion.update(player,List.of(cow));
        var first=packets.get(packets.size()-1).targets().get(0);
        h.assertTrue(first.complete() && !first.boxes().isEmpty(),"Real log cover was skipped based on native tracking radius");
        // Exhaust the global budget using unrelated unknown observations; the old geometry must remain valid.
        var pressure=new net.minecraftforge.common.util.FakePlayer(level,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"cover-pressure"));
        var distractions=new ArrayList<net.minecraft.world.entity.Entity>();
        for(int i=0;i<32;i++) {
            var e=net.minecraft.world.entity.EntityType.COW.create(level);e.setPos(13000000+i*128,80,13000000);
            e.setBoundingBox(e.getBoundingBox().inflate(6));distractions.add(e);
        }
        ScopeOcclusion.beginTick(101,Set.of(player.getUUID(),pressure.getUUID()));
        for(int i=0;i<4;i++)ScopeOcclusion.update(pressure,distractions);
        player.setYRot(90);ScopeOcclusion.update(player,List.of(unseen,cow));
        var next=packets.get(packets.size()-1).targets();
        h.assertTrue(next.stream().anyMatch(t->t.id()==cow.getId() && t.complete() && t.boxes().equals(first.boxes())),
                "Aim order/budget pressure cleared a still valid log mask");
        h.assertTrue(next.stream().anyMatch(t->t.id()==unseen.getId() && !t.complete()),"Unchecked new entity became visible without cover");
        h.setBlock(new BlockPos(10,5,8),Blocks.AIR);h.setBlock(new BlockPos(10,6,8),Blocks.AIR);
        ScopeOcclusion.beginTick(102,Set.of(player.getUUID()));ScopeOcclusion.update(player,List.of(cow));
        var clear=packets.get(packets.size()-1).targets().get(0);
        h.assertTrue(clear.complete() && clear.boxes().isEmpty(),"Removing actual cover failed to publish a verified clear corridor");
        ScopeOcclusion.clear();h.succeed();
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
