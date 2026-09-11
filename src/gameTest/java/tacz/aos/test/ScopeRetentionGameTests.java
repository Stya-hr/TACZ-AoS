package tacz.aos.test;
import com.mojang.authlib.GameProfile;
import com.tacz.guns.api.entity.IGunOperator;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import tacz.aos.TaczArtOfSniping;
import tacz.aos.config.ServerConfig;
import tacz.aos.scoping.*;
import java.util.*;
@GameTestHolder(TaczArtOfSniping.MOD_ID) @PrefixGameTestTemplate(false)
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
                new ServerConfig.Activity(true,0,2,60,25,4096,1,20,600),old.prediction()));
            var event=new ServerTickEvent.Pre(()->true,level.getServer());activity.tick(event);
            Vec3 eye=a.getEyePosition(),target=eye.add(96,0,0),other=eye.add(160,0,0);
            for(int x=a.chunkPosition().x-3;x<=(int)Math.floor(other.x/16)+3;x++)
                for(int z=a.chunkPosition().z-3;z<=a.chunkPosition().z+3;z++)level.getChunk(x,z);
            activity.consider(a,target);activity.tick(event);activity.tick(event);activity.consider(a,target);
            h.assertTrue(activity.status().regions()==1,"Generated target not admitted");
            activity.consider(b,target);activity.tick(event);activity.tick(event);activity.consider(b,target);
            h.assertTrue(activity.observationBounds(b).size()==1 && activity.status().regions()==1,"Shared target duplicated or not shared");
            for(int i=0;i<10;i++)activity.tick(event);
            activity.consider(a,other);
            h.assertTrue(activity.status().regions()==1 && activity.status().budgetDenied()>0,"Region budget bypassed");
            var bullet=f.bullet(new Vec3(4,5,4),new Vec3(1,0,0));
            // The second player still owns the shared region; pin that ownership for the shot.
            flight=activity.retainForShot(bullet,b,100,1024);
            h.assertTrue(!flight.bounds().isEmpty(),"Shot failed to retain its target");
            IGunOperator.fromLivingEntity(a).aim(false);IGunOperator.fromLivingEntity(b).aim(false);
            for(int i=0;i<65;i++)activity.tick(event);
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
    @GameTest(template="ballistics",batch="scope_throttle")
    public static void attemptsCooldownAndLatestTargetAcrossUnscoping(GameTestHelper h) {
        var level=h.getLevel();var old=ServerConfig.current();var activity=ScopeActivity.INSTANCE;
        var player=FakePlayerFactory.get(level,new GameProfile(UUID.randomUUID(),"cooldown"));
        new LocalBallisticsGameTests.Fixture(h,player);register(player,true);
        try {
            LocalBallisticsGameTests.setSettings(new ServerConfig.Settings(true,old.policy(),1024,32,false,old.ballistics(),old.scopeRendering(),
                    new ServerConfig.Activity(true,0,2,60,196,4096,4,20,600),old.prediction()));
            var event=new ServerTickEvent.Pre(()->true,level.getServer());activity.tick(event);
            Vec3 eye=player.getEyePosition(),first=eye.add(96,0,0),obsolete=eye.add(160,0,0),latest=eye.add(224,0,0);
            for(int x=player.chunkPosition().x-3;x<=(int)Math.floor(latest.x/16)+3;x++)
                for(int z=player.chunkPosition().z-3;z<=player.chunkPosition().z+3;z++)level.getChunk(x,z);
            activity.consider(player,first);
            h.assertTrue(activity.status().regions()==1,"First target delayed");
            IGunOperator.fromLivingEntity(player).aim(false);
            for(int i=0;i<5;i++)activity.tick(event);
            IGunOperator.fromLivingEntity(player).aim(true);
            activity.consider(player,obsolete);
            h.assertTrue(activity.status().regions()==1,"Unscoping bypassed new-region cooldown");
            for(int i=0;i<5;i++)activity.tick(event);
            h.assertTrue(activity.status().regions()==1,"Obsolete target was queued for delayed loading");
            activity.consider(player,latest);
            h.assertTrue(activity.observationBounds(player).stream().anyMatch(b->b.contains(latest))
                    && activity.observationBounds(player).stream().noneMatch(b->b.contains(obsolete)),"Cooldown did not use latest target");
            // Repeated failed path checks must consume the short interval, too.
            activity.releaseAll();for(int i=0;i<10;i++)activity.tick(event);
            long before=activity.status().unknown();var unknown=eye.add(800,0,0);
            activity.consider(player,unknown);activity.consider(player,unknown);
            h.assertTrue(activity.status().unknown()==before+1,"Unknown path checked repeatedly in same tick");
            for(int i=0;i<4;i++)activity.tick(event);activity.consider(player,unknown);
            h.assertTrue(activity.status().unknown()==before+1,"Unknown path bypassed request interval");
            activity.tick(event);activity.consider(player,unknown);
            h.assertTrue(activity.status().unknown()==before+2,"Unknown path was never retried");
            // A custom per-player cap must apply even when the global budget still has room.
            LocalBallisticsGameTests.setSettings(new ServerConfig.Settings(true,old.policy(),1024,32,false,old.ballistics(),old.scopeRendering(),
                    new ServerConfig.Activity(true,0,2,60,196,4096,4,20,600,2,1),old.prediction()));
            activity.tick(event);for(int i=0;i<5;i++)activity.tick(event);
            activity.consider(player,first);long denied=activity.status().budgetDenied();
            for(int i=0;i<10;i++)activity.tick(event);activity.consider(player,latest);
            h.assertTrue(activity.status().regions()==1 && activity.status().budgetDenied()==denied+1,"Configured per-player cap ignored");
        } finally {
            activity.releaseAll();register(player,false);LocalBallisticsGameTests.setSettings(old);
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
