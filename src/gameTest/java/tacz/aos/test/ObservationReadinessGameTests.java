package tacz.aos.test;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.*;
import tacz.aos.TaczArtOfSniping;
import tacz.aos.scoping.*;
import java.util.*;

@GameTestHolder(TaczArtOfSniping.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ObservationReadinessGameTests {
    @GameTest(setupTicks=20,template="ballistics",batch="observation_readiness")
    public static void residentHaloAndRevokedCoreCannotAuthorizeEntities(GameTestHelper h) {
        var p=FakePlayerFactory.get(h.getLevel(),new GameProfile(UUID.randomUUID(),"ready-observer"));
        new LocalBallisticsGameTests.Fixture(h,p);
        var target=p.getEyePosition().add(10,0,0);
        var body=new AABB(target,target).inflate(.4);
        h.assertTrue(!ScopeActivity.INSTANCE.observable(p,body),"Resident terrain alone authorized a remote body");
        ObservationFixture.ready(h,p,target,()->{
            var a=ScopeActivity.INSTANCE;
            h.assertTrue(a.observable(p,body),"Simulated owned core was not ready");
            var core=a.observationBounds(p).get(0);
            var halo=body.move(core.maxX-body.minX+1,0,0);
            h.assertTrue(h.getLevel().getChunkSource().getChunkNow((int)Math.floor(halo.minX/16),(int)Math.floor(halo.minZ/16))!=null,
                    "Fixture halo was not resident");
            h.assertTrue(!a.observable(p,halo),"Dependency halo was treated as collision/simulation core");
            h.assertTrue(!a.observable(p,body.move(core.maxX-body.maxX+.1,0,0)),"Partially uncovered body was authorized");
            a.releaseAll();
            h.assertTrue(!a.observable(p,body),"Revoked ownership retained display authorization");
            h.succeed();
        });
    }

    @GameTest(setupTicks=20,template="ballistics",batch="observation_cache")
    public static void validMaskSurvivesBudgetPressureButBlockChangesInvalidateIt(GameTestHelper h) throws Exception {
        var level=h.getLevel();
        var p=FakePlayerFactory.get(level,new GameProfile(UUID.randomUUID(),"mask-observer"));
        var pos=h.absoluteVec(new Vec3(12,4,4));pos=new Vec3(pos.x,250,pos.z);
        p.setPos(pos.add(-880,0,0));
        var cow=EntityType.COW.create(level);cow.setPos(pos);
        var cover=BlockPos.containing(pos.add(-4,0,0));
        int cx=cover.getX()>>4,cz=cover.getZ()>>4;
        for(int x=cx-3;x<=cx+3;x++)for(int z=cz-3;z<=cz+3;z++)level.getChunk(x,z);
        var old=level.getBlockState(cover);level.setBlockAndUpdate(cover,Blocks.OAK_LOG.defaultBlockState());
        var budget=ScopeOcclusion.class.getDeclaredField("remaining");budget.setAccessible(true);
        try {
            ScopeOcclusion.clear();ScopeOcclusion.beginTick(1,Set.of(p.getUUID()));
            var first=ScopeOcclusion.prepare(p,List.of(cow));
            h.assertTrue(first.targets().size()==1 && !first.targets().get(0).boxes().isEmpty(),"Complete cover was not published");
            cow.setPos(pos.add(0,0,.4));
            ScopeOcclusion.beginTick(100,Set.of(p.getUUID()));budget.setInt(null,0);
            h.assertTrue(ScopeOcclusion.prepare(p,List.of(cow)).targets().equals(first.targets()),"Valid mask expired or flickered under budget pressure");
            level.setBlockAndUpdate(cover,Blocks.AIR.defaultBlockState());
            h.assertTrue(ScopeOcclusion.prepare(p,List.of(cow)).targets().isEmpty(),"Changed block retained stale cover authorization");
            ScopeOcclusion.beginTick(101,Set.of(p.getUUID()));
            h.assertTrue(ScopeOcclusion.prepare(p,List.of(cow)).targets().size()==1,"Observation did not resume after geometry refresh");
            p.setPos(p.position().add(0,0,1));budget.setInt(null,0);
            h.assertTrue(ScopeOcclusion.prepare(p,List.of(cow)).targets().isEmpty(),"Old viewpoint authorized new unverified cover");
        } finally {level.setBlockAndUpdate(cover,old);ScopeOcclusion.clear();}
        h.succeed();
    }
}
