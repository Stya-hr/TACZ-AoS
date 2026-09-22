package tacz.aos.test;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.*;
import tacz.aos.TaczArtOfSniping;
import tacz.aos.terrain.GeneratedChunks;
import tacz.aos.ballistics.*;
import java.util.*;
@GameTestHolder(TaczArtOfSniping.MOD_ID) @PrefixGameTestTemplate(false)
public final class GeneratedTargetGameTests {
    @GameTest(setupTicks=20, template="empty",batch="generated_target",timeoutTicks=600)
    public static void savedFullMetadataAndMissingGapNeverLoadTerrain(GameTestHelper h) {
        var level=h.getLevel();var index=GeneratedChunks.INSTANCE;index.clear();
        int x=31100,z=31100;
        for(int i=0;i<4;i++) {
            var tag=new CompoundTag();tag.putInt("xPos",x+i);tag.putInt("zPos",z);
            tag.putString("Status",i==2?"minecraft:features":"minecraft:full");
            level.getChunkSource().chunkMap.write(new ChunkPos(x+i,z),tag);
        }
        h.onEachTick(()->{
            // GameTest ticks are unthrottled. Give the native region I/O worker real CPU time.
            try {Thread.sleep(2);} catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}
            var value=index.path(level,new Vec3(x*16+8,80,z*16+8),new Vec3((x+3)*16+8,80,z*16+8));
            var front=index.state(level,x,z);var back=index.state(level,x+3,z);
            if(value==GeneratedChunks.State.UNKNOWN || front==GeneratedChunks.State.UNKNOWN || back==GeneratedChunks.State.UNKNOWN)return;
            h.assertTrue(value==GeneratedChunks.State.ABSENT,"Incomplete middle allowed isolated Voxy island");
            h.assertTrue(front==GeneratedChunks.State.FULL && back==GeneratedChunks.State.FULL,"Saved FULL endpoints not recognized");
            for(int i=0;i<4;i++) h.assertTrue(level.getChunkSource().getChunkNow(x+i,z)==null,"Metadata scan loaded terrain");
            h.succeed();
        });
    }
    @GameTest(setupTicks=20, template="ballistics",batch="generated_target")
    public static void transitWallsIgnoredButTargetWallsStopActualProjectile(GameTestHelper h) {
        var f=new LocalBallisticsGameTests.Fixture(h);
        h.setBlock(new BlockPos(10,5,4),Blocks.STONE);h.setBlock(new BlockPos(20,5,4),Blocks.STONE);
        var start=h.absoluteVec(new Vec3(4,5.5,4.5));
        var region=new AABB(h.absoluteVec(new Vec3(18,0,0)),h.absoluteVec(new Vec3(24,16,24)));
        var source=new TargetCollisionSource(h.getLevel(),List.of(region));
        var bullet=f.bullet(new Vec3(4,5.5,4.5),new Vec3(24,0,0));bullet.tickCount++;
        var step=TargetBallistics.tick(h.getLevel(),bullet,128,source);
        h.assertTrue("BLOCK".equals(step.termination()) && Math.abs(step.distance()-16)<.001,"Wrong target-only collision: "+step);
        var predicted=tacz.aos.scoping.ImpactPrediction.predict(
            new tacz.aos.compat.tacz.BallisticPreview(start,new Vec3(24,0,0),0,0,100),source,List.of(),128,100,65536,8192,Long.MAX_VALUE);
        h.assertTrue(predicted.target()!=null && predicted.target().distanceTo(bullet.position())<.001,"Preview and actual impact differ");
        h.succeed();
    }
    @GameTest(setupTicks=20, template="ballistics",batch="generated_target")
    public static void movingTargetCanDodgeAfterShot(GameTestHelper h) {
        var f=new LocalBallisticsGameTests.Fixture(h);
        var cow=h.spawn(net.minecraft.world.entity.EntityType.COW,new Vec3(20,5,4));cow.setNoAi(true);cow.setNoGravity(true);
        var source=new TargetCollisionSource(h.getLevel(),List.of(new AABB(h.absoluteVec(new Vec3(18,0,0)),h.absoluteVec(new Vec3(24,16,24)))));
        var bullet=f.bullet(new Vec3(4,5.5,4),new Vec3(8,0,0));
        LocalBallisticsGameTests.setField(bullet,"gravity",0f);LocalBallisticsGameTests.setField(bullet,"friction",0f);
        bullet.tickCount++;TargetBallistics.tick(h.getLevel(),bullet,128,source);
        cow.setPos(cow.position().add(0,0,6));float health=cow.getHealth();
        for(int i=0;i<3;i++){bullet.tickCount++;TargetBallistics.tick(h.getLevel(),bullet,128,source);}
        h.assertTrue(cow.getHealth()==health,"Projectile locked damage to old entity position");cow.discard();bullet.discard();h.succeed();
    }
}
