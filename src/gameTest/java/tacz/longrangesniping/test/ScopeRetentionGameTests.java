package tacz.longrangesniping.test;
import com.mojang.authlib.GameProfile;
import com.tacz.guns.api.entity.IGunOperator;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import tacz.longrangesniping.TaczLongRangeSniping;
import tacz.longrangesniping.config.ServerConfig;
import tacz.longrangesniping.scoping.*;
import java.util.*;
@GameTestHolder(TaczLongRangeSniping.MOD_ID) @PrefixGameTestTemplate(false)
public final class ScopeRetentionGameTests {
    @GameTest(template="ballistics",batch="scope_retention",timeoutTicks=300)
    public static void sharedRegionBudgetAndIndependentFlightLease(GameTestHelper h) {
        var level=h.getLevel();var old=ServerConfig.current();var activity=ScopeActivity.INSTANCE;
        var a=FakePlayerFactory.get(level,new GameProfile(UUID.randomUUID(),"target-a"));
        var b=FakePlayerFactory.get(level,new GameProfile(UUID.randomUUID(),"target-b"));
        var f=new LocalBallisticsGameTests.Fixture(h,a);new LocalBallisticsGameTests.Fixture(h,b);
        register(a,true);register(b,true);
        ScopeActivity.FlightLease flight=null;
        try {
            LocalBallisticsGameTests.setSettings(new ServerConfig.Settings(true,old.policy(),1024,32,false,old.ballistics(),old.scopeRendering(),
                new ServerConfig.Activity(true,0,2,5,25,4096,1,20,600),old.prediction()));
            var event=new ServerTickEvent.Pre(()->true,level.getServer());activity.tick(event);
            Vec3 eye=a.getEyePosition(),target=eye.add(96,0,0),other=eye.add(160,0,0);
            for(int x=a.chunkPosition().x-3;x<=(int)Math.floor(other.x/16)+3;x++)
                for(int z=a.chunkPosition().z-3;z<=a.chunkPosition().z+3;z++)level.getChunk(x,z);
            activity.consider(a,target);activity.tick(event);activity.tick(event);activity.consider(a,target);
            h.assertTrue(activity.status().regions()==1,"Generated target not admitted");
            activity.consider(b,target);activity.tick(event);activity.tick(event);activity.consider(b,target);
            h.assertTrue(activity.observationBounds(b).size()==1 && activity.status().regions()==1,"Shared target duplicated or not shared");
            activity.consider(a,other);activity.tick(event);activity.tick(event);activity.consider(a,other);
            h.assertTrue(activity.status().regions()==1 && activity.status().budgetDenied()>0,"Region budget bypassed");
            var bullet=f.bullet(new Vec3(4,5,4),new Vec3(1,0,0));
            // The second player still owns the shared region; pin that ownership for the shot.
            flight=activity.retainForShot(bullet,b,100,1024);
            h.assertTrue(!flight.bounds().isEmpty(),"Shot failed to retain its target");
            IGunOperator.fromLivingEntity(a).aim(false);IGunOperator.fromLivingEntity(b).aim(false);
            for(int i=0;i<12;i++)activity.tick(event);
            h.assertTrue(activity.observationBounds(a).isEmpty() && activity.observationBounds(b).isEmpty(),"Scope grace did not expire");
            h.assertTrue(activity.status().regions()==1 && activity.status().flightPins()==1 && !flight.bounds().isEmpty(),"Unscope canceled flight pin");
            flight.close();activity.tick(event);
            h.assertTrue(activity.status().regions()==0,"Final reference did not release region");bullet.discard();
        } finally {
            if(flight!=null)flight.close();activity.releaseAll();register(a,false);register(b,false);
            ScopeSubscriptions.INSTANCE.replace(a,List.of());ScopeSubscriptions.INSTANCE.replace(b,List.of());LocalBallisticsGameTests.setSettings(old);
        }
        h.succeed();
    }
    @SuppressWarnings("unchecked") private static void register(net.minecraft.server.level.ServerPlayer p,boolean add) {
        try {
            var list=p.server.getPlayerList();var pf=net.minecraft.server.players.PlayerList.class.getDeclaredField("players");
            var uf=net.minecraft.server.players.PlayerList.class.getDeclaredField("playersByUUID");pf.setAccessible(true);uf.setAccessible(true);
            var players=(List<net.minecraft.server.level.ServerPlayer>)pf.get(list);var ids=(Map<UUID,net.minecraft.server.level.ServerPlayer>)uf.get(list);
            if(add){var channel=net.minecraft.network.Connection.class.getDeclaredField("channel");channel.setAccessible(true);
                channel.set(p.connection.getConnection(),new io.netty.channel.embedded.EmbeddedChannel());players.add(p);ids.put(p.getUUID(),p);}
            else{players.remove(p);ids.remove(p.getUUID());p.connection.getConnection().channel().close();}
        }catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
}
