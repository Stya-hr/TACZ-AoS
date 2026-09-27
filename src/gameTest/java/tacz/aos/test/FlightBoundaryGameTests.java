package tacz.aos.test;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import net.minecraftforge.gametest.*;
import tacz.aos.ballistics.*;
import tacz.aos.trails.*;

@GameTestHolder("tacz_aos") @PrefixGameTestTemplate(false)
public final class FlightBoundaryGameTests {
    private static AABB box(double a,double b){return new AABB(a,0,0,b,10,10);}
    @GameTest(template="empty",batch="flight_boundary")
    public static void intervalUnionHandlesHighSpeedTouchingReverseAndGaps(GameTestHelper h) {
        var b=new TargetFlightBoundary();var p=new Vec3(0,5,5);
        var transit=b.limit(p,new Vec3(4,0,0),List.of(box(8,12)));
        h.assertTrue(!transit.stop()&&!b.entered(),"Transit stopped before reaching target");
        var fast=b.limit(p,new Vec3(32,0,0),List.of(box(18,22),box(8,12),box(12,20)));
        h.assertTrue(fast.stop()&&Math.abs(fast.fraction()-22.0/32)<1e-9,"Touching/overlapping union ended early or tunneled");
        var gap=new TargetFlightBoundary().limit(p,new Vec3(32,0,0),List.of(box(8,12),box(13,22)));
        h.assertTrue(gap.stop()&&Math.abs(gap.fraction()-12.0/32)<1e-9,"Flight jumped to a disconnected region");
        var reverse=new TargetFlightBoundary().limit(new Vec3(30,5,5),new Vec3(-30,0,0),List.of(box(8,12),box(12,22)));
        h.assertTrue(reverse.stop()&&Math.abs(reverse.fraction()-22.0/30)<1e-9,"Reverse boundary incorrect");
        var edge=new TargetFlightBoundary().limit(p,new Vec3(12,0,0),List.of(box(8,12),box(12,20)));
        h.assertTrue(!edge.stop(),"Exact touching boundary truncated continuous coverage");
        var tangent=new TargetFlightBoundary();tangent.limit(new Vec3(0,10,5),new Vec3(30,0,0),List.of(box(8,12)));
        h.assertTrue(!tangent.entered(),"Tangential transit incorrectly entered target");
        var retained=new TargetFlightBoundary();retained.limit(new Vec3(9,5,5),Vec3.ZERO,List.of(box(8,12)));
        h.assertTrue(retained.entered()&&retained.limit(new Vec3(9,5,5),new Vec3(1,0,0),List.of()).stop(),"Lost coverage reset entered state");
        var turn=new TargetFlightBoundary();turn.limit(new Vec3(9,5,5),new Vec3(1,0,0),List.of(box(8,12)));
        h.assertTrue(Math.abs(turn.limit(new Vec3(10,5,5),new Vec3(-8,0,0),List.of(box(8,12))).fraction()-.25)<1e-9,"Reversal escaped through entry face");
        var vertical=new TargetFlightBoundary().limit(new Vec3(9,5,5),new Vec3(0,-10,0),List.of(box(8,12)));
        h.assertTrue(vertical.stop()&&Math.abs(vertical.fraction()-.5)<1e-9,"Vertical exit was ignored");h.succeed();
    }
    private static AABB region(GameTestHelper h,int a,int b){return new AABB(h.absoluteVec(new Vec3(a,0,0)),h.absoluteVec(new Vec3(b,16,24)));}
    @GameTest(setupTicks=20,template="ballistics",batch="flight_boundary")
    public static void piercingHitsInsideCoreThenStopsAtBoundaryAndSmokeFades(GameTestHelper h) {
        var f=new LocalBallisticsGameTests.Fixture(h);
        var first=h.spawn(EntityType.COW,new Vec3(10,5,4.5));var second=h.spawn(EntityType.COW,new Vec3(14,5,4.5));
        var outside=h.spawn(EntityType.COW,new Vec3(20,5,4.5));
        for(var cow:List.of(first,second,outside)){cow.setNoAi(true);cow.setNoGravity(true);}
        float firstHealth=first.getHealth(),secondHealth=second.getHealth(),outsideHealth=outside.getHealth();
        var bullet=f.bullet(new Vec3(4,5.5,4.5),new Vec3(24,0,0));
        LocalBallisticsGameTests.setField(bullet,"pierce",5);var start=bullet.position();bullet.tickCount++;
        int loaded=h.getLevel().getChunkSource().getLoadedChunksCount();
        var step=TargetBallistics.tick(h.getLevel(),bullet,128,new TargetCollisionSource(h.getLevel(),List.of(region(h,8,16))),new TargetFlightBoundary());
        h.assertTrue(first.getHealth()<firstHealth&&second.getHealth()<secondHealth,"Region exit disabled native multi-entity penetration");
        h.assertTrue(outside.getHealth()==outsideHealth,"Outside entity was damaged after region exit");
        h.assertTrue("TARGET_AREA_EXIT".equals(step.termination())&&bullet.isRemoved()&&Math.abs(step.distance()-12)<.001,"Bullet did not stop at target boundary: "+step);
        h.assertTrue(h.getLevel().getChunkSource().getLoadedChunksCount()==loaded,"Boundary check loaded chunks");
        var end=bullet.position();var history=new TrailHistory();
        history.accept(new TrailSegment(41,1,start.x,start.y,start.z,end.x,end.y,end.z,24,true),10);
        history.expire(12);
        h.assertTrue(history.hasVisibleSamples(12)&&!history.hasMovingHead(41,12),"Boundary stop removed the wake or left a moving head");
        h.assertTrue(history.samples().stream().allMatch(sample->sample.segment().x1()<=end.x),"Smoke extended past terminal boundary");
        history.expire(100);h.assertTrue(history.isEmpty(),"Boundary wake never faded");
        for(var cow:List.of(first,second,outside))cow.discard();h.succeed();
    }
    @GameTest(setupTicks=20,template="ballistics",batch="flight_boundary")
    public static void innerWallWinsWhileDisjointRegionCannotReceiveDamage(GameTestHelper h) {
        var f=new LocalBallisticsGameTests.Fixture(h);h.setBlock(new BlockPos(15,5,4),Blocks.STONE);
        var bullet=f.bullet(new Vec3(4,5.5,4.5),new Vec3(24,0,0));bullet.tickCount++;
        var source=new TargetCollisionSource(h.getLevel(),List.of(region(h,8,16)));
        var hit=TargetBallistics.tick(h.getLevel(),bullet,128,source,new TargetFlightBoundary());
        h.assertTrue("BLOCK".equals(hit.termination())&&Math.abs(hit.distance()-11)<.001,"Boundary superseded a nearer block");
        h.setBlock(new BlockPos(15,5,4),Blocks.AIR);
        var cow=h.spawn(EntityType.COW,new Vec3(19,5,4.5));cow.setNoAi(true);cow.setNoGravity(true);float health=cow.getHealth();
        bullet=f.bullet(new Vec3(4,5.5,4.5),new Vec3(24,0,0));bullet.tickCount++;
        var gap=TargetBallistics.tick(h.getLevel(),bullet,128,new TargetCollisionSource(h.getLevel(),List.of(region(h,8,12),region(h,16,24))),new TargetFlightBoundary());
        h.assertTrue("TARGET_AREA_EXIT".equals(gap.termination())&&Math.abs(gap.distance()-8)<.001&&cow.getHealth()==health,"Bullet jumped across a gap into another loaded core: "+gap+", health="+cow.getHealth());
        cow.discard();h.succeed();
    }
    @GameTest(setupTicks=20,template="ballistics",batch="flight_boundary")
    public static void enteredStateSurvivesTicksAndUnavailableTargetIsNotAir(GameTestHelper h) {
        var f=new LocalBallisticsGameTests.Fixture(h);var state=new TargetFlightBoundary();
        var bullet=f.bullet(new Vec3(4,5.5,4.5),new Vec3(6,0,0));bullet.tickCount++;
        var first=TargetBallistics.tick(h.getLevel(),bullet,128,new TargetCollisionSource(h.getLevel(),List.of(region(h,8,16))),state);
        h.assertTrue(first.termination()==null&&state.entered(),"Flight failed to enter the core");
        bullet.tickCount++;var next=TargetBallistics.tick(h.getLevel(),bullet,122,new TargetCollisionSource(h.getLevel(),List.of()),state);
        h.assertTrue("TARGET_AREA_EXIT".equals(next.termination())&&next.distance()==0,"Lost lease resumed unrestricted transit");
        bullet=f.bullet(new Vec3(4,5.5,4.5),new Vec3(8,0,0));bullet.setPos(13000000,80,13000000);bullet.tickCount++;
        int loaded=h.getLevel().getChunkSource().getLoadedChunksCount();
        var unknown=TargetBallistics.tick(h.getLevel(),bullet,128,new TargetCollisionSource(h.getLevel(),List.of(new AABB(bullet.position(),bullet.position()).inflate(32))),new TargetFlightBoundary());
        h.assertTrue("UNKNOWN_AREA".equals(unknown.termination())&&unknown.distance()==0,"Missing target chunk became collision-free transit");
        h.assertTrue(loaded==h.getLevel().getChunkSource().getLoadedChunksCount(),"Unknown target lookup loaded terrain");h.succeed();
    }
}
