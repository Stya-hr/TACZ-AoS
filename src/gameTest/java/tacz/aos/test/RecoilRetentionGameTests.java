package tacz.aos.test;

import java.util.*;
import com.mojang.authlib.GameProfile;
import com.tacz.guns.api.entity.IGunOperator;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.*;
import tacz.aos.scoping.*;
import tacz.aos.network.ScopeViewPayload;

@GameTestHolder("tacz_aos") @PrefixGameTestTemplate(false)
public final class RecoilRetentionGameTests {
    @GameTest(template="ballistics",batch="recoil_retention",setupTicks=20,timeoutTicks=100)
    public static void recoilRetainsLiveTargetButExpiresAndRevokes(GameTestHelper h) {
        var p=new FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),"recoil-test"));
        new LocalBallisticsGameTests.Fixture(h,p);
        p.setYRot(-90);p.setYHeadRot(-90);p.setXRot(0);
        var cow=h.spawn(EntityType.COW,new Vec3(14,5,4));cow.setNoAi(true);cow.setNoGravity(true);cow.setPos(p.getX()+10,p.getEyeY()-cow.getBbHeight()/2,p.getZ());
        ScopeViews.INSTANCE.receive(p,new ScopeViewPayload(p.level().dimension().location(),25,(float)Math.toRadians(8)));
        var subscriptions=ScopeSubscriptions.INSTANCE;
        h.assertTrue(subscriptions.select(p,25).contains(cow),"Initial target not selected: eye="+p.getEyePosition()+", cow="+cow.position()+", look="+p.getViewVector(1)+", range="+tacz.aos.config.ServerConfig.current().maxDistance());
        subscriptions.replace(p,List.of(cow));
        p.setXRot(-35);
        h.assertTrue(subscriptions.select(p,25).contains(cow),"Recoil immediately revoked live target");
        // Renewing the observer list must not renew the off-axis grace deadline.
        subscriptions.replace(p,subscriptions.select(p,25));
        h.runAfterDelay(8,()->{
            h.assertTrue(subscriptions.select(p,25).contains(cow),"Short recoil lost subscription");
            p.setXRot(0);
            h.assertTrue(subscriptions.select(p,25).contains(cow),"Returning aim did not recover immediately");
            p.setXRot(-35);
            subscriptions.replace(p,subscriptions.select(p,25));
        });
        h.runAfterDelay(72,()->{
            h.assertTrue(!subscriptions.select(p,25).contains(cow),"Off-axis lease renewed itself forever");
            p.setXRot(0);subscriptions.replace(p,subscriptions.select(p,25));
            cow.setInvisible(true);
            h.assertTrue(!subscriptions.select(p,25).contains(cow),"Grace bypassed invisibility");
            cow.setInvisible(false);
            IGunOperator.fromLivingEntity(p).aim(false);
            h.assertTrue(!subscriptions.authorized(p,cow),"Unscoping retained authorization");
            subscriptions.replace(p,List.of());cow.discard();h.succeed();
        });
    }

    @SuppressWarnings("unchecked")
    @GameTest(template="ballistics",batch="recoil_retention",setupTicks=20)
    public static void observationFollowsRegionAndFlightWithoutExtraGrace(GameTestHelper h) throws Exception {
        var p=new FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),"region-observer"));
        new LocalBallisticsGameTests.Fixture(h,p);p.setYRot(-90);p.setYHeadRot(-90);p.setXRot(0);
        var cow=h.spawn(EntityType.COW,new Vec3(14,5,4));cow.setNoAi(true);cow.setNoGravity(true);
        cow.setPos(p.getX()+10,p.getEyeY()-cow.getBbHeight()/2,p.getZ());
        var activity=ScopeActivity.INSTANCE;
        var key=construct("Key",h.getLevel(),cow.chunkPosition().x,cow.chunkPosition().z,1);
        var region=construct("Region",key,0L);
        var regions=(Map<Object,Object>)field(activity,"regions");
        var owners=(Map<UUID,Long>)field(region,"owners");owners.put(p.getUUID(),Long.MAX_VALUE);
        regions.put(key,region);
        try {
            var subs=ScopeSubscriptions.INSTANCE;
            h.assertTrue(subs.select(p,25).contains(cow),"Initial region target missing");subs.replace(p,List.of(cow));
            p.setXRot(-50);
            h.assertTrue(subs.select(p,25).contains(cow),"Held region lost observer");
            owners.clear();
            var flights=(Map<UUID,Object>)field(region,"flights");flights.put(UUID.randomUUID(),construct("FlightHold",p.getUUID(),Long.MAX_VALUE));
            h.assertTrue(subs.select(p,25).contains(cow),"In-flight region lost observer");
            flights.clear();
            h.assertTrue(!subs.select(p,25).contains(cow),"Expired region incorrectly gained a second time grace");
            subs.replace(p,List.of());
        } finally {regions.remove(key);cow.discard();}
        h.succeed();
    }
    private static Object construct(String nested,Object... args) throws Exception {
        var c=Class.forName("tacz.aos.scoping.ScopeActivity$"+nested).getDeclaredConstructors()[0];c.setAccessible(true);return c.newInstance(args);
    }
    private static Object field(Object object,String name) throws Exception {
        var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);
    }

    @SuppressWarnings("unchecked")
    @GameTest(template="ballistics",batch="recoil_retention",setupTicks=20)
    public static void emptyRosterKeepsBoundedCoverUntilExpiryButUnscopeClears(GameTestHelper h) throws Exception {
        var p=new FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),"cover-grace"));
        new LocalBallisticsGameTests.Fixture(h,p);
        var cow=h.spawn(EntityType.COW,new Vec3(14,5,4));cow.setNoAi(true);cow.setNoGravity(true);
        ScopeOcclusion.beginTick(100,Set.of(p.getUUID()));ScopeOcclusion.update(p,List.of(cow));
        var field=ScopeOcclusion.class.getDeclaredField("sessions");field.setAccessible(true);
        var sessions=(Map<UUID,?>)field.get(null);var session=sessions.get(p.getUUID());
        var jf=session.getClass().getDeclaredField("jobs");jf.setAccessible(true);
        var jobs=(Map<Long,ScopeOcclusion.Job>)jf.get(session);
        h.assertTrue(!jobs.isEmpty(),"No cover prepared");
        var saved=new HashMap<>(jobs);
        ScopeOcclusion.beginTick(104,Set.of(p.getUUID()));ScopeOcclusion.update(p,List.of());
        h.assertTrue(jobs.size()==saved.size()&&saved.entrySet().stream().allMatch(e->jobs.get(e.getKey())==e.getValue()),"Empty roster destroyed reusable cover");
        ScopeOcclusion.beginTick(108,Set.of(p.getUUID()));ScopeOcclusion.update(p,List.of(cow));
        h.assertTrue(saved.entrySet().stream().allMatch(e->jobs.get(e.getKey())==e.getValue()),"Returning target rebuilt cover jobs");
        ScopeOcclusion.beginTick(180,Set.of(p.getUUID()));ScopeOcclusion.update(p,List.of());
        h.assertTrue(jobs.isEmpty(),"Unused cover outlived retention budget");
        ScopeOcclusion.update(p,List.of(cow));IGunOperator.fromLivingEntity(p).aim(false);
        ScopeOcclusion.update(p,List.of());
        h.assertTrue(!sessions.containsKey(p.getUUID()),"Unscoping retained cover session");
        cow.discard();h.succeed();
    }
}
