package tacz.aos.test;

import com.tacz.guns.block.TargetBlock;
import com.tacz.guns.entity.TargetMinecart;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.*;
import net.minecraftforge.gametest.*;
import tacz.aos.TaczArtOfSniping;
import tacz.aos.ballistics.*;
import java.util.*;

@GameTestHolder(TaczArtOfSniping.MOD_ID) @PrefixGameTestTemplate(false)
public final class TaczTargetGameTests {
    static BlockPos place(net.minecraft.server.level.ServerLevel level,BlockPos pos) {
        var block=com.tacz.guns.init.ModBlocks.TARGET.get();
        var state=block.defaultBlockState().setValue(TargetBlock.FACING,Direction.EAST);
        level.setBlockAndUpdate(pos,state);level.setBlockAndUpdate(pos.above(),state.setValue(TargetBlock.HALF,DoubleBlockHalf.UPPER));
        return pos;
    }
    @GameTest(setupTicks=20, template="ballistics",timeoutTicks=140)
    public static void frontHitFallsAndScheduledTickResetsBothHalves(GameTestHelper h) {
        var f=new LocalBallisticsGameTests.Fixture(h);var level=h.getLevel();
        var pos=place(level,h.absolutePos(new BlockPos(20,5,4)));
        var source=new TargetCollisionSource(level,List.of(new AABB(pos).inflate(3)));
        var bullet=f.bullet(new Vec3(4,6.32,4.5),new Vec3(24,0,0));
        var result=TargetBallistics.tick(level,bullet,1024,source);
        h.assertTrue("BLOCK".equals(result.termination()),"Target did not collide");
        h.assertTrue(!level.getBlockState(pos).getValue(TargetBlock.STAND) && !level.getBlockState(pos.above()).getValue(TargetBlock.STAND),"Native target callback did not lower both halves");
        h.assertTrue(level.getBlockState(pos).getValue(TargetBlock.OUTPUT_POWER)>0,"Native target score missing");
        var next=f.bullet(new Vec3(4,6.32,4.5),new Vec3(24,0,0));
        var fallen=TargetBallistics.tick(level,next,1024,new TargetCollisionSource(level,List.of(new AABB(pos).inflate(3))));
        h.assertTrue("TARGET_AREA_EXIT".equals(fallen.termination()),"Fallen upper target should be passed before stopping at the region boundary: "+fallen);next.discard();
        h.runAfterDelay(105,()->{
            h.assertTrue(level.getBlockState(pos).getValue(TargetBlock.STAND) && level.getBlockState(pos.above()).getValue(TargetBlock.STAND),"Scheduled native reset failed");
            h.assertTrue(level.getBlockState(pos).getValue(TargetBlock.OUTPUT_POWER)==0,"Redstone did not reset");h.succeed();
        });
    }
    @GameTest(setupTicks=20, template="ballistics")
    public static void backAndOutsideCoreDoNotKnockDownTarget(GameTestHelper h) {
        var f=new LocalBallisticsGameTests.Fixture(h);var level=h.getLevel();var pos=place(level,h.absolutePos(new BlockPos(20,5,4)));
        var shot=f.bullet(new Vec3(28,6.3,4.5),new Vec3(-16,0,0));
        TargetBallistics.tick(level,shot,1024,new TargetCollisionSource(level,List.of(new AABB(pos).inflate(3))));
        h.assertTrue(level.getBlockState(pos).getValue(TargetBlock.STAND),"Back hit should preserve TACZ's directional rule");
        shot=f.bullet(new Vec3(4,6.3,4.5),new Vec3(24,0,0));
        TargetBallistics.tick(level,shot,1024,new TargetCollisionSource(level,List.of()));
        h.assertTrue(level.getBlockState(pos).getValue(TargetBlock.STAND),"Transit target was hit outside selected core");shot.discard();h.succeed();
    }
    @GameTest(setupTicks=20, template="ballistics",batch="target_minecart",timeoutTicks=30)
    public static void minecartUsesNativeHitAndRecovery(GameTestHelper h) {
        var f=new LocalBallisticsGameTests.Fixture(h);var pos=h.absoluteVec(new Vec3(12,5,4.5));
        // Isolate native hit/recovery from parallel structure preparation; initialize compensated position.
        var cart=new TargetMinecart(h.getLevel(),pos.x,pos.y,pos.z);cart.setNoGravity(true);cart.setOldPosAndRot();h.getLevel().addFreshEntity(cart);
        var shot=f.bullet(new Vec3(4,6,4.5),new Vec3(24,0,0));
        h.assertTrue(h.getLevel().getEntity(cart.getId())==cart,"Minecart fixture is not accessible in its section");
        var step=TargetBallistics.tick(h.getLevel(),shot,1024,new TargetCollisionSource(h.getLevel(),List.of(new AABB(pos,pos).inflate(4))));
        h.assertTrue(cart.getHurtTime()>0 && cart.getDamage()>0 && !cart.isRemoved(),"Native target minecart hit feedback missing: "+step+", hurt="+cart.getHurtTime()+", damage="+cart.getDamage()+", box="+cart.getBoundingBox()+", owner="+shot.getOwner());
        shot.discard();
        h.runAfterDelay(15,()->{h.assertTrue(cart.getHurtTime()==0 && !cart.isRemoved(),"Minecart feedback failed to recover");h.succeed();});
    }
    @GameTest(setupTicks=20, template="empty")
    public static void blockTargetPacketPreservesStateAndRejectsOtherBlocks(GameTestHelper h) {
        var state=com.tacz.guns.init.ModBlocks.TARGET.get().defaultBlockState().setValue(TargetBlock.STAND,false);
        var tag=new net.minecraft.nbt.CompoundTag();tag.putString("CustomName","\"Test\"");
        var payload=new tacz.aos.network.ScopeBlockTargetsPayload(h.getLevel().dimension().location(),List.of(
                new tacz.aos.network.ScopeBlockTargetsPayload.Target(new BlockPos(900,80,-4),state,15728880,tag)));
        var buf=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            var codec=tacz.aos.network.ScopeBlockTargetsPayload.STREAM_CODEC;codec.encode(buf,payload);
            h.assertTrue(codec.decode(buf).equals(payload),"Target state/metadata packet lost information");
        } finally {buf.release();}
        boolean rejected=false;
        try {new tacz.aos.network.ScopeBlockTargetsPayload(h.getLevel().dimension().location(),List.of(
            new tacz.aos.network.ScopeBlockTargetsPayload.Target(BlockPos.ZERO,Blocks.STONE.defaultBlockState(),0,tag)));}
        catch(IllegalArgumentException expected){rejected=true;}
        h.assertTrue(rejected,"Packet accepted arbitrary world blocks");h.succeed();
    }
}
